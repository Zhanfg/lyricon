/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import io.github.proify.lyricon.central.internal.player.ActivePlayerHub
import io.github.proify.lyricon.central.internal.provider.ProviderDirectory
import io.github.proify.lyricon.central.internal.registration.RegistrationDiagnostics
import io.github.proify.lyricon.central.internal.registration.RegistrationDispatcher
import io.github.proify.lyricon.central.internal.subscriber.SubscriberDirectory

/**
 * 中央运行时：全局单例的注册表与分发器集合。
 */
internal object CentralRuntime {
    val activePlayers = ActivePlayerHub()
    val providers = ProviderDirectory(activePlayers)
    val subscribers = SubscriberDirectory()
    val registration = RegistrationDispatcher(providers, subscribers)

    fun providerCount(): Int = providers.count()
    fun subscriberCount(): Int = subscribers.count()
    fun registrationDiagnostics(): RegistrationDiagnostics.Snapshot =
        RegistrationDiagnostics.snapshot()

    /** 释放全部中央连接与播放器状态。 */
    fun release() {
        subscribers.release()
        providers.release()
        activePlayers.release()
        RegistrationDiagnostics.reset()
    }
}
