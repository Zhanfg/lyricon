/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.central.internal.CentralConstants
import io.github.proify.lyricon.central.internal.util.ScreenStateMonitor

/**
 * 中央桥接管理对象。
 *
 * 负责初始化全局 Context，并管理核心广播通信：
 * - 注册 [CentralReceiver] 接收提供者注册请求；
 * - 向系统或其他组件发送启动完成广播。
 */
@SuppressLint("StaticFieldLeak")
object BridgeCentral {

    /** 全局应用 Context，用于广播和注册接收器 */
    @Volatile
    private var context: Context? = null

    /** 用于接收中央控制广播的接收器实例 */
    private val receiver = CentralReceiver

    /**
     * 初始化中央桥接。
     *
     * 仅在第一次调用时生效，后续调用将被忽略。
     *
     * @param appContext 应用级 Context
     */
    @Synchronized
    fun initialize(appContext: Context) {
        if (context != null) return
        val app = appContext.applicationContext
        context = app
        ScreenStateMonitor.initialize(app)
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter().apply {
                addAction(CentralConstants.ACTION_REGISTER_PROVIDER)
                addAction(CentralConstants.ACTION_REGISTER_SUBSCRIBER)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    data class Diagnostics(
        val initialized: Boolean,
        val providerCount: Int,
        val subscriberCount: Int,
        val providerRegistrationAttempts: Int,
        val providerRegistrationStage: String,
        val providerRegistrationErrorCode: String?,
        val providerRegistrationErrorDetail: String?
    )

    /** 只读运行时诊断快照，不修改 Central 状态。 */
    fun diagnostics(): Diagnostics {
        val registration = CentralRuntime.registrationDiagnostics()
        return Diagnostics(
            initialized = context != null,
            providerCount = CentralRuntime.providerCount(),
            subscriberCount = CentralRuntime.subscriberCount(),
            providerRegistrationAttempts = registration.attempts,
            providerRegistrationStage = registration.stage.name,
            providerRegistrationErrorCode = registration.errorCode,
            providerRegistrationErrorDetail = registration.errorDetail
        )
    }

    /**
     * 发送中央启动完成广播。
     *
     * 通知系统或其他组件中央模块已完成初始化。
     */
    fun sendBootCompleted() {
        val app = context ?: return
        app.sendBroadcast(Intent(CentralConstants.ACTION_CENTRAL_BOOT_COMPLETED))
    }

    /**
     * 停止内置 Central。
     *
     * API 102 热重载时先注销广播并关闭所有 Binder 连接。新模块代际初始化后会再次
     * 发送 ACTION_CENTRAL_BOOT_COMPLETED，使新版 Provider/Subscriber 自动重新注册。
     */
    @Synchronized
    fun release() {
        val app = context ?: return
        runCatching { app.unregisterReceiver(receiver) }
        CentralRuntime.release()
        ScreenStateMonitor.release()
        context = null
    }
}
