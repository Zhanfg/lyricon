/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏视图解析工具类 (StatusBarViewResolver)
 *
 * 原实现仅等待 `status_bar` layout 之后再次经过
 * `LayoutInflater.inflate(Int, ViewGroup, Boolean)`。部分 Android 16 / ColorOS 16
 * SystemUI 会在 Hook 初始化前完成状态栏构建，或通过厂商窗口路径创建状态栏，
 * 从而导致模块已激活但锚点为空、歌词无法注入。
 *
 * 当前解析链路：
 * 1. 保留原有 status_bar layout inflate 捕获。
 * 2. Hook WindowManagerImpl.addView，捕获之后新建/重建的状态栏窗口。
 * 3. 初始化后扫描 WindowManagerGlobal 已存在窗口，补偿 Hook 初始化前已创建的状态栏。
 * 4. 对已通知的根视图做弱引用去重，避免重复创建控制器。
 */
object StatusBarViewResolver {

    private const val TAG = "StatusBarViewResolver"
    private const val MAX_SCAN_NODES = 512
    private const val PHONE_STATUS_BAR_VIEW =
        "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /**
     * 状态栏视图获取成功的回调定义
     */
    typealias OnViewResolvedListener = (statusBarRoot: ViewGroup) -> Unit

    private val registry = mutableListOf<OnViewResolvedListener>()
    private val resolvedRoots = mutableListOf<WeakReference<ViewGroup>>()
    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingResolvedView: WeakReference<ViewGroup>? = null

    private var isInitialized = false
    private var isPackageReadyHookInstalled = false

    /**
     * 订阅状态栏视图。
     * 如果 Hook 触发时视图加载完成，将通知所有订阅者。
     * @param listener 接收 ViewGroup 的回调函数
     */
    fun subscribe(listener: OnViewResolvedListener) {
        if (!registry.contains(listener)) {
            registry.add(listener)
        }

        val pending = pendingResolvedView?.get() ?: return
        pendingResolvedView = null
        mainHandler.post {
            notifyResolved(pending, "package_ready_pending")
        }
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(listener: OnViewResolvedListener) {
        registry.remove(listener)
    }

    /**
     * libxposed API 102 / ColorOS 16 主解析入口。
     *
     * 在 onPackageReady() 阶段安装 PhoneStatusBarView.onFinishInflate() Hook。
     * OnePlus / ColorOS 16 的已验证实现使用的就是这一生命周期入口；这样可以在
     * 状态栏真正构建完成的瞬间直接拿到 View，而不依赖 layout 名称或窗口扫描。
     */
    fun installPackageReadyHook(
        module: XposedModule,
        classLoader: ClassLoader
    ) {
        if (isPackageReadyHookInstalled) return

        try {
            val phoneStatusBarClass = Class.forName(
                PHONE_STATUS_BAR_VIEW,
                false,
                classLoader
            )
            val method = phoneStatusBarClass.getDeclaredMethod("onFinishInflate")

            val handle = module.hook(method)
                .setId("lyricon:statusbar:phone-finish-inflate")
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val view = chain.thisObject as? ViewGroup
                        if (view != null) {
                            mainHandler.post {
                                captureResolved(
                                    view,
                                    "phone_status_bar_onFinishInflate"
                                )
                            }
                        } else {
                            YLog.warning(
                                TAG,
                                "PhoneStatusBarView.onFinishInflate thisObject is not ViewGroup"
                            )
                        }
                        return result
                    }
                })

