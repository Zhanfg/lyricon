/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.core.view.doOnAttach
import io.github.proify.android.extensions.deflate
import io.github.proify.android.extensions.json
import io.github.proify.android.extensions.safeEncode
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.common.util.ViewHierarchyParser
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.xposed.BuildConfig
import io.github.proify.lyricon.xposed.ModuleEntry
import io.github.proify.lyricon.xposed.hook.PackageHooker
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslator
import io.github.proify.lyricon.xposed.systemui.diagnostic.RuntimeDataProbe
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarDisableHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarViewResolver
import io.github.proify.lyricon.xposed.systemui.hook.ViewVisibilityTracker
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.lyric.NativeLyricInfoBridge
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewController
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import io.github.proify.lyricon.xposed.systemui.util.CrashDetector
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SystemUI Hook 入口对象
 * 负责状态栏视图注入、第三方逻辑初始化及跨进程通信绑定
 */
object SystemUIHooker : PackageHooker() {
    private const val TAG = "SystemUIHooker"

    private const val TEST_CRASH = false
    private var isSafeMode = false
    private var isAppCreated = false

    var subscriber: LyriconSubscriber? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var subscriberRegisterTask: Runnable? = null

    override fun onHook() {
        YLog.info(TAG, "onHook")

        if (!isMainProcess()) {
            YLog.info(TAG, "Not main process, do nothing")
            return
        }

        doOnAppCreated {
            if (isAppCreated) {
                YLog.info(TAG, "App already created, do nothing")
                return@doOnAppCreated
            }
            isAppCreated = true
            YLog.info(TAG, "App created")

            if (isHotReloadAttach) {
                // 热重载没有发生 SystemUI 进程崩溃，不应写入 CrashDetector 计数。
                isSafeMode = false
                YLog.info(TAG, "API 102 hot reload attach: skip crash accounting")
                initCrashDataChannel()
                onAppCreate()
            } else {
                onPreLoad()
            }
        }
    }

    /**
     * 应用创建前的准备工作，包含崩溃检测逻辑
     */
    private fun onPreLoad() {
        YLog.info(TAG, "onPreLoad")

        val context = appContext
        if (context == null) {
            YLog.info(TAG, "App context not available")
            return
        }

        CrashDetector.getInstance(context).apply {
            record()
            // 检测到多次连续崩溃时进入安全模式，停止后续注入
            if (isContinuousCrash()) {
                isSafeMode = true
                YLog.error(TAG, "检测到连续崩溃，已停止hook")
            }
            if (isSafeMode) reset()
        }

        initCrashDataChannel()
        if (!isSafeMode) {
            onAppCreate()
        } else {
            YLog.info(TAG, "Safe mode enabled, app create skipped")
        }
    }

    private fun onAppCreate() {
        YLog.info(TAG, "onAppCreate")
        val context = appContext
        if (context == null) {
            YLog.info(TAG, "App context not available")
            return
        }

        StatusBarViewResolver.subscribe {
            YLog.info(TAG, "New status bar view resolved ")
            addStatusBarView(it)
        }

        initialize()
    }

    /**
     * 在App onCreate完成时进行各类辅助工具和监控器的初始化
     */
    private fun initialize() {
        YLog.info(TAG, "onInit")
        val context = appContext ?: return

        ScreenStateMonitor.initialize(context)
        OplusCapsuleHooker.initialize(module, classLoader)
        NotificationCoverHelper.initialize()
        ViewVisibilityTracker.initialize(module, classLoader)
        initDataChannel()

        initLyriconService()

        StatusBarDisableHooker.inject(module, classLoader)
        StatusBarDisableHooker.addListener(object :
            StatusBarDisableHooker.OnStatusBarDisableListener {
            private var lastDisableStateChanged: Boolean? = null

            override fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean) {
                if (lastDisableStateChanged == shouldHide) return
                lastDisableStateChanged = shouldHide
                StatusBarViewManager.forEach { it.onDisableStateChanged(shouldHide) }
            }
        })

