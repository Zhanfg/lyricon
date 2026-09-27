/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.IRemoteService
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.SubscriberInfo
import io.github.proify.lyricon.subscriber.SubscriberStatus
import io.github.proify.lyricon.subscriber.internal.SubscriberConstants
import io.github.proify.lyricon.subscriber.internal.binding.RegistrationBinder
import io.github.proify.lyricon.subscriber.internal.connection.CentralConnection
import io.github.proify.lyricon.subscriber.internal.registration.CentralBootReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认订阅端实现。
 *
 * 负责发送注册广播、处理连接超时重试、维护连接状态，并把远端服务交给
 * [CentralConnection] 管理。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class LyriconSubscriberImpl(
    private val context: Context,
    override val subscriberInfo: SubscriberInfo,
) : LyriconSubscriber {
    private val destroyed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<ConnectionListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val remote =
        CentralConnection { disconnect(remote = true, notifyRemote = false) }
    private val registration = RegistrationCoordinator()

    /** 当前连接状态（由 [RegistrationCoordinator] 维护）。 */
    val status: SubscriberStatus
        get() = registration.status

    override fun addConnectionListener(listener: ConnectionListener) {
        listeners.add(listener)
    }

    override fun removeConnectionListener(listener: ConnectionListener) {
        listeners.remove(listener)
    }

    override fun subscribeActivePlayer(listener: ActivePlayerListener): Boolean =
        remote.addActivePlayerListener(listener)

    override fun unsubscribeActivePlayer(listener: ActivePlayerListener): Boolean =
        remote.removeActivePlayerListener(listener)

    override fun register() {
        registration.start(manual = true)
    }

    override fun unregister() {
        if (destroyed.get()) return
        registration.cancel()
        disconnect(remote = false)
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        registration.close()
        disconnect(remote = false, destroy = true)
        scope.cancel()
        listeners.clear()
    }

    /** 注册成功：标记连接、绑定远端服务并通知监听器（区分首次与重连）。 */
    private fun onRegistered(service: IRemoteService?, reconnect: Boolean) {
        registration.markConnected()
        remote.bind(service)
        listeners.forEach {
            if (reconnect) it.onReconnected(this) else it.onConnected(this)
        }
    }

    /**
     * 断开连接并通知监听器。
     *
     * @param remote 是否为远端导致的断开。
     * @param notifyRemote 是否调用远端 disconnect 接口。
     * @param destroy 是否彻底销毁连接端点。
     */
    private fun disconnect(
        remote: Boolean,
        notifyRemote: Boolean = true,
        destroy: Boolean = false
    ) {
        registration.abortAttempt()
        this.remote.disconnect(notifyRemote, destroy)
        registration.markDisconnected(remote)
        listeners.forEach { it.onDisconnected(this) }
    }

    /**
     * 注册协调器：发送注册广播、管理超时重试与中心重启后的恢复注册。
     *
     * 注册尝试（[RegistrationAttempts] + [RegistrationBinder]）保证回调至多被消费一次：
     * - 注销/销毁会结束尝试，迟到回调不再建立连接；
     * - 连接中重复注册被拒绝，避免重复广播与重复 onConnected；
     * - 超时后仍期望连接时，中心启动广播可恢复注册。
     */
    private inner class RegistrationCoordinator : CentralBootReceiver.BootListener {
        private val binder = RegistrationBinder(subscriberInfo)
        private var timeoutJob: Job? = null
        private var retryCount = 0
        private var reconnect = false

        /** 期望保持连接（register 时置位，注销/销毁时复位）。 */
        @Volatile
        private var wantsConnection: Boolean = false

        @Volatile
        var status: SubscriberStatus = SubscriberStatus.DISCONNECTED
            private set

        /** 标记为已连接。 */
        fun markConnected() {
            status = SubscriberStatus.CONNECTED
        }

        /** 标记为断开。 */
        fun markDisconnected(byRemote: Boolean) {
            status =
                if (byRemote) SubscriberStatus.DISCONNECTED_BY_REMOTE
                else SubscriberStatus.DISCONNECTED
        }

        private val attempt = object : RegistrationBinder.RegistrationAttempt {
            override fun onCompleted(service: IRemoteService?) {
                cancelTimeout()
                retryCount = 0
                if (!wantsConnection || destroyed.get()) return

                if (service == null) {
                    // 中心返回空服务（异常路径）：不标记连接，保持期望连接等待恢复信号。
                    Log.w(TAG, "Central returned null service, registration rejected")
                    status = SubscriberStatus.DISCONNECTED
                    return
                }
                this@LyriconSubscriberImpl.onRegistered(service, reconnect)
            }
        }

        init {
            CentralBootReceiver.addBootListener(this)
        }

        /**
         * 发起注册（用户主动或中心启动恢复）。
         *
         * @param manual 用户主动触发；否则为系统恢复路径。
         */
        fun start(manual: Boolean) {
            if (destroyed.get()) return
            if (status == SubscriberStatus.CONNECTED || status == SubscriberStatus.CONNECTING) return

            wantsConnection = true
            reconnect = !manual || status.isDisconnectedByRemote()
            retryCount = 0
            send()
        }

        override fun onBootCompleted() {
            if (!wantsConnection || destroyed.get()) return

            // 与 Provider 一致：Central boot 是 epoch 变化，不依赖 binderDied。
            // API 102 原地热重载时旧 Binder 可能仍 alive，必须显式丢弃再注册。
            if (status == SubscriberStatus.CONNECTED || status == SubscriberStatus.CONNECTING) {
                this@LyriconSubscriberImpl.disconnect(
                    remote = true,
                    notifyRemote = false
                )
            }
            start(manual = false)
        }

        /** 结束当前尝试但保留期望连接（远端断开/超时等非用户路径）。 */
        fun abortAttempt() {
            cancelTimeout()
            binder.endAttempt(attempt)
        }

        /** 注销/销毁：结束尝试并清除期望连接。 */
        fun cancel() {
            wantsConnection = false
            abortAttempt()
        }

        /** 销毁：结束尝试并停止监听中心启动广播。 */
        fun close() {
            cancel()
            CentralBootReceiver.removeBootListener(this)
        }

        /** 取消注册超时等待。 */
        private fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        /** 发送注册广播并启动超时等待。 */
        private fun send() {
            if (destroyed.get()) return
            status = SubscriberStatus.CONNECTING
            binder.beginAttempt(attempt)
            context.sendBroadcast(Intent(SubscriberConstants.ACTION_REGISTER_SUBSCRIBER).apply {
                setPackage(SubscriberConstants.SYSTEM_UI_PACKAGE_NAME)
                putExtra(
                    SubscriberConstants.EXTRA_BUNDLE,
                    Bundle().apply {
                        putBinder(SubscriberConstants.EXTRA_BINDER, binder)
                    }
                )
            })
            scheduleTimeout()
        }

        /** 启动注册超时等待；超时后按次数重发广播，耗尽则报告超时。 */
        private fun scheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (destroyed.get() || !status.isConnecting() || !wantsConnection) return@launch
                if (retryCount++ < MAX_RETRY_COUNT) {
                    send()
                } else {
                    retryCount = 0
                    status = SubscriberStatus.DISCONNECTED
                    listeners.forEach { it.onConnectTimeout(this@LyriconSubscriberImpl) }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "LyriconSubscriberImpl"
        private const val MAX_RETRY_COUNT = 3
        private const val CONNECT_TIMEOUT_MS = 3_000L
    }
}
