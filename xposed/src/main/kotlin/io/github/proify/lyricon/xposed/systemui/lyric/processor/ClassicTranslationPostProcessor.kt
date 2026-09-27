/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric.processor

import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.translate.ClassicTranslationConfig
import io.github.proify.lyricon.xposed.systemui.translate.ClassicTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Google / Microsoft 传统机器翻译后处理器。
 *
 * 与 AI 翻译互斥：由 translationEngine 决定实际执行哪一条链。
 */
class ClassicTranslationPostProcessor : PostProcessor {

    private companion object {
        const val TAG = "ClassicTranslationPostProcessor"
    }

    override val priority: Int = 10

    override fun isEnabled(style: LyricStyle): Boolean {
        val engine = style.basicStyle.translationEngine
        if (
            engine != TextStyle.TRANSLATION_ENGINE_GOOGLE &&
            engine != TextStyle.TRANSLATION_ENGINE_MICROSOFT
        ) {
            return false
        }

        val prefs = LyricPrefs.baseStylePrefs
        return when (engine) {
            TextStyle.TRANSLATION_ENGINE_GOOGLE ->
                !prefs.getString(TextStyle.KEY_TRANSLATION_GOOGLE_API_KEY, null).isNullOrBlank()

            TextStyle.TRANSLATION_ENGINE_MICROSOFT ->
                !prefs.getString(TextStyle.KEY_TRANSLATION_MICROSOFT_API_KEY, null).isNullOrBlank()

            else -> false
        }
    }

    override suspend fun process(song: Song, style: LyricStyle): Song {
        if (song.lyrics.isNullOrEmpty()) return song

        if (
            style.basicStyle.isAiTranslationAutoIgnoreChinese &&
            song.isFullyChinese()
        ) {
            Log.d(TAG, "Skip Chinese song: " + song.name)
            return song
        }

        if (song.lyrics.orEmpty().all { !it.translation.isNullOrBlank() }) {
            return song
        }

        val engine = style.basicStyle.translationEngine
        val targetCode = style.basicStyle.translationTargetLanguageCode
            .ifBlank { Locale.getDefault().toLanguageTag() }

        val prefs = LyricPrefs.baseStylePrefs
        val config = ClassicTranslationConfig(
            engine = engine,
            targetLanguageCode = targetCode,
            googleApiKey = prefs.getString(
                TextStyle.KEY_TRANSLATION_GOOGLE_API_KEY,
                null
            ),
            microsoftApiKey = prefs.getString(
                TextStyle.KEY_TRANSLATION_MICROSOFT_API_KEY,
                null
            ),
            microsoftRegion = prefs.getString(
                TextStyle.KEY_TRANSLATION_MICROSOFT_REGION,
                null
            ),
        )

        if (!config.isUsable) {
            Log.w(TAG, "Translation config is unusable: engine=" + engine)
            return song
        }

        return withContext(Dispatchers.IO) {
            try {
                ClassicTranslator.translateSong(song, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Classic translation failed for " + song.name, e)
                song
            }
        }
    }

    private fun Song.isFullyChinese(): Boolean =
        lyrics?.all { line ->
            line.text
                ?.filterNot { it.isWhitespace() || it.isPunctuation() }
                ?.all { it.isChinese() }
                ?: true
        } ?: true

    private fun Char.isChinese(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                block == Character.UnicodeBlock.GENERAL_PUNCTUATION
    }

    private fun Char.isPunctuation(): Boolean =
        !isLetterOrDigit() && !isWhitespace()
}