        StatusBarColorMonitor.initialize(module, classLoader)
        AiTranslator.init(context)
        SystemUIMediaUtils.init(context)
        NativeLyricInfoBridge.init()
        StatusBarViewResolver.init(module, context)
    }

    private fun initLyriconService() {
        val context = appContext ?: return

        val service = ModuleEntry.instance
        val defaultSp = service.getRemotePreferences("default")
        val coreServiceDisable = defaultSp.getBoolean("core_service_disable", false)

        if (!coreServiceDisable) {
            BridgeCentral.initialize(context)
            BridgeCentral.sendBootCompleted()
        } else {
            YLog.info(TAG, "已禁用内置中心服务")
        }

        val subscriber = LyriconFactory.createSubscriber(appContext!!)
        this.subscriber = subscriber

        subscriber.subscribeActivePlayer(LyricDataHub)

        subscriber.addConnectionListener(object : ConnectionListener {
            override fun onConnected(subscriber: LyriconSubscriber) {
                RuntimeDataProbe.markSubscriberConnected()
                YLog.info(TAG, "lyriconSubscriber onConnected")
            }

            override fun onReconnected(subscriber: LyriconSubscriber) {
                RuntimeDataProbe.markSubscriberConnected()
                YLog.info(TAG, "lyriconSubscriber onReconnected")
            }

            override fun onDisconnected(subscriber: LyriconSubscriber) {
                RuntimeDataProbe.markSubscriberDisconnected()
                YLog.info(TAG, "lyriconSubscriber onDisconnected")
            }

            override fun onConnectTimeout(subscriber: LyriconSubscriber) {
                RuntimeDataProbe.markSubscriberTimeout()
                YLog.info(TAG, "lyriconSubscriber onConnectTimeout")
            }

        })
        subscriberRegisterTask?.let(mainHandler::removeCallbacks)
        val registerTask = Runnable {
            subscriberRegisterTask = null
            if (this.subscriber === subscriber) {
                RuntimeDataProbe.markSubscriberConnecting()
                subscriber.register()
            }
        }
        subscriberRegisterTask = registerTask
        mainHandler.postDelayed(registerTask, 2000L)
    }

    private fun initDataChannel() {
        val context = appContext ?: return
        LyriconBridge.routing(context) {
            onCommand(AppBridgeConstants.REQUEST_HIGHLIGHT_VIEW) {
                val id = it.getString("id")
                YLog.info(TAG, "App requested view highlight id: ")

                StatusBarViewManager.forEachOnMainThread { it.highlightView(id) }
            }

            onQuery(AppBridgeConstants.REQUEST_VIEW_TREE) {
                YLog.info(TAG, "App requested view tree")

                val controller = StatusBarViewManager.controllers.firstOrNull() ?: return@onQuery

                val data =
                    json.safeEncode(ViewHierarchyParser.buildNodeTree(controller.statusBarView))
                        .toByteArray(Charsets.UTF_8)
                        .deflate()

                YLog.info(TAG, "View tree reply data: ")

                reply(Bundle().apply {
                    putByteArray("result", data)
                })
            }

            onCommand(AppBridgeConstants.REQUEST_CLEAR_TRANSLATION_DB) {
                AiTranslator.clearCache { LyricDataHub.reprocessCurrentSong() }
            }
        }
    }

    /**
     * 将自定义控制器绑定到状态栏视图
     */
    private fun addStatusBarView(view: ViewGroup) {
        view.doOnAttach {
            val target = view.rootView as? ViewGroup ?: return@doOnAttach
            val controller = StatusBarViewController(target, LyricPrefs.getLyricStyle())
            StatusBarViewManager.add(controller)

            val isFirst = StatusBarViewManager.controllers.size == 1
            if (isFirst) {
                if (TEST_CRASH) target.postDelayed({ error("test crash") }, 3000)
            }
        }
    }

    /**
     * API 102 热重载前释放旧模块代际持有的所有 SystemUI 长生命周期资源。
     *
     * UI 对象必须在主线程同步销毁；只有清理完整结束后才允许框架切换 classloader。
     */
    override fun onHotReloadCleanup() {
        YLog.info(TAG, "Preparing API 102 hot reload")

        // 先停止会继续产生 UI/Hook 回调的入口。
        StatusBarViewResolver.release()
        StatusBarDisableHooker.release()

        runOnMainThreadBlocking {
            LyricControlPopup.destroyForHotReload()
            StatusBarViewManager.destroyAllNow()
            LyricViewController.destroy()
        }

        // 再释放依赖于 Controller/LyricView 的监控器。
        StatusBarColorMonitor.release()
        OplusCapsuleHooker.release()
        ViewVisibilityTracker.release()
        LyricPrefs.release()

        // 先从 Subscriber 移除 LyricDataHub，阻止清理过程中继续收到远端回调；
        // 随后同步等待歌词流水线退出，再释放其依赖的 AI/媒体资源。
        subscriber?.let { current ->
            runCatching { current.unsubscribeActivePlayer(LyricDataHub) }
                .onFailure { YLog.error(TAG, "Failed to unsubscribe LyricDataHub", it) }
        }
        NativeLyricInfoBridge.release()
        LyricDataHub.release()

        // 媒体与 AI 后台任务可能持有旧模块对象，必须显式断开。
        NotificationCoverHelper.destroy()
        SystemUIMediaUtils.release()
        ScreenStateMonitor.release()
        AiTranslator.release()

        // 销毁本进程 Subscriber，再关闭内置 Central 的 Binder 连接。
        subscriber?.let { current ->
            runCatching { current.unregister() }
            runCatching { current.destroy() }
        }
        subscriber = null
        BridgeCentral.release()

        // 最后撤销跨进程广播路由，并取消尚未执行的延迟注册。
        LyriconBridge.release()
        subscriberRegisterTask?.let(mainHandler::removeCallbacks)
        subscriberRegisterTask = null
        mainHandler.removeCallbacksAndMessages(null)

        RuntimeDataProbe.reset()
        isAppCreated = false
        isSafeMode = false
        YLog.info(TAG, "API 102 hot reload cleanup complete")
    }

    /**
     * 在主线程同步执行清理，防止 onHotReloading 返回后旧 View 仍留在 SystemUI。
     */
    private fun runOnMainThreadBlocking(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out while cleaning SystemUI for hot reload")
        }
        failure?.let { throw it }
    }

    /**
     * 初始化崩溃相关的通信频道
     */
    private fun initCrashDataChannel() {
        val context = appContext ?: return
        LyriconBridge.routing(context) {
            onQuery(AppBridgeConstants.REQUEST_CHECK_SAFE_MODE) {
                reply(Bundle().apply {
                    putBoolean("result", isSafeMode)
                    putLong("runtime_version_code", BuildConfig.APP_VERSION_CODE)
                    putString("runtime_version_name", BuildConfig.APP_VERSION_NAME)
                })
            }
        }
    }
}
