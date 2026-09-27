/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.subscriber

import io.github.proify.lyricon.central.internal.connection.ConnectionRegistry
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo

/** 订阅端目录：按 [SubscriberInfo] 身份维护订阅端连接。 */
internal class SubscriberDirectory {
    private val registry = ConnectionRegistry<SubscriberInfo, SubscriberConnection>()

    /**
     * 获取或创建订阅端连接。
     *
     * 复用已有连接时换绑死亡监听；已关闭的残留连接会被清理并新建。
     *
     * @param binder 本次注册的 Binder。
     * @param info 订阅端身份。
     */
    fun getOrCreate(binder: ISubscriberBinder, info: SubscriberInfo): SubscriberConnection {
        val existing = registry.get(info)
        if (existing != null) {
            // 订阅端以新 Binder 重新注册：换绑死亡监听；已关闭的残留连接会被
            // 清除并由下方新建连接取代。
            if (existing.reattach(binder)) return existing
            registry.unregister(existing)
        }
        return registry.register(SubscriberConnection(binder, info))
    }

    /** 注销订阅端连接。 */
    fun unregister(connection: SubscriberConnection) {
        registry.unregister(connection)
    }

    /** 关闭全部订阅端连接。 */
    fun release() {
        registry.clear()
    }
}
