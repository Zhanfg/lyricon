/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.hook

import android.app.Application
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.proify.lyricon.xposed.logger.YLog
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 目标包 Hook 生命周期基类。
 *
 * API 102 热重载不会重新触发普通的 PackageLoaded 初始化流程，因此这里不再长期持有
 * PackageLoadedParam，而是把运行所需的包名、进程名和 ClassLoader 提取成稳定状态。
 * 热重载后的新模块代际可以直接使用当前 Application 重新挂载。
 */
abstract class PackageHooker {
    companion object {
        private const val TAG = "PackageHooker"
    }

    protected lateinit var module: XposedModule

    private var runtimePackageName: String? = null
    private var runtimeProcessName: String? = null
    private var runtimeClassLoader: ClassLoader? = null
    private var runtimeApplicationClassName: String? = null

    val packageName: String
        get() = runtimePackageName ?: error("PackageHooker is not attached")

    val classLoader: ClassLoader
        get() = runtimeClassLoader ?: error("PackageHooker is not attached")

    @Volatile
    var appContext: Application? = null
        private set

    private val appOnCreateListeners = CopyOnWriteArraySet<(Application) -> Unit>()
    private val isAppCreateHooked = AtomicBoolean(false)
    private val isAttached = AtomicBoolean(false)

    fun isMainProcess(): Boolean = runtimeProcessName == packageName

    /**
     * 在 Application 创建时执行回调。
     * 热重载时 Application 已存在，因此会立即执行。
     */
    fun doOnAppCreated(callback: (Application) -> Unit) {
        val current = appContext
        if (current != null) {
            callback(current)
            return
        }

        appOnCreateListeners.add(callback)

        if (isAppCreateHooked.compareAndSet(false, true)) {
            hookApplicationOnCreate()
        }
    }

    /**
     * Hook Application.onCreate() 以获取 Application 实例。
     */
    private fun hookApplicationOnCreate() {
        val targetClassName = runtimeApplicationClassName ?: "android.app.Application"

        try {
            YLog.info(TAG, "Targeting Application class: $targetClassName")

            val targetClass = classLoader.loadClass(targetClassName)
            val onCreateMethod = targetClass.getDeclaredMethod("onCreate")

            module.hook(onCreateMethod)
                .intercept(XposedInterfaceHooker())
        } catch (e: Throwable) {
            YLog.error(
                TAG,
                "Failed to hook $targetClassName, falling back to global Application",
                e
            )
            fallbackToGlobalHook()
        }
    }

    /**
     * 兜底方案：Hook 通用 Application.onCreate()。
     */
    private fun fallbackToGlobalHook() {
        try {
            val onCreateMethod = Application::class.java.getDeclaredMethod("onCreate")

            module.hook(onCreateMethod).intercept { chain ->
                chain.proceed()
                val instance = chain.thisObject as? Application
                if (instance != null) {
                    handleApplicationInstance(instance)
                }
                null
            }
        } catch (t: Throwable) {
            YLog.error(TAG, "Critical failure: Global Hook failed", t)
        }
    }

    private inner class XposedInterfaceHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            chain.proceed()
            val instance = chain.thisObject as? Application
            if (instance != null) {
                handleApplicationInstance(instance)
            }
            return null
        }
    }

    private fun handleApplicationInstance(instance: Application) {
        if (appContext != null) return

        synchronized(this) {
            if (appContext == null) {
                appContext = instance
                runtimeProcessName = Application.getProcessName()

                YLog.info(TAG, "Application Context is ready: " + instance.javaClass.name)

                appOnCreateListeners.forEach {
                    runCatching { it(instance) }.onFailure { error ->
                        YLog.error(TAG, "Callback error", error)
                    }
                }
                appOnCreateListeners.clear()
            }
        }
    }

    /**
     * 普通进程加载路径。
     */
    fun hook(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!isAttached.compareAndSet(false, true)) {
            YLog.info(TAG, "Already attached")
            return
        }

        this.module = module
        runtimePackageName = param.packageName
        runtimeProcessName = param.applicationInfo.processName
        runtimeClassLoader = param.defaultClassLoader
        runtimeApplicationClassName = param.applicationInfo.className

        onHook()
    }

    /**
     * API 102 热重载后的重新挂载路径。
     *
     * Application 和其 ClassLoader 属于宿主进程而非旧模块 classloader，可安全跨模块代际复用。
     */
    fun hookAfterHotReload(module: XposedModule, application: Application) {
        if (!isAttached.compareAndSet(false, true)) {
            YLog.info(TAG, "Already attached after hot reload")
            return
        }

        this.module = module
        runtimePackageName = application.packageName
        runtimeProcessName = Application.getProcessName()
        runtimeClassLoader = application.classLoader
        runtimeApplicationClassName = application.javaClass.name
        appContext = application

        YLog.info(
            TAG,
            "Reattaching after hot reload: package=$runtimePackageName, process=$runtimeProcessName"
        )

        onHook()
    }

    /**
     * 热重载前释放由旧模块代际持有的长期对象。
     *
     * 返回当前宿主 Application，交给 ModuleEntry 保存到 API 102 savedInstanceState。
     */
    fun prepareHotReload(): Application? {
        val application = appContext
        onHotReloadCleanup()
        appOnCreateListeners.clear()
        return application
    }

    /**
     * 子类清理入口。这里必须只释放模块自己的监听器、View、协程等资源，
     * 不应终止 SystemUI 自身资源。
     */
    protected open fun onHotReloadCleanup() = Unit

    abstract fun onHook()
}
