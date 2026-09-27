/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.connection

import java.util.concurrent.ConcurrentHashMap

/**
 * 连接注册表：按连接 key 管理远端连接，并注入 Binder 死亡回调。
 *
 * @property K 连接身份类型。
 * @property C 连接类型。
 */
internal class ConnectionRegistry<K, C : RemoteConnection<K>> {

    private val connections = ConcurrentHashMap<K, C>()

    /**
     * 注册连接；返回最终生效的连接（key 已有时返回已有的）。
     *
     * 死亡回调注入：仅在对应 Binder 死亡时删除该 key 的连接并关闭。
     */
    fun register(connection: C): C {
        val existing = connections.putIfAbsent(connection.key, connection)
        if (existing != null) return existing

        connection.setDeathRecipient { unregister(connection.key) }
        return connection
    }

    /** 按 key 注销连接；不存在时返回 null。 */
    fun unregister(key: K): C? {
        val removed = connections.remove(key) ?: return null
        removed.close()
        return removed
    }

    /** 按连接实例注销（身份匹配时才生效）。 */
    fun unregister(connection: C): Boolean {
        val removed = connections.remove(connection.key, connection)
        if (removed) connection.close()
        return removed
    }

    /** 查询连接。 */
    fun get(key: K): C? = connections[key]

    /** 关闭并移除全部连接。用于 Central 热重载/关闭。 */
    fun clear() {
        val snapshot = connections.values.toList()
        connections.clear()
        snapshot.forEach { connection ->
            connection.setDeathRecipient(null)
            runCatching { connection.close() }
        }
    }
}
