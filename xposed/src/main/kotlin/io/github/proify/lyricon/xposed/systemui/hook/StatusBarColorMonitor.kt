/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor.register
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏文字颜色监控器(策略协调器)
 *
 * 只负责通用职责,具体的「感知与取色」由 [StatusColorStrategy] 实现:
 * - 监听器管理与颜色结果去重上报(`(color, luminance)` 输出);
 * - 状态栏根视图 / 时钟视图引用维护与统一绑定入口;
 * - 按用户偏好(`BasicStyle.statusColorStrategy`,基础样式偏好热更新,
 *   无需重启 SystemUI)动态切换策略:切换时撤销旧策略 hook、
 *   激活新策略并重放视图绑定。
 *
 * ### 注册新策略
 *
 * 见 [register]。内置:
 * - [PreciseClockColorStrategy] 精准模式(默认,按时钟类动态 hook);
 * - [CompatSetTextColorStrategy] 兼容模式(全局 setTextColor + 聚合)。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object StatusBarColorMonitor {

    private const val TAG = "StatusBarColorMonitor"

    /** 策略注册表 */
    private val registry = mutableListOf<StatusColorStrategy>()

    /** 已注册的颜色变化监听器 */
    private val listeners = CopyOnWriteArrayList<OnColorChangeListener>()

    /** 颜色亮度缓存，避免重复计算同一颜色的亮度值 */
    private val luminanceCache = HashMap<Int, Float>()

    /** 是否已初始化的标志，使用 @Volatile 保证多线程可见性 */
    @Volatile
    private var initialized = false

    /** 模块实例(供策略安装 hook 使用) */
    @Volatile
    private var module: XposedModule? = null

    /** 类加载器(供策略安装 hook 使用) */
    @Volatile
    private var classLoader: ClassLoader? = null

    /** 当前激活的策略 */
    @Volatile
    private var activeStrategy: StatusColorStrategy? = null

    /** 状态栏根视图的弱引用(切换策略后重放) */
    @Volatile
    private var statusBarRoot: WeakReference<ViewGroup>? = null

    /** 时钟视图的弱引用(切换策略后重放) */
    @Volatile
    private var clockView: WeakReference<View>? = null

    /** 最近一次上报的颜色指纹，避免重复通知 */
    @Volatile
    private var lastFingerprint: String? = null

    /** 策略注册表,内置两个默认策略 */
    init {
        register(PreciseClockColorStrategy())
        register(CompatSetTextColorStrategy())
    }

    /**
     * 注册策略(内置策略无需调用)
     *
     * 未来新增方案:实现 [StatusColorStrategy] 并在此注册,
     * 同一 [StatusColorStrategy.id] 只保留首次注册。
     *
     * @param strategy 策略实例
     */
    fun register(strategy: StatusColorStrategy) {
        synchronized(registry) {
            if (registry.none { it.id == strategy.id }) {
                registry.add(strategy)
            }
        }
    }

    /**
     * 初始化监控器
     *
     * 只保存模块引用并同步策略;只应执行一次。
     *
     * @param module Xposed 模块实例
     * @param classLoader 用于加载目标类的类加载器
     */
    fun initialize(module: XposedModule, classLoader: ClassLoader) {
        if (initialized) return
        initialized = true

        this.module = module
        this.classLoader = classLoader

        syncStrategy()

        YLog.info(TAG, "Initialized")
    }

    /**
     * API 102 热重载前停止策略并释放旧代际引用。
     */
    fun release() {
        runCatching { activeStrategy?.onDeactivate() }
        activeStrategy = null
        listeners.clear()
        luminanceCache.clear()
        statusBarRoot = null
        clockView = null
        lastFingerprint = null
        module = null
        classLoader = null
        initialized = false
        YLog.info(TAG, "Released")
    }

    /**
     * 绑定状态栏根视图
     *
     * @param root 状态栏根视图;传 null 清除绑定
     */
    fun bindStatusBar(root: ViewGroup?) {
        statusBarRoot = root?.let { WeakReference(it) }
        activeStrategy?.onBindStatusBar(root)
    }

    /**
     * 绑定时钟视图
     *
     * @param view 时钟视图,通常由 [ClockViewFinder.find] 得到;传 null 清除绑定
     */
    fun bindClockView(view: View?) {
        clockView = view?.let { WeakReference(it) }
        activeStrategy?.onBindClockView(view)
    }

    /**
     * 解绑指定时钟视图(仅在绑定对象一致时生效)
     *
     * @param view 待解绑的视图
     */
    fun unbindClockView(view: View?) {
        if (view != null && clockView?.get() === view) {
            clockView = null
            activeStrategy?.onBindClockView(null)
        }
    }

    /**
     * 注册颜色变化监听器
     *
     * @param listener 颜色变化监听器
     */
    fun addListener(listener: OnColorChangeListener) {
        if (listeners.addIfAbsent(listener)) {
            syncStrategy()
            refresh()
        }
    }

    /**
     * 移除颜色变化监听器
     *
     * @param listener 颜色变化监听器
     */
    fun removeListener(listener: OnColorChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 刷新:同步策略偏好并按当前策略上报一次
     *
     * 可挂在布局等事件上,策略切换与兜底观测都会经此生效。
     */
    fun refresh() {
        syncStrategy()
        activeStrategy?.onRefresh()
    }

    /**
     * 按用户偏好同步激活的策略
     *
     * 切换时先停用旧策略(撤销其全部 hook),再激活新策略并重放视图绑定。
     * 目标 ID 未注册(未来偏好残留)时回退到精准模式。
     */
    private fun syncStrategy() {
        val targetId = runCatching { LyricPrefs.baseStyle.statusColorStrategy }
            .getOrDefault(BasicStyle.Defaults.STATUS_COLOR_STRATEGY)

        val next = synchronized(registry) {
            registry.firstOrNull { it.id == targetId }
                ?: registry.firstOrNull { it.id == BasicStyle.STATUS_COLOR_STRATEGY_PRECISE }
        } ?: return

        if (next === activeStrategy) return

        activeStrategy?.onDeactivate()
        activeStrategy = next

        val module = this.module
        val classLoader = this.classLoader
        if (module != null && classLoader != null) {
            next.onActivate(module, classLoader, ::emit)
        }

        // 重放视图绑定,保证切换后立即具备取色上下文
        statusBarRoot?.get()?.let { next.onBindStatusBar(it) }
        clockView?.get()?.let { next.onBindClockView(it) }

        YLog.info(TAG, "Strategy switched: ${next.name}")
    }

    /**
     * 统一颜色上报入口(含指纹去重)
     *
     * @param color 实际颜色(黑/白或其插值)
     */
    private fun emit(color: Int) {
        if (listeners.isEmpty()) return

        val luminance = luminanceCache.getOrPut(color) {
            ColorUtils.calculateLuminance(color).toFloat()
        }

        val fingerprint = "$color:$luminance"
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        listeners.forEach { listener ->
            runCatching { listener.onColorChanged(color, luminance) }
                .onFailure { YLog.error(TAG, "Color listener callback failed", it) }
        }
    }
}
