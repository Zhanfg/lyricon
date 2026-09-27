/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.translate

import android.content.Context
import android.text.Html
import android.util.Log
import io.github.proify.android.extensions.md5
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 非 LLM 歌词翻译器。
 *
 * Google 路径固定请求 Cloud Translation Basic 的 NMT model；
 * Microsoft 路径使用 Translator Text v3。
 */
object ClassicTranslator {
    private const val TAG = "ClassicTranslator"
    private const val MAX_CACHE_SIZE = 1000
    private const val GOOGLE_ENDPOINT =
        "https://translation.googleapis.com/language/translate/v2"
    private const val MICROSOFT_ENDPOINT =
        "https://api.cognitive.microsofttranslator.com/translate"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cache = ClassicTranslationCache(MAX_CACHE_SIZE, scope)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        cache.init(context)
    }

    suspend fun translateSong(
        song: Song,
        config: ClassicTranslationConfig,
    ): Song {
        if (!config.isUsable || song.lyrics.isNullOrEmpty()) return song

        val requestItems = song.lyrics.orEmpty().mapIndexedNotNull { index, line ->
            val source = line.text?.trim().orEmpty()
            if (
                source.isBlank() ||
                !line.translation.isNullOrBlank() ||
                source.none { it.isLetter() }
            ) {
                null
            } else {
                IndexedText(index, source)
            }
        }
        if (requestItems.isEmpty()) return song

        val cacheKey = calculateKey(song, config, requestItems)

        cache.getMemory(cacheKey)?.let {
            return apply(song, it)
        }
        cache.getDb(cacheKey)?.let {
            cache.putMemory(cacheKey, it)
            return apply(song, it)
        }

        val results = try {
            when (config.engine) {
                ClassicTranslationConfig.ENGINE_GOOGLE ->
                    translateWithGoogle(requestItems, config)

                ClassicTranslationConfig.ENGINE_MICROSOFT ->
                    translateWithMicrosoft(requestItems, config)

                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Translation failed: engine=" + config.engine, e)
            emptyList()
        }

        if (results.isEmpty()) return song

        cache.putMemory(cacheKey, results)
        cache.putDb(cacheKey, results)
        return apply(song, results)
    }

    fun clearCache(callback: () -> Unit) {
        cache.clear(callback)
    }

    fun release() {
        scope.cancel()
        cache.close()
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private suspend fun translateWithGoogle(
        items: List<IndexedText>,
        config: ClassicTranslationConfig,
    ): List<ClassicTranslationItem> = withContext(Dispatchers.IO) {
        val target = normalizeGoogleLanguage(config.targetLanguageCode)
        val apiKey = config.googleApiKey.orEmpty()
        val output = ArrayList<ClassicTranslationItem>()

        items.chunked(128).forEach { batch ->
            coroutineContext.ensureActive()

            val payload = JSONObject().apply {
                put("q", JSONArray().apply { batch.forEach { put(it.text) } })
                put("target", target)
                put("format", "text")
                // 明确固定为传统 NMT，不请求 Translation LLM。
                put("model", "nmt")
            }

            val request = Request.Builder()
                .url(GOOGLE_ENDPOINT)
                .header("X-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Google Translate HTTP " + response.code + ": " + bodyText.take(256)
                    )
                }

                val translations = JSONObject(bodyText)
                    .getJSONObject("data")
                    .getJSONArray("translations")

                if (translations.length() != batch.size) {
                    throw IllegalStateException(
                        "Google result count mismatch: " +
                                translations.length() + " != " + batch.size
                    )
                }

                batch.forEachIndexed { index, source ->
                    val translated = translations
                        .getJSONObject(index)
                        .optString("translatedText")
                        .decodeHtml()
                        .trim()
                    if (translated.isNotBlank()) {
                        output += ClassicTranslationItem(source.index, translated)
                    }
                }
            }
        }

        output
    }

    private suspend fun translateWithMicrosoft(
        items: List<IndexedText>,
        config: ClassicTranslationConfig,
    ): List<ClassicTranslationItem> = withContext(Dispatchers.IO) {
        val target = normalizeMicrosoftLanguage(config.targetLanguageCode)
        val apiKey = config.microsoftApiKey.orEmpty()
        val region = config.microsoftRegion.orEmpty()
        val output = ArrayList<ClassicTranslationItem>()

        chunkMicrosoft(items).forEach { batch ->
            coroutineContext.ensureActive()

            val body = JSONArray().apply {
                batch.forEach { source ->
                    put(JSONObject().put("Text", source.text))
                }
            }

            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.cognitive.microsofttranslator.com")
                .addPathSegment("translate")
                .addQueryParameter("api-version", "3.0")
                .addQueryParameter("to", target)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .header("Content-Type", "application/json; charset=utf-8")

            if (region.isNotBlank()) {
                requestBuilder.header("Ocp-Apim-Subscription-Region", region)
            }

            val request = requestBuilder
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Microsoft Translator HTTP " + response.code + ": " +
                                bodyText.take(256)
                    )
                }

                val results = JSONArray(bodyText)
                if (results.length() != batch.size) {
                    throw IllegalStateException(
                        "Microsoft result count mismatch: " +
                                results.length() + " != " + batch.size
                    )
                }

                batch.forEachIndexed { index, source ->
                    val translated = results
                        .getJSONObject(index)
                        .getJSONArray("translations")
                        .optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                        .trim()

                    if (translated.isNotBlank()) {
                        output += ClassicTranslationItem(source.index, translated)
                    }
                }
            }
        }

        output
    }

    /**
     * Microsoft Translator v3 单次最多 25 个输入，且请求正文中的文本总量最多 5000 字符。
     */
    private fun chunkMicrosoft(items: List<IndexedText>): List<List<IndexedText>> {
        val batches = ArrayList<List<IndexedText>>()
        var current = ArrayList<IndexedText>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                batches += current
                current = ArrayList()
                chars = 0
            }
        }

        items.forEach { item ->
            // 歌词行正常不会接近 5000；异常超长行直接跳过，避免整个批次被 API 拒绝。
            if (item.text.length > 5000) return@forEach

            if (current.size >= 25 || chars + item.text.length > 5000) {
                flush()
            }
            current += item
            chars += item.text.length
        }
        flush()
        return batches
    }

    private fun apply(
        song: Song,
        translations: List<ClassicTranslationItem>,
    ): Song {
        val byIndex = translations.associateBy { it.index }
        val lines = song.lyrics?.mapIndexed { index, line ->
            val translated = byIndex[index]?.translation?.trim()
            if (
                !translated.isNullOrBlank() &&
                line.translation.isNullOrBlank() &&
                translated.lowercase(Locale.ROOT) !=
                line.text?.trim()?.lowercase(Locale.ROOT)
            ) {
                line.copy(
                    translation = translated,
                    translationWords = null,
                )
            } else {
                line
            }
        }
        return song.copy(lyrics = lines)
    }

    private fun calculateKey(
        song: Song,
        config: ClassicTranslationConfig,
        items: List<IndexedText>,
    ): String = buildString {
        append("engine=").appendLine(config.engine)
        append("target=").appendLine(config.targetLanguageCode)
        append("title=").appendLine(song.name.orEmpty())
        append("artist=").appendLine(song.artist.orEmpty())
        items.forEach {
            append(it.index).append(':').appendLine(it.text)
        }
    }.md5()

    private fun normalizeGoogleLanguage(code: String): String {
        val normalized = code.trim().replace('_', '-')
        return when (normalized.lowercase(Locale.ROOT)) {
            "zh-hans", "zh-cn", "zh-sg" -> "zh-CN"
            "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> "zh-TW"
            else -> normalized
        }
    }

    private fun normalizeMicrosoftLanguage(code: String): String {
        val normalized = code.trim().replace('_', '-')
        return when (normalized.lowercase(Locale.ROOT)) {
            "zh", "zh-cn", "zh-sg", "zh-hans" -> "zh-Hans"
            "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> "zh-Hant"
            else -> normalized
        }
    }

    private fun String.decodeHtml(): String =
        Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()

    private data class IndexedText(
        val index: Int,
        val text: String,
    )
}
