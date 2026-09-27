/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.registration

import android.content.Intent
import android.util.Log
import io.github.proify.lyricon.central.internal.CentralConstants
import io.github.proify.lyricon.central.internal.provider.ProviderConnection
import io.github.proify.lyricon.central.internal.provider.ProviderDirectory
import io.github.proify.lyricon.central.internal.subscriber.SubscriberConnection
import io.github.proify.lyricon.central.internal.subscriber.SubscriberDirectory
import io.github.proify.lyricon.central.internal.wire.json
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo

/**
 * 注册请求分发器：按广播动作把提供端/订阅端注册请求交给对应目录处理。
 *
 * @property providers 提供端目录。
 * @property subscribers 订阅端目录。
 */
internal class RegistrationDispatcher(
    private val providers: ProviderDirectory,
    private val subscribers: SubscriberDirectory
) {

    /**
     * 处理一条注册广播。
     *
     * @param intent 注册广播。
     */
    fun handle(intent: Intent) {
        when (intent.action) {
            CentralConstants.ACTION_REGISTER_PROVIDER -> {
                RegistrationDiagnostics.onProviderIntent()
                registerProvider(intent)
            }
            CentralConstants.ACTION_REGISTER_SUBSCRIBER -> registerSubscriber(intent)
        }
    }

    /** 解析并注册提供端；成功后回执远端服务。 */
    private fun registerProvider(intent: Intent) {
        val binder = getBinder<IProviderBinder>(intent) ?: run {
            RegistrationDiagnostics.fail("B", "provider binder missing or invalid")
            return
        }
        RegistrationDiagnostics.onProviderBinder()

        var connection: ProviderConnection? = null

        try {
            val rawInfo = binder.providerInfo ?: run {
                RegistrationDiagnostics.fail("N", "providerInfo is null")
                return
            }

            val info = runCatching {
                rawInfo.toString(Charsets.UTF_8)
                    .let { json.decodeFromString(ProviderInfo.serializer(), it) }
            }.getOrElse { error ->
                RegistrationDiagnostics.fail(
                    "J",
                    error.javaClass.simpleName + ": " + (error.message ?: "")
                )
                Log.e(TAG, "Provider info decode failed", error)
                return
            }

            RegistrationDiagnostics.onProviderInfo()

            if (info.providerPackageName.isBlank() || info.playerPackageName.isBlank()) {
                RegistrationDiagnostics.fail("I", "provider/player package is blank")
                Log.e(TAG, "Provider info is invalid: $info")
                return
            }

            connection = providers.getOrCreate(binder, info)
            RegistrationDiagnostics.onProviderRegistered()
            Log.d(TAG, "Provider registered: $info")

            binder.onRegistrationCallback(connection.service)
            RegistrationDiagnostics.onProviderCallback()
        } catch (e: Exception) {
            RegistrationDiagnostics.fail(
                "C",
                e.javaClass.simpleName + ": " + (e.message ?: "")
            )
            Log.e(TAG, "Provider registration failed", e)
            connection?.let { providers.unregister(it) }
        }
    }

    /** 解析并注册订阅端；成功后回执远端服务。 */
    private fun registerSubscriber(intent: Intent) {
        val binder = getBinder<ISubscriberBinder>(intent) ?: return
        var connection: SubscriberConnection? = null

        try {
            val info = binder.subscriberInfo
                ?.toString(Charsets.UTF_8)
                ?.let { json.decodeFromString(SubscriberInfo.serializer(), it) }

            if (info?.packageName.isNullOrBlank() || info.processName.isBlank()) {
                Log.e(TAG, "Subscriber info is invalid: $info")
                return
            }

            connection = subscribers.getOrCreate(binder, info)
            Log.d(TAG, "Subscriber registered: $info")
            binder.onRegistrationCallback(connection.service)
        } catch (e: Exception) {
            Log.e(TAG, "Subscriber registration failed", e)
            connection?.let { subscribers.unregister(it) }
        }
    }

    /** 从广播中解析指定类型的注册 Binder。 */
    private inline fun <reified T> getBinder(intent: Intent): T? = runCatching {
        val binder = intent.getBundleExtra(CentralConstants.EXTRA_BUNDLE)
            ?.getBinder(CentralConstants.EXTRA_BINDER) ?: return null

        when (T::class) {
            IProviderBinder::class -> IProviderBinder.Stub.asInterface(binder) as? T
            ISubscriberBinder::class -> ISubscriberBinder.Stub.asInterface(binder) as? T
            else -> {
                Log.e(TAG, "Unknown binder type: ${T::class.java.simpleName}")
                null
            }
        }
    }.onFailure {
        Log.e(TAG, "Failed to get binder from intent", it)
    }.getOrNull()

    private companion object {
        private const val TAG = "RegistrationDispatcher"
    }
}
