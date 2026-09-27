/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.registration

import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider 注册链只读诊断状态。
 *
 * 不持有 Context/Binder，仅记录阶段和轻量错误码，供 SystemUI 调试探针读取。
 */
internal object RegistrationDiagnostics {

    enum class Stage {
        NONE,
        INTENT,
        BINDER,
        INFO,
        REGISTERED,
        CALLBACK
    }

    private val providerAttempts = AtomicInteger(0)

    @Volatile
    private var providerStage: Stage = Stage.NONE

    @Volatile
    private var providerErrorCode: String? = null

    @Volatile
    private var providerErrorDetail: String? = null

    fun onProviderIntent() {
        providerAttempts.incrementAndGet()
        providerStage = Stage.INTENT
        providerErrorCode = null
        providerErrorDetail = null
    }

    fun onProviderBinder() {
        providerStage = Stage.BINDER
    }

    fun onProviderInfo() {
        providerStage = Stage.INFO
    }

    fun onProviderRegistered() {
        providerStage = Stage.REGISTERED
    }

    fun onProviderCallback() {
        providerStage = Stage.CALLBACK
    }

    fun fail(code: String, detail: String? = null) {
        providerErrorCode = code
        providerErrorDetail = detail?.take(96)
    }

    fun snapshot(): Snapshot = Snapshot(
        attempts = providerAttempts.get(),
        stage = providerStage,
        errorCode = providerErrorCode,
        errorDetail = providerErrorDetail
    )

    fun reset() {
        providerAttempts.set(0)
        providerStage = Stage.NONE
        providerErrorCode = null
        providerErrorDetail = null
    }

    data class Snapshot(
        val attempts: Int,
        val stage: Stage,
        val errorCode: String?,
        val errorDetail: String?
    )
}
