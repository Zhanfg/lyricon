/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import io.github.proify.lyricon.provider.ProviderInfo

/**
 * 活跃播放器选择器（纯 Kotlin，可单元测试）。
 *
 * 策略：
 * - 先到先得：第一个提供状态事件的来源成为活跃者；
 * - 播放抢占：当前活跃者未在播放时，播放中的新来源可以抢占；
 * - 活跃者断开/注销时释放（[release]）；
 * - 同一来源的事件持续保持活跃身份，并跟随其播放状态。
 *
 * 本类只做状态与决策，不发任何通知；对外行为（切换时全量报告、事件增量取舍）
 * 由 [ActivePlayerHub] 依据 [ActivePlayerDecision] 完成。
 */
internal class ActivePlayerSelector {

    /** 当前活跃会话，null 表示没有活跃播放器。 */
    var activeSession: PlayerSession? = null
        private set

    /** 当前活跃者是否处于播放状态。 */
    var activeIsPlaying: Boolean = false
        private set

    /**
     * 处理一条来自 [session] 的状态事件并给出决策。
     *
     * @param session 事件来源的播放器会话。
     * @param allowDuplicateIfSwitching 切换后是否额外广播原始事件增量；
     *   内容类事件（歌曲/文本/显示偏好）已由完整报告覆盖，传 false；
     *   实时事件（播放状态/位置/跳转）传 true。
     */
    fun decide(
        session: PlayerSession,
        allowDuplicateIfSwitching: Boolean = true,
    ): ActivePlayerDecision {
        val sessionPlaying = session.isPlaying
        return if (activeSession === session) {
            activeIsPlaying = sessionPlaying
            ActivePlayerDecision.Keep
        } else {
            val canSwitch = activeSession == null || (!activeIsPlaying && sessionPlaying)
            if (canSwitch) {
                activeSession = session
                activeIsPlaying = sessionPlaying
                ActivePlayerDecision.Switch(session, allowDuplicateIfSwitching)
            } else {
                ActivePlayerDecision.Ignore
            }
        }
    }

    /**
     * 活跃者失效（断开/注销）时释放占位。
     *
     * @param providerInfo 失效提供端的信息。
     * @return 是否为当前活跃者；false 表示释放被忽略。
     */
    fun release(providerInfo: ProviderInfo): Boolean {
        if (activeSession?.providerInfo != providerInfo) return false
        activeSession = null
        activeIsPlaying = false
        return true
    }

    /** 无条件清空选择状态。用于 Central 生命周期结束。 */
    fun reset() {
        activeSession = null
        activeIsPlaying = false
    }
}
