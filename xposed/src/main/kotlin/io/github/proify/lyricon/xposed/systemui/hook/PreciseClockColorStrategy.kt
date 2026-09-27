/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 精准策略(默认):按时钟类动态 hook
 *
 * 从时钟视图实例动态获取实际类名(`view.javaClass` —— MIUI/HyperOS `MiuiTextClock`、
 * ColorOS `OplusTextClock`、AOSP `Clock` 等),只 hook 该类**自身声明**的
 * 颜色相关方法(`setColor`/`setTextColor`/`setTint`/`onDarkChanged`/
 * `onColorsChanged` 等):只有时钟实例会经过拦截,不触碰其他 TextView;
 * proceed 后读取该实例的 `currentTextColor` 即真实渲染色。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
class PreciseClockColorStrategy : StatusColorStrategy {

    companion object {
        private const val TAG = "PreciseClockStrategy"

        /** 颜色相关方法名关键词(按类声明方法名匹配) */
        private val COLOR_KEYWORDS = arrayOf("color", "tint", "dark")

        /** 框架 TextView 本体 — 若时钟竟是纯 TextView,其声明方法为全局拦截,跳过 */
        private const val FRAMEWORK_TEXT_VIEW = "android.widget.TextView"
    }

    override val id: Int = BasicStyle.STATUS_COLOR_STRATEGY_PRECISE
    override val name: String = "Precise"

    private var module: XposedModule? = null
    private var emit: ((Int) -> Unit)? = null

    /** 时钟视图的弱引用(用于初始/复核读取) */
    private var clockView: WeakReference<View>? = null

    /** 已按类 hook 过的类名集合(防止重复 hook) */
    private val hookedClasses = HashSet<String>()

    /** 当前策略安装的 Hook 句柄，切换策略/热重载时必须释放。 */
    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()

    override fun onActivate(
        module: XposedModule,
        classLoader: ClassLoader,
        emit: (Int) -> Unit
    ) {
        this.module = module
        this.emit = emit

        // 若视图早已绑定(策略切换场景),补装该类 hook
        clockView?.get()?.let { hookClockClass(it.javaClass) }
    }

    override fun onDeactivate() {
        hookHandles.forEach { handle -> runCatching { handle.unhook() } }
        hookHandles.clear()
        synchronized(hookedClasses) { hookedClasses.clear() }
        clockView = null
        module = null
        emit = null
        YLog.info(TAG, "Hooks removed")
    }

    override fun onBindStatusBar(root: ViewGroup?) = Unit

    override fun onBindClockView(view: View?) {
        clockView = view?.let { WeakReference(it) }
        if (view != null) {
            hookClockClass(view.javaClass)
        }
        reportCurrentColor()
    }

    override fun onRefresh() {
        reportCurrentColor()
    }

    /**
     * 按视图实际类动态安装精确 hook
     *
     * @param clazz 时钟视图的实际类
     */
    private fun hookClockClass(clazz: Class<*>) {
        val className = clazz.name

        if (className == FRAMEWORK_TEXT_VIEW) {
            YLog.warning(TAG, "Clock class is framework TextView, skip class hook")
            return
        }

        synchronized(hookedClasses) {
            if (!hookedClasses.add(className)) return
        }

        val module = this.module ?: return
        val emit = this.emit ?: return

        val methods = clazz.declaredMethods.filter { method ->
            !method.isBridge &&
                    !method.isSynthetic &&
                    !method.name.startsWith("get") &&
                    COLOR_KEYWORDS.any { keyword ->
                        method.name.contains(keyword, ignoreCase = true)
                    }
        }

        if (methods.isEmpty()) {
            YLog.warning(TAG, "No color-related methods declared on $className")
            return
        }

        methods.forEach { method ->
            try {
                @Suppress("ObjectLiteralToLambda")
                val handle = module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        chain.proceed()
                        try {
                            // 方法声明在时钟类,thisObject 为时钟实例;
                            // proceed 后读取即最新真实文字颜色
                            val tv = chain.thisObject as? TextView ?: return null
                            val color = tv.currentTextColor
                            if (color != 0) {
                                emit(color)
                            }
                        } catch (t: Throwable) {
                            YLog.error(TAG, "$className#${method.name} callback failed", t)
                        }
                        return null
                    }
                })
                hookHandles.add(handle)
            } catch (t: Throwable) {
                YLog.warning(TAG, "Hook $className#${method.name} failed")
            }
        }

        YLog.info(
            TAG,
            "Hooked ${methods.size} color methods on $className" +
                    " (${methods.joinToString { it.name }})"
        )
    }

    /**
     * 读取绑定时钟视图的当前文字颜色并上报
     */
    private fun reportCurrentColor() {
        val emit = this.emit ?: return

        val color = (clockView?.get() as? TextView)
            ?.currentTextColor
            ?.takeIf { it != 0 }
            ?: return

        emit(color)
    }
}
