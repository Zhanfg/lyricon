/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed

import android.app.Application
import android.util.Pair
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.xposed.hook.GeneralHooker
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.SystemUIHooker

/**
 * libxposed API 102 模块入口。
 *
 * 除常规包加载外，实现 API 102 原生 Hot Reload 生命周期：
 * - onHotReloading: 旧 classloader 中同步释放 Hook/UI/Binder/线程并保存宿主 Application；
 * - onHotReloaded: 新 classloader 中撤销残余旧 HookHandle，并基于同一宿主进程直接重新挂载。
 *
 * 保存状态只传递 Android 宿主创建的 Application，不传递任何旧模块 classloader 创建的对象。
 */
@Keep
class ModuleEntry : XposedModule() {

    companion object {
        private const val TAG = "ModuleEntry"

        private val scopes = setOf(
            PackageNames.APPLICATION,
            PackageNames.SYSTEM_UI,
        )

        lateinit var instance: ModuleEntry
            private set
    }

    @Volatile
    private var activePackageName: String? = null

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        YLog.info(TAG, "onPackageReady: packageName=" + param.packageName)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        YLog.info(TAG, "onSystemServerStarting: classLoader=" + param.classLoader)
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        YLog.init(this)
        YLog.info(
            TAG,
            "onModuleLoaded: isSystemServer=" + param.isSystemServer +
                    ", processName=" + param.processName
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        if (packageName !in scopes) {
            YLog.debug(TAG, "onPackageLoaded: $packageName is not in scopes")
            return
        }

        activePackageName = packageName
        YLog.info(TAG, "onPackageLoaded: $packageName")

        GeneralHooker.hook(this, param)
        if (packageName == PackageNames.SYSTEM_UI) {
            SystemUIHooker.hook(this, param)
        }
    }

    /**
     * API 102：旧模块代码即将被替换。
     *
     * 返回 true 后框架继续热重载。清理中的单点异常会记录，但不会让已经部分释放的旧代际
     * 留在半初始化状态；新代际会重新建立全部运行时对象。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val packageName = activePackageName
        YLog.info(TAG, "onHotReloading: package=$packageName")

        val application: Application? = when (packageName) {
            PackageNames.SYSTEM_UI -> {
                val app = SystemUIHooker.appContext ?: GeneralHooker.appContext
                if (app != null) {
                    runCatching { SystemUIHooker.prepareHotReload() }
                        .onFailure { YLog.error(TAG, "SystemUI hot reload cleanup failed", it) }
                    runCatching { GeneralHooker.prepareHotReload() }
                        .onFailure { YLog.error(TAG, "General hot reload cleanup failed", it) }
                }
                app
            }

            PackageNames.APPLICATION -> {
                val app = GeneralHooker.appContext
                if (app != null) {
                    runCatching { GeneralHooker.prepareHotReload() }
                        .onFailure { YLog.error(TAG, "App hot reload cleanup failed", it) }
                }
                app
            }

            else -> null
        }

        if (application == null) {
            YLog.warning(TAG, "Rejecting hot reload: host Application is not ready")
            return false
        }

        // 与 libxposed API 102 官方示例一致：只保存宿主包名 + 宿主 ClassLoader。
        // 两者均不由旧模块 classloader 创建，可安全跨模块代际传递。
        param.setSavedInstanceState(Pair.create(application.packageName, application.classLoader))
        YLog.info(TAG, "Old generation ready for hot reload")
        return true
    }

    /**
     * API 102：新模块代码已载入，直接在当前宿主进程恢复。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        instance = this
        YLog.init(this)

        // 包括未被各组件显式记录的 Hook（例如 Application.onCreate 早期 Hook）。
        param.oldHookHandles.forEach { handle ->
            runCatching { handle.unhook() }
                .onFailure { YLog.error(TAG, "Failed to unhook old generation handle", it) }
        }

        val saved = param.savedInstanceState as? Pair<*, *>
        val packageName = saved?.first as? String
        val restoredClassLoader = saved?.second as? ClassLoader
        if (packageName == null || restoredClassLoader == null) {
            YLog.error(TAG, "Hot reload restored without host package/ClassLoader state")
            return
        }

        val application = currentApplication()
        if (application == null) {
            YLog.error(TAG, "Hot reload restored but current Application is unavailable")
            return
        }
        if (application.packageName != packageName) {
            YLog.error(
                TAG,
                "Hot reload package mismatch: saved=$packageName, current=" + application.packageName
            )
            return
        }

        activePackageName = packageName

        YLog.info(
            TAG,
            "onHotReloaded: package=$packageName, oldHooks=" + param.oldHookHandles.size
        )

        GeneralHooker.hookAfterHotReload(this, application, restoredClassLoader)
        if (packageName == PackageNames.SYSTEM_UI) {
            SystemUIHooker.hookAfterHotReload(this, application, restoredClassLoader)
        }
    }

    /**
     * 从当前 Android 进程取宿主 Application。
     * 通过反射调用 framework 隐藏 API，避免把 Application 本身放进热重载 saved state。
     */
    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication").apply {
            isAccessible = true
        }
        method.invoke(null) as? Application
    }.getOrElse { error ->
        YLog.error(TAG, "Unable to resolve current Application after hot reload", error)
        null
    }
}
