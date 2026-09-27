/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.translate

import kotlinx.serialization.Serializable

/** 传统机器翻译配置。这里的 AI 是指生成式/LLM；Google NMT / Microsoft Translator 不走 LLM。 */
internal data class ClassicTranslationConfig(
    val engine: String,
    val targetLanguageCode: String,
    val googleApiKey: String? = null,
    val microsoftApiKey: String? = null,
    val microsoftRegion: String? = null,
) {
    val isUsable: Boolean
        get() = targetLanguageCode.isNotBlank() && when (engine) {
            ENGINE_GOOGLE -> !googleApiKey.isNullOrBlank()
            ENGINE_MICROSOFT -> !microsoftApiKey.isNullOrBlank()
            else -> false
        }

    companion object {
        const val ENGINE_GOOGLE = "google"
        const val ENGINE_MICROSOFT = "microsoft"
    }
}

@Serializable
internal data class ClassicTranslationItem(
    val index: Int,
    val translation: String,
)