            hookHandles.add(handle)
            isPackageReadyHookInstalled = true
            YLog.info(
                TAG,
                "API102 PhoneStatusBarView.onFinishInflate hook installed: " +
                        phoneStatusBarClass.name
            )
        } catch (t: Throwable) {
            YLog.error(
                TAG,
                "Unable to install API102 PhoneStatusBarView.onFinishInflate hook",
                t
            )
        }
    }

    /**
     * 启动拦截任务
     * @param module XposedModule 实例
     * @param context 系统上下文（建议使用 SystemUI 的 Context）
     * @param classLoader 对应的 ClassLoader
     */
    @SuppressLint("DiscouragedApi", "PrivateApi")
    fun init(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader = context.classLoader
    ) {
        if (isInitialized) return
        isInitialized = true

        val targetLayoutId = context.resources.getIdentifier(
            "status_bar",
            "layout",
            context.packageName
        )

        if (targetLayoutId == 0) {
            YLog.warning(TAG, "status_bar layout id not found; enabling fallback resolver paths")
        } else {
            hookLayoutInflater(module, classLoader, targetLayoutId)
        }

        hookWindowManagerAddView(module, classLoader)

        // SystemUI 在部分 ROM 上会先创建状态栏，再执行模块初始化。
        // 多次短延迟扫描可覆盖 Application 创建时序和插件初始化时序差异。
        listOf(0L, 250L, 1000L, 3000L).forEach { delayMs ->
            mainHandler.postDelayed(
                { scanExistingWindows(classLoader) },
                delayMs
            )
        }
    }

    /**
     * 释放当前模块代际注册的 Hook、延迟扫描和订阅者。
     * API 102 热重载前调用，确保旧 classloader 不再被 SystemUI 长期持有。
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        hookHandles.forEach { handle -> runCatching { handle.unhook() } }
        hookHandles.clear()
        registry.clear()
        resolvedRoots.clear()
        pendingResolvedView = null
        isInitialized = false
        isPackageReadyHookInstalled = false
        YLog.info(TAG, "Released")
    }

    private fun hookLayoutInflater(
        module: XposedModule,
        classLoader: ClassLoader,
        targetLayoutId: Int
    ) {
        try {
            val inflaterClass = classLoader.loadClass("android.view.LayoutInflater")
            val inflateMethod = inflaterClass.getDeclaredMethod(
                "inflate",
                Int::class.javaPrimitiveType,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType
            )

            @Suppress("ObjectLiteralToLambda")
            val handle = module.hook(inflateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val currentLayoutId = chain.args[0] as? Int

                    if (currentLayoutId == targetLayoutId) {
                        (result as? View)?.let { candidate ->
                            resolveCandidate(candidate, "layout_inflate")
                        }
                    }
                    return result
                }
            })
            hookHandles.add(handle)

            YLog.info(TAG, "LayoutInflater status_bar hook installed")
        } catch (t: Throwable) {
            YLog.error(TAG, "Error during LayoutInflater inflation hook", t)
        }
    }

    private fun hookWindowManagerAddView(
        module: XposedModule,
        classLoader: ClassLoader
    ) {
        try {
            val windowManagerClass = classLoader.loadClass("android.view.WindowManagerImpl")
            val addViewMethod = windowManagerClass.getDeclaredMethod(
                "addView",
                View::class.java,
                ViewGroup.LayoutParams::class.java
            )

            @Suppress("ObjectLiteralToLambda")
            val handle = module.hook(addViewMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val addedView = chain.args.firstOrNull() as? View

                    addedView?.let { candidate ->
                        mainHandler.post {
                            resolveCandidate(candidate, "window_add")
                        }
                    }

                    return result
                }
            })
            hookHandles.add(handle)

            YLog.info(TAG, "WindowManager addView fallback hook installed")
        } catch (t: Throwable) {
            // 某些 ROM 可能替换/封装 WindowManagerImpl；已有窗口扫描仍可继续工作。
            YLog.error(TAG, "WindowManager addView fallback hook unavailable", t)
        }
    }

    @SuppressLint("PrivateApi")
    private fun scanExistingWindows(classLoader: ClassLoader) {
        try {
            val globalClass = classLoader.loadClass("android.view.WindowManagerGlobal")
            val getInstance = globalClass.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }
            val instance = getInstance.invoke(null) ?: return

            val viewsField = globalClass.getDeclaredField("mViews").apply {
                isAccessible = true
            }

            val views = viewsField.get(instance) as? Iterable<*> ?: return
            var scanned = 0

            views.forEach { item ->
                val view = item as? View ?: return@forEach
                scanned++
                resolveCandidate(view, "existing_window")
            }

            YLog.debug(TAG, "Existing WindowManager roots scanned: $scanned")
        } catch (t: Throwable) {
            YLog.error(TAG, "Unable to scan existing WindowManager roots", t)
        }
    }

    private fun resolveCandidate(candidate: View, source: String) {
        val root = (candidate.rootView ?: candidate) as? ViewGroup ?: return
        val statusBar = findStatusBarView(root) ?: return
        captureResolved(statusBar, source)
    }

    private fun captureResolved(view: ViewGroup, source: String) {
        if (registry.isEmpty()) {
            pendingResolvedView = WeakReference(view)
            YLog.info(
                TAG,
                "Status bar captured before subscriber via " + source + ": " +
                        view.javaClass.name
            )
            return
        }
        notifyResolved(view, source)
    }

    /**
     * 在一个 SystemUI window root 内寻找状态栏节点。
     *
     * 优先使用稳定类名和资源名，避免绑定到 ColorOS 私有实现的完整包名。
     */
    private fun findStatusBarView(root: ViewGroup): ViewGroup? {
        val queue = ArrayDeque<View>()
        queue.add(root)

        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val view = queue.removeFirst()
            scanned++

            if (view is ViewGroup && looksLikeStatusBar(view)) {
                return view
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue.addLast(view.getChildAt(index))
                }
            }
        }

        return null
    }

    private fun looksLikeStatusBar(view: ViewGroup): Boolean {
        val className = view.javaClass.name
        val simpleName = view.javaClass.simpleName

        if (
            simpleName == "PhoneStatusBarView" ||
            simpleName == "StatusBarWindowView" ||
            className.endsWith(".PhoneStatusBarView") ||
            className.endsWith(".StatusBarWindowView")
        ) {
            return true
        }

        val id = view.id
        if (id == View.NO_ID) return false

        return try {
            when (view.resources.getResourceEntryName(id)) {
                "status_bar",
                "status_bar_contents",
                "status_bar_view" -> true

                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyResolved(view: ViewGroup, source: String) {
        val iterator = resolvedRoots.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next().get()
            if (existing == null) {
                iterator.remove()
            } else if (existing === view) {
                return
            }
        }

        resolvedRoots.add(WeakReference(view))
        YLog.info(
            TAG,
            "Status bar resolved via " + source + ": " + view.javaClass.name
        )

        registry.toList().forEach { listener ->
            runCatching { listener.invoke(view) }
                .onFailure { error ->
                    YLog.error(TAG, "Status bar listener failed", error)
                }
        }
    }
}
