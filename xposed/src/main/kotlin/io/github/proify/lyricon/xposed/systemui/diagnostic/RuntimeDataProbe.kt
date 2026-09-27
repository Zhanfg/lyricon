/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.diagnostic

import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.logger.YLog

/**
 * SystemUI 内运行时数据链诊断。
 *
 * 状态顺序：
 * UI -> Central -> Subscriber -> Provider registration -> Active provider -> Lyric payload
 *
 * 该对象只保存轻量状态，不持有 View / Binder / Context。
 */
object RuntimeDataProbe {
    private const val TAG = "RuntimeDataProbe"

    enum class SubscriberState {
        INIT,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        TIMEOUT
    }

    @Volatile
    private var subscriberState: SubscriberState = SubscriberState.INIT

    @Volatile
    private var activeProviderPackage: String? = null

    @Volatile
    private var lyricKind: String? = null

    @Volatile
    private var lyricSummary: String? = null

    @Volatile
    private var nativeLyricStage: String = "NONE"

    @Volatile
    private var nativeLyricDetail: String? = null

    fun markSubscriberConnecting() {
        updateSubscriber(SubscriberState.CONNECTING)
    }

    fun markSubscriberConnected() {
        updateSubscriber(SubscriberState.CONNECTED)
    }

    fun markSubscriberDisconnected() {
        updateSubscriber(SubscriberState.DISCONNECTED)
    }

    fun markSubscriberTimeout() {
        updateSubscriber(SubscriberState.TIMEOUT)
    }

    fun markNativeLyricSeen(packageName: String) {
        nativeLyricStage = "SEEN"
        nativeLyricDetail = packageName
        logSnapshot("native_seen")
    }

    fun markNativeLyricActive(packageName: String, lineCount: Int) {
        nativeLyricStage = "ACTIVE"
        nativeLyricDetail = packageName + " / lines=" + lineCount
        logSnapshot("native_active")
    }

    fun markNativeLyricError(code: String, detail: String? = null) {
        nativeLyricStage = "ERROR:" + code
        nativeLyricDetail = detail?.take(96)
        logSnapshot("native_error")
    }

    fun markNativeLyricInactive() {
        nativeLyricStage = "NONE"
        nativeLyricDetail = null
    }

    fun markActiveProvider(providerInfo: ProviderInfo?) {
        activeProviderPackage = providerInfo?.playerPackageName
        if (providerInfo == null) {
            lyricKind = null
            lyricSummary = null
        }
        logSnapshot("active_provider")
    }

    fun markSong(name: String?, lyricCount: Int) {
        if (name.isNullOrBlank()) {
            lyricKind = null
            lyricSummary = null
        } else {
            lyricKind = "SONG"
            lyricSummary = name + " / lines=" + lyricCount
        }
        logSnapshot("song")
    }

    fun markText(text: String?) {
        if (text.isNullOrBlank()) {
            lyricKind = null
            lyricSummary = null
        } else {
            lyricKind = "TEXT"
            lyricSummary = text.take(24)
        }
        logSnapshot("text")
    }

    fun reset() {
        subscriberState = SubscriberState.INIT
        activeProviderPackage = null
        lyricKind = null
        lyricSummary = null
        nativeLyricStage = "NONE"
        nativeLyricDetail = null
    }

    fun snapshot(): Snapshot {
        val central = BridgeCentral.diagnostics()
        return Snapshot(
            centralInitialized = central.initialized,
            subscriberState = subscriberState,
            providerCount = central.providerCount,
            subscriberCount = central.subscriberCount,
            providerRegistrationAttempts = central.providerRegistrationAttempts,
            providerRegistrationStage = central.providerRegistrationStage,
            providerRegistrationErrorCode = central.providerRegistrationErrorCode,
            providerRegistrationErrorDetail = central.providerRegistrationErrorDetail,
            nativeLyricStage = nativeLyricStage,
            nativeLyricDetail = nativeLyricDetail,
            activeProviderPackage = activeProviderPackage,
            lyricKind = lyricKind,
            lyricSummary = lyricSummary
        )
    }

    private fun updateSubscriber(state: SubscriberState) {
        subscriberState = state
        logSnapshot("subscriber")
    }

    private fun logSnapshot(reason: String) {
        val s = snapshot()
        YLog.info(
            TAG,
            "reason=" + reason +
                    " central=" + s.centralInitialized +
                    " subscriber=" + s.subscriberState +
                    " centralSubscribers=" + s.subscriberCount +
                    " providers=" + s.providerCount +
                    " registerAttempts=" + s.providerRegistrationAttempts +
                    " registerStage=" + s.providerRegistrationStage +
                    " registerError=" + (s.providerRegistrationErrorCode ?: "none") +
                    " registerDetail=" + (s.providerRegistrationErrorDetail ?: "") +
                    " nativeStage=" + s.nativeLyricStage +
                    " nativeDetail=" + (s.nativeLyricDetail ?: "") +
                    " active=" + (s.activeProviderPackage ?: "none") +
                    " lyric=" + (s.lyricKind ?: "none") +
                    " detail=" + (s.lyricSummary ?: "")
        )
    }

    data class Snapshot(
        val centralInitialized: Boolean,
        val subscriberState: SubscriberState,
        val providerCount: Int,
        val subscriberCount: Int,
        val providerRegistrationAttempts: Int,
        val providerRegistrationStage: String,
        val providerRegistrationErrorCode: String?,
        val providerRegistrationErrorDetail: String?,
        val nativeLyricStage: String,
        val nativeLyricDetail: String?,
        val activeProviderPackage: String?,
        val lyricKind: String?,
        val lyricSummary: String?
    ) {
        fun compactLabel(): String {
            val central = if (centralInitialized) "C✓" else "C×"
            val subscriber = when (subscriberState) {
                SubscriberState.INIT -> "S?"
                SubscriberState.CONNECTING -> "S…"
                SubscriberState.CONNECTED -> "S✓"
                SubscriberState.DISCONNECTED -> "S×"
                SubscriberState.TIMEOUT -> "S!"
            }
            val registration = when {
                providerRegistrationAttempts == 0 -> "R0"
                providerRegistrationErrorCode != null ->
                    "R!" + providerRegistrationErrorCode
                else -> when (providerRegistrationStage) {
                    "INTENT" -> "RI"
                    "BINDER" -> "RB"
                    "INFO" -> "RF"
                    "REGISTERED" -> "RR"
                    "CALLBACK" -> "RC"
                    else -> "R?"
                }
            }
            val provider = "P" + providerCount
            val native = when {
                nativeLyricStage == "ACTIVE" -> "N✓"
                nativeLyricStage == "SEEN" -> "N…"
                nativeLyricStage.startsWith("ERROR:") -> "N!" + nativeLyricStage.substringAfter(':')
                else -> "N0"
            }
            val active = if (activeProviderPackage != null) "A✓" else "A×"
            val lyric = if (lyricKind != null) "L✓" else "L×"
            return central + " " + subscriber + " " + registration +
                    " " + provider + " " + native + " " + active + " " + lyric
        }
    }
}
