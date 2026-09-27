/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.isLandScape
import io.github.proify.android.extensions.setColorAlpha
import io.github.proify.android.extensions.toBitmap
import io.github.proify.lyricon.app.bridge.AppBridge.LyricGesturePrefs
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.diagnostic.RuntimeDataProbe
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController.isPlaying
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import java.io.File

/**
 * 状态栏歌词视图控制器：负责歌词视图的注入、位置锚定及显隐逻辑
 */
@SuppressLint("DiscouragedApi")
class StatusBarViewController(
    val statusBarView: ViewGroup,
    var currentLyricStyle: LyricStyle
) : ScreenStateMonitor.ScreenStateListener {
    companion object {
        const val TAG = "StatusBarViewController"

        private val FALLBACK_CONTAINER_NAMES = arrayOf(
            "status_bar_left_side",
            "status_bar_contents",
            "left_side",
            "status_bar_left_container",
            "system_icon_area"
        )

        private const val SHOW_INJECTION_PROBE = false
        private const val PROBE_DURATION_MS = 60_000L
        private const val PROBE_REFRESH_MS = 500L
    }

    val context: Context = statusBarView.context.applicationContext
    val visibilityController: ViewVisibilityController = ViewVisibilityController(statusBarView)
    val lyricView: StatusBarLyric by lazy { createLyricView(currentLyricStyle) }

    // --- 手势控制状态 (随偏好热更新) ---
    private var gestureEnabled: Boolean = LyricGesturePrefs.DEFAULT_ENABLED
    private var swipeLeftAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_LEFT
    private var swipeRightAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
    private var tapAction: Int = LyricGesturePrefs.DEFAULT_TAP
    private var longPressAction: Int = LyricGesturePrefs.DEFAULT_LONG_PRESS

    private var lastAnchor = ""
    private var lastInsertionOrder = -1
    private var internalRemoveLyricViewFlag = false
    private var lastHighlightView: View? = null
    private var colorMonitorView: View? = null
    private var coverColorPaletteResult: ColorPaletteResult? = null
    private var systemStatusBarColor: SystemStatusBarColor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var injectionProbe: TextView? = null

    private val colorChangeListener = object : OnColorChangeListener {

        private var colorFingerprint: String? = null
        override fun onColorChanged(color: Int, darkIntensity: Float) {
            val colorFingerprint = color.toString() + darkIntensity
            if (colorFingerprint == this.colorFingerprint) return
            this.colorFingerprint = colorFingerprint

            updateStatusColor(SystemStatusBarColor(color, darkIntensity))
        }
    }

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyVisibilityRulesNow()
//        // 无副作用复核:布局事件也是状态栏颜色可能的变更时机(补救观察点)
//        StatusBarColorMonitor.refresh()
    }

    // --- 生命周期与初始化 ---
    fun onCreate() {
        statusBarView.addOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.addOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.addListener(this)
        lyricView.onPlayingChanged = { _ -> }

        // 手势控制:读取偏好并绑定回调,手势动作可配置
        refreshGestureConfig()
        lyricView.gestureListener = { gesture -> onLyricGesture(gesture) }

        StatusBarColorMonitor.bindStatusBar(statusBarView)
        colorMonitorView = getClockView()
        StatusBarColorMonitor.bindClockView(colorMonitorView)
        StatusBarColorMonitor.addListener(colorChangeListener)

        statusBarView.doOnAttach { checkLyricViewExists() }
        YLog.info(tag = TAG, "Lyric view created for $statusBarView")
    }

    fun onDestroy() {
        statusBarView.removeOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.removeOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.removeListener(this)
        lyricView.onPlayingChanged = null
        lyricView.gestureListener = null
        lyricView.setOnClickListener(null)
        LyricControlPopup.dismissIfOwnedBy(lyricView)
        lyricView.release()
        mainHandler.removeCallbacksAndMessages(null)
        injectionProbe?.let { probe ->
            (probe.parent as? ViewGroup)?.removeView(probe)
        }
        injectionProbe = null
        visibilityController.restoreAll()
        lastHighlightView?.background = null
        lastHighlightView = null
        (lyricView.parent as? ViewGroup)?.removeView(lyricView)
        StatusBarColorMonitor.removeListener(colorChangeListener)
        colorMonitorView?.let { StatusBarColorMonitor.unbindClockView(it) }
        colorMonitorView = null
        YLog.info(tag = TAG, "Lyric view destroyed for $statusBarView")
    }

    // --- 核心业务逻辑 ---

    /**
     * 更新状态栏颜色，内部决定最终颜色
     */
    internal fun updateStatusColor(systemStatusBarColor: SystemStatusBarColor) {
        this.systemStatusBarColor = systemStatusBarColor

        val textStyle = currentLyricStyle.packageStyle.text
        lyricView.apply {
            currentStatusColor.apply {
                this.darkIntensity = systemStatusBarColor.darkIntensity

                val coverColorPaletteResult = coverColorPaletteResult
                when {
                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor
                            && textStyle.enableExtractCoverTextGradient -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val gradient = themeColors.swatches

                        this.color = gradient
                        this.translucentColor = gradient.map {
                            it.setColorAlpha(0.75f)
                        }.toIntArray()
                    }

                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val primary = themeColors.primary

                        this.color = intArrayOf(primary)
                        this.translucentColor = intArrayOf(primary.setColorAlpha(0.75f))
                    }

                    else -> {
                        this.color = intArrayOf(systemStatusBarColor.color)
                        this.translucentColor =
                            intArrayOf(systemStatusBarColor.color.setColorAlpha(0.5f))
                    }
                }
            }
            setStatusBarColor(currentStatusColor)
        }
    }

    /**
     * 更新歌词样式及位置，若锚点或顺序变化则重新注入视图
     */
    fun updateLyricStyle(lyricStyle: LyricStyle) {
        this.currentLyricStyle = lyricStyle
        val basicStyle = lyricStyle.basicStyle

        val needUpdateLocation = lastAnchor != basicStyle.anchor
                || lastInsertionOrder != basicStyle.insertionOrder
                || !lyricView.isAttachedToWindow

        if (needUpdateLocation) {
            YLog.info(
                TAG,
                "Lyric location changed: ${basicStyle.anchor}, order ${basicStyle.insertionOrder}"
            )
            updateLocation(basicStyle)
        }
        lyricView.updateStyle(lyricStyle)
        refreshGestureConfig()

        systemStatusBarColor?.let { updateStatusColor(it) }
    }

    fun updateCoverThemeColors(coverFile: File?) {
        coverColorPaletteResult = null
        try {
            val bitmap = coverFile?.toBitmap() ?: return
            ColorExtractor.extractAsync(
                bitmap = bitmap,
                cacheKey = {
                    coverFile.name
                }) {
                coverColorPaletteResult = it
                systemStatusBarColor?.let { updateStatusColor(it) }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            YLog.error(TAG, "Failed to extract cover theme colors", e)
        }
    }

    /**
     * 处理视图注入逻辑：根据 BasicStyle 寻找锚点并插入歌词视图
     */
    private fun updateLocation(baseStyle: BasicStyle) {
        val anchor = resolveAnchorView(baseStyle.anchor)

        if (anchor != null) {
            injectRelativeToAnchor(anchor, baseStyle)
            return
        }

        val container = findFallbackContainer()
        if (container != null) {
            injectIntoContainer(container, baseStyle)
            return
        }

        YLog.error(
            TAG,
            "No lyric anchor/container found. requested=" + baseStyle.anchor +
                    ", root=" + statusBarView.javaClass.name
        )
    }

    private fun resolveAnchorView(anchorName: String): View? {
        val packages = linkedSetOf(
            statusBarView.context.packageName,
            context.packageName,
            "com.android.systemui"
        )

        packages.forEach { pkg ->
            val id = runCatching {
                statusBarView.resources.getIdentifier(anchorName, "id", pkg)
            }.getOrDefault(0)
            if (id != 0) {
                statusBarView.findViewById<View>(id)?.let { view ->
                    YLog.info(
                        TAG,
                        "Anchor resolved by id: name=" + anchorName +
                                " pkg=" + pkg + " class=" + view.javaClass.name
                    )
                    return view
                }
            }
        }

        ClockViewFinder.find(statusBarView)?.let { clock ->
            YLog.warning(
                TAG,
                "Configured anchor '" + anchorName +
                        "' unavailable; using clock fallback: " + clock.javaClass.name
            )
            return clock
        }

        YLog.warning(TAG, "Anchor and compatible clock view not found: " + anchorName)
        return null
    }

    private fun injectRelativeToAnchor(anchorView: View, baseStyle: BasicStyle) {
        val anchorParent = anchorView.parent as? ViewGroup ?: run {
            YLog.error(TAG, "Lyric anchor parent not found: " + anchorView.javaClass.name)
            return
        }

        internalRemoveLyricViewFlag = true
        try {
            (lyricView.parent as? ViewGroup)?.removeView(lyricView)

            val anchorIndex = anchorParent.indexOfChild(anchorView)
            if (anchorIndex < 0) {
                YLog.error(TAG, "Resolved anchor is not a child of its parent")
                return
            }

            val lp = lyricView.layoutParams ?: createLyricLayoutParams(baseStyle)
            val targetIndex =
                if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) anchorIndex + 1
                else anchorIndex

            anchorParent.addView(
                lyricView,
                targetIndex.coerceIn(0, anchorParent.childCount),
                lp
            )
            finishInjection(
                baseStyle = baseStyle,
                mode = "anchor",
                target = anchorView,
                parent = anchorParent
            )
        } finally {
            internalRemoveLyricViewFlag = false
        }
    }

    private fun injectIntoContainer(container: ViewGroup, baseStyle: BasicStyle) {
        internalRemoveLyricViewFlag = true
        try {
            (lyricView.parent as? ViewGroup)?.removeView(lyricView)
            val lp = lyricView.layoutParams ?: createLyricLayoutParams(baseStyle)
            container.addView(lyricView, container.childCount, lp)
            finishInjection(
                baseStyle = baseStyle,
                mode = "container",
                target = container,
                parent = container
            )
        } finally {
            internalRemoveLyricViewFlag = false
        }
    }

    private fun createLyricLayoutParams(baseStyle: BasicStyle): ViewGroup.LayoutParams {
        val width = baseStyle.getAutoWidth(
            context.isLandScape(),
            isOplusCapsuleShowing = OplusCapsuleHooker.isShowing
        ).dp
        return ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun finishInjection(
        baseStyle: BasicStyle,
        mode: String,
        target: View,
        parent: ViewGroup
    ) {
        lyricView.updateVisibility()
        lastAnchor = baseStyle.anchor
        lastInsertionOrder = baseStyle.insertionOrder

        YLog.info(
            TAG,
            "Lyric injected: mode=" + mode +
                    " requested=" + baseStyle.anchor +
                    " target=" + target.javaClass.name +
                    " parent=" + parent.javaClass.name +
                    " attached=" + lyricView.isAttachedToWindow
        )

        if (SHOW_INJECTION_PROBE) {
            showInjectionProbe(parent)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun findFallbackContainer(): ViewGroup? {
        val packages = linkedSetOf(
            statusBarView.context.packageName,
            context.packageName,
            "com.android.systemui"
        )

        FALLBACK_CONTAINER_NAMES.forEach { name ->
            packages.forEach { pkg ->
                val id = runCatching {
                    statusBarView.resources.getIdentifier(name, "id", pkg)
                }.getOrDefault(0)
                if (id != 0) {
                    (statusBarView.findViewById<View>(id) as? ViewGroup)?.let { container ->
                        YLog.warning(
                            TAG,
                            "Using fallback status bar container: name=" + name +
                                    " pkg=" + pkg + " class=" + container.javaClass.name
                        )
                        return container
                    }
                }
            }
        }

        return findContainerByResourceName(statusBarView)
    }

    private fun findContainerByResourceName(root: ViewGroup): ViewGroup? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup) {
                val entry = runCatching {
                    if (child.id == View.NO_ID) null
                    else child.resources.getResourceEntryName(child.id)
                }.getOrNull().orEmpty()

                if (
                    entry.contains("status_bar", ignoreCase = true) &&
                    (
                        entry.contains("left", ignoreCase = true) ||
                        entry.contains("content", ignoreCase = true)
                    )
                ) {
                    YLog.warning(
                        TAG,
                        "Using discovered status bar container: " + entry +
                                " / " + child.javaClass.name
                    )
                    return child
                }

                findContainerByResourceName(child)?.let { return it }
            }
        }
        return null
    }

    private fun showInjectionProbe(parent: ViewGroup) {
        val old = injectionProbe
        if (old?.parent != null) {
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val probe = TextView(parent.context).apply {
            textSize = 8f
            setTextColor(Color.WHITE)
            setPadding(3.dp, 0, 3.dp, 0)
            isSingleLine = true
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        injectionProbe = probe
        runCatching {
            parent.addView(
                probe,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            val startedAt = android.os.SystemClock.uptimeMillis()
            fun refresh() {
                if (injectionProbe !== probe) return

                val snapshot = RuntimeDataProbe.snapshot()
                probe.text = snapshot.compactLabel()
                probe.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    val color = when {
                        snapshot.lyricKind != null -> 0xCC2E7D32.toInt()
                        snapshot.subscriberState == RuntimeDataProbe.SubscriberState.TIMEOUT ||
                                snapshot.subscriberState == RuntimeDataProbe.SubscriberState.DISCONNECTED ->
                            0xCCC62828.toInt()
                        snapshot.providerCount == 0 -> 0xCCEF6C00.toInt()
                        else -> 0xCC455A64.toInt()
                    }
                    setColor(color)
                    cornerRadius = 6.dp.toFloat()
                }

                val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                if (elapsed < PROBE_DURATION_MS) {
                    mainHandler.postDelayed({ refresh() }, PROBE_REFRESH_MS)
                } else {
                    (probe.parent as? ViewGroup)?.removeView(probe)
                    if (injectionProbe === probe) injectionProbe = null
                }
            }
            refresh()
        }.onFailure { error ->
            YLog.error(TAG, "Failed to show data-path probe", error)
            injectionProbe = null
        }
    }

    fun checkLyricViewExists() {
        if (lyricView.isAttachedToWindow) return
        lastAnchor = ""
        lastInsertionOrder = -1
        updateLyricStyle(currentLyricStyle)
    }

    // --- 辅助方法 ---

    private fun getClockView(): View? = ClockViewFinder.find(statusBarView)

    private var wasPlayingBeforeVisibilityUpdate: Boolean = false

    fun computeShouldApplyPlayingRules(): Boolean {
        return isPlaying && when {
            lyricView.isDisabledVisible -> !lyricView.isHideOnLockScreen()
            lyricView.isVisible -> true
            else -> false
        }
    }

    private fun applyVisibilityRulesNow() {
        val isPlaying = computeShouldApplyPlayingRules()
        fun apply() {
            visibilityController.applyVisibilityRules(
                rules = currentLyricStyle.basicStyle.visibilityRules,
                isPlaying = isPlaying
            )
        }

        if (!isPlaying) {
            // 仅在之前是播放状态时才更新（避免重复更新非播放状态的隐藏逻辑）
            if (wasPlayingBeforeVisibilityUpdate) {
                apply()
                wasPlayingBeforeVisibilityUpdate = false
            }
        } else {
            apply()
            wasPlayingBeforeVisibilityUpdate = true
        }
    }

    private fun createLyricView(style: LyricStyle) =
        StatusBarLyric(context, style, getClockView() as? TextView)

    // --- 手势控制 ---

    /**
     * 从偏好刷新手势配置,并同步视图的手势开关与点击行为。
     *
     * 手势关闭时保留旧版"单击打开控制面板"的行为(通过点击监听器委托)。
     */
    private fun refreshGestureConfig() {
        gestureEnabled = LyricPrefs.gestureEnabled
        swipeLeftAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_LEFT,
            LyricGesturePrefs.DEFAULT_SWIPE_LEFT
        )
        swipeRightAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_RIGHT,
            LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
        )
        tapAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_TAP,
            LyricGesturePrefs.DEFAULT_TAP
        )
        longPressAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_LONG_PRESS,
            LyricGesturePrefs.DEFAULT_LONG_PRESS
        )

        lyricView.gestureEnabled = gestureEnabled
        lyricView.hapticEnabled = LyricPrefs.gestureHapticEnabled
        if (gestureEnabled) {
            lyricView.setOnClickListener(null)
        } else {
            lyricView.setOnClickListener { v ->
                LyricControlPopup.show(v)
            }
        }
        // setOnClickListener(null) 会关闭 clickable,这里恢复以保持手势模式下的点击语义(无障碍)
        lyricView.isClickable = true
    }

    /**
     * 手势回调入口:根据当前配置将手势映射为动作并执行
     */
    private fun onLyricGesture(gesture: StatusBarLyric.GestureType) {
        if (!gestureEnabled) return

        val action = when (gesture) {
            StatusBarLyric.GestureType.SWIPE_LEFT -> swipeLeftAction
            StatusBarLyric.GestureType.SWIPE_RIGHT -> swipeRightAction
            StatusBarLyric.GestureType.TAP -> tapAction
            StatusBarLyric.GestureType.LONG_PRESS -> longPressAction
        }

        when (action) {
            LyricGesturePrefs.ACTION_NONE -> Unit
            LyricGesturePrefs.ACTION_TOGGLE_PLAY -> PlaybackControl.togglePlay()
            LyricGesturePrefs.ACTION_PREVIOUS -> PlaybackControl.previous()
            LyricGesturePrefs.ACTION_NEXT -> PlaybackControl.next()
            LyricGesturePrefs.ACTION_OPEN_CONTROL -> LyricControlPopup.show(lyricView)
            else -> YLog.warning(TAG, "Unknown gesture action: $action")
        }
    }

    fun highlightView(idName: String?) {
        YLog.info(TAG, "Highlighting view id:$idName")

        lastHighlightView?.background = null
        if (idName.isNullOrBlank()) return

        val id = ResourceMapper.getIdByName(context, idName)
        statusBarView.findViewById<View>(id)?.let { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#FF3582FF".toColorInt())
                cornerRadius = 20.dp.toFloat()
            }
            lastHighlightView = view
        } ?: YLog.error(TAG, "Highlight target $idName not found")
    }

    private val lyricAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            YLog.info(TAG, "LyricView attached")
        }

        override fun onViewDetachedFromWindow(v: View) {
            YLog.info(TAG, "LyricView detached")
            if (!internalRemoveLyricViewFlag) {
                checkLyricViewExists()
            } else {
                YLog.info(TAG, "LyricView detached by internal flag")
            }
        }
    }

    private val statusBarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {}
    }

    override fun onScreenOn() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    override fun onScreenOff() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = true
    }

    override fun onScreenUnlocked() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    fun onDisableStateChanged(shouldHide: Boolean) {
        lyricView.isDisabledVisible = shouldHide
    }

    override fun equals(other: Any?): Boolean =
        (this === other) ||
                (other is StatusBarViewController && statusBarView == other.statusBarView)

    override fun hashCode(): Int = 31 * 17 + statusBarView.hashCode()

    data class SystemStatusBarColor(val color: Int, val darkIntensity: Float)
}