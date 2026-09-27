/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.diagnostic.RuntimeDataProbe
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import org.json.JSONObject
import kotlin.math.max

/**
 * ColorOS 原生 lyricInfo -> Lyricon 数据桥。
 *
 * 新版 ColorOS Provider 会把歌词附加到播放器自己的 MediaMetadata["lyricInfo"]。
 * SystemUI 本身已经能看到该 MediaSession，因此当旧 Lyricon Provider 注册链不可用时，
 * 可以直接消费同一份 metadata，绕开跨 UID REGISTER_PROVIDER 广播/Binder 握手。
 *
 * 优先级：
 * - 有真实 Central Provider 时，本桥让位；
 * - Central 没有活跃 Provider 时，才启用 native metadata fallback。
 */
object NativeLyricInfoBridge : SystemUIMediaUtils.MediaControllerCallback {

    private const val TAG = "NativeLyricInfoBridge"
    private const val KEY_LYRIC_INFO = "lyricInfo"
    private const val NATIVE_PROVIDER_PREFIX = "native.lyricinfo:"
    private const val TICK_INTERVAL_MS = 100L
    private const val MISSING_LYRIC_GRACE_MS = 1800L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    @Volatile
    private var activePackage: String? = null

    @Volatile
    private var latestState: PlaybackState? = null

    private var latestTrackKey: String? = null
    private var latestMetadataTrackKey: String? = null
    private var latestLyricFingerprint: String? = null
    private var pendingClear: Runnable? = null
    private var tickerRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return

            val pkg = activePackage
            val state = latestState
            if (pkg != null && state != null && canUseFallback(pkg)) {
                ensureNativeProvider(pkg)
                LyricDataHub.onPositionChanged(computePosition(state))
                if (state.state == PlaybackState.STATE_PLAYING) {
                    mainHandler.postDelayed(this, TICK_INTERVAL_MS)
                    return
                }
            }

            tickerRunning = false
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        SystemUIMediaUtils.registerListener(this)
        YLog.info(TAG, "Initialized")
    }

    fun release() {
        if (!initialized) return
        initialized = false
        SystemUIMediaUtils.unregisterListener(this)
        pendingClear?.let(mainHandler::removeCallbacks)
        pendingClear = null
        stopTicker()
        latestState = null
        latestTrackKey = null
        latestMetadataTrackKey = null
        latestLyricFingerprint = null
        RuntimeDataProbe.markNativeLyricInactive()

        val pkg = activePackage
        activePackage = null
        if (pkg != null && isOurProvider(LyricDataHub.currentProviderInfo())) {
            LyricDataHub.onSongChanged(null)
            LyricDataHub.onActiveProviderChanged(null)
        }
        YLog.info(TAG, "Released")
    }

    override fun onMediaChanged(controller: MediaController, metadata: MediaMetadata) {
        val packageName = controller.packageName ?: return

        val lyricInfo = runCatching {
            metadata.getString(KEY_LYRIC_INFO)
                ?: metadata.getText(KEY_LYRIC_INFO)?.toString()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }

        if (lyricInfo == null) {
            handleMetadataWithoutLyrics(packageName, metadata)
            return
        }

        pendingClear?.let(mainHandler::removeCallbacks)
        pendingClear = null
        RuntimeDataProbe.markNativeLyricSeen(packageName)

        if (!canUseFallback(packageName)) {
            YLog.debug(TAG, "Central provider is active; skip native lyricInfo for " + packageName)
            return
        }

        val parsed = runCatching {
            parseLyricInfo(metadata, lyricInfo, packageName)
        }.onFailure { error ->
            RuntimeDataProbe.markNativeLyricError(
                "P",
                error.javaClass.simpleName + ": " + (error.message ?: "")
            )
            YLog.error(TAG, "Failed to parse lyricInfo for " + packageName, error)
        }.getOrNull() ?: return

        val fingerprint = parsed.trackKey + "#" + lyricInfo.hashCode()
        if (fingerprint == latestLyricFingerprint) {
            return
        }

        latestLyricFingerprint = fingerprint
        latestTrackKey = parsed.trackKey
        latestMetadataTrackKey = metadataTrackKey(metadata)
        activePackage = packageName

        ensureNativeProvider(packageName)
        LyricDataHub.onSongChanged(parsed.song)
        RuntimeDataProbe.markNativeLyricActive(
            packageName,
            parsed.song.lyrics?.size ?: 0
        )

        controller.playbackState?.let {
            latestState = it
            dispatchPlaybackState(packageName, it)
        }

        YLog.info(
            TAG,
            "Native lyricInfo accepted: pkg=" + packageName +
                    " title=" + parsed.song.name +
                    " lines=" + (parsed.song.lyrics?.size ?: 0)
        )
    }

    override fun onPlaybackStateChanged(controller: MediaController, state: PlaybackState) {
        val packageName = controller.packageName ?: return
        if (packageName != activePackage) return
        if (!canUseFallback(packageName)) {
            stopTicker()
            return
        }

        latestState = state
        dispatchPlaybackState(packageName, state)
    }

    override fun onSessionDestroyed(controller: MediaController) {
        val packageName = controller.packageName ?: return
        if (packageName != activePackage) return
        clearFallback("session_destroyed")
    }

    private fun dispatchPlaybackState(packageName: String, state: PlaybackState) {
        if (packageName != activePackage || !canUseFallback(packageName)) return

        ensureNativeProvider(packageName)

        val playing = state.state == PlaybackState.STATE_PLAYING
        LyricDataHub.onPlaybackStateChanged(playing)
        LyricDataHub.onSeekTo(computePosition(state))

        if (playing) startTicker() else stopTicker()

        YLog.debug(
            TAG,
            "Playback state: pkg=" + packageName +
                    " state=" + state.state +
                    " position=" + computePosition(state) +
                    " speed=" + state.playbackSpeed
        )
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    private fun computePosition(state: PlaybackState): Long {
        var position = state.position.coerceAtLeast(0L)
        if (
            state.state == PlaybackState.STATE_PLAYING &&
            state.lastPositionUpdateTime > 0L
        ) {
            val delta = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                .coerceAtLeast(0L)
            position += (delta * state.playbackSpeed).toLong()
        }
        return position.coerceAtLeast(0L)
    }

    private fun ensureNativeProvider(packageName: String) {
        val current = LyricDataHub.currentProviderInfo()
        if (
            current?.playerPackageName == packageName &&
            isOurProvider(current)
        ) {
            return
        }
        if (current != null && !isOurProvider(current)) {
            return
        }

        val wasMissing = current == null
        LyricDataHub.onActiveProviderChanged(
            ProviderInfo(
                providerPackageName = NATIVE_PROVIDER_PREFIX + packageName,
                playerPackageName = packageName,
                processName = packageName
            )
        )

        // Central 的异步 null 回调可能在 native 歌词之后到达并重置 UI。
        // 重新取得 fallback 所有权时重放缓存歌曲即可恢复，无需重新解析 metadata。
        if (wasMissing && activePackage == packageName) {
            LyricDataHub.reprocessCurrentSong()
        }
    }

    private fun canUseFallback(packageName: String): Boolean {
        val current = LyricDataHub.currentProviderInfo() ?: return true
        if (isOurProvider(current)) return current.playerPackageName == packageName
        return false
    }

    private fun isOurProvider(info: ProviderInfo?): Boolean =
        info?.providerPackageName?.startsWith(NATIVE_PROVIDER_PREFIX) == true

    private fun handleMetadataWithoutLyrics(
        packageName: String,
        metadata: MediaMetadata
    ) {
        if (packageName != activePackage) return

        val currentTrack = metadataTrackKey(metadata)
        val previousTrack = latestMetadataTrackKey
        if (
            previousTrack.isNullOrBlank() ||
            currentTrack.isBlank() ||
            currentTrack == previousTrack
        ) {
            return
        }

        pendingClear?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            pendingClear = null
            if (activePackage == packageName && latestTrackKey == previousTrack) {
                clearFallback("track_changed_without_lyricInfo")
            }
        }
        pendingClear = task
        mainHandler.postDelayed(task, MISSING_LYRIC_GRACE_MS)
    }

    private fun clearFallback(reason: String) {
        val current = LyricDataHub.currentProviderInfo()
        if (!isOurProvider(current)) return

        YLog.info(TAG, "Clearing native fallback: " + reason)
        pendingClear?.let(mainHandler::removeCallbacks)
        pendingClear = null
        stopTicker()
        latestState = null
        latestTrackKey = null
        latestMetadataTrackKey = null
        latestLyricFingerprint = null
        activePackage = null
        RuntimeDataProbe.markNativeLyricInactive()
        LyricDataHub.onSongChanged(null)
        LyricDataHub.onPlaybackStateChanged(false)
        LyricDataHub.onActiveProviderChanged(null)
    }

    private data class ParsedLyricInfo(
        val trackKey: String,
        val song: Song
    )

    private fun parseLyricInfo(
        metadata: MediaMetadata,
        rawJson: String,
        packageName: String
    ): ParsedLyricInfo {
        val json = JSONObject(rawJson)

        if (json.optBoolean("noLyric", false)) {
            throw IllegalArgumentException("lyricInfo reports noLyric=true")
        }

        val rawLyric = json.optString("rawLyric", "")
        val plainLyric = json.optString("lyric", "")
        val translationLyric = json.optString("translationLyric", "")

        val translationByTime = parsePlainLrc(translationLyric)
            .associate { it.begin to it.text.orEmpty() }

        val enhanced = if (rawLyric.isNotBlank()) {
            parseEnhancedLrc(rawLyric, translationByTime)
        } else {
            emptyList()
        }

        val lines = enhanced.ifEmpty {
            val plain = parsePlainLrc(plainLyric)
            applyEstimatedEnds(
                plain.map { parsed ->
                    RichLyricLine(
                        begin = parsed.begin,
                        text = parsed.text,
                        translation = translationByTime[parsed.begin]
                    )
                },
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            )
        }

        if (lines.isEmpty()) {
            throw IllegalArgumentException("lyricInfo contains no timed lyric lines")
        }

        val title = json.optString("songName", "")
            .ifBlank {
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: ""
            }
        val artist = json.optString("artist", "")
            .ifBlank {
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: ""
            }
        val songId = json.optString("songId", "")
            .ifBlank {
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    ?: ""
            }

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            .takeIf { it > 0L }
            ?: lines.maxOfOrNull { it.end }?.coerceAtLeast(0L)
            ?: 0L

        val trackKey = json.optString("trackKey", "")
            .ifBlank {
                listOf(packageName, songId, title, artist).joinToString("|")
            }

        return ParsedLyricInfo(
            trackKey = trackKey,
            song = Song(
                id = songId,
                name = title,
                artist = artist,
                duration = duration,
                lyrics = lines
            )
        )
    }

    private data class PlainLine(
        val begin: Long,
        val text: String?
    )

    private val lineTag = Regex(
        """^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)$"""
    )

    private val wordTag = Regex(
        """<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>"""
    )

    private fun parsePlainLrc(value: String): List<PlainLine> =
        value.lineSequence()
            .mapNotNull { source ->
                val line = source.trim()
                val match = lineTag.matchEntire(line) ?: return@mapNotNull null
                val time = parseTime(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                )
                val text = match.groupValues[4].trim()
                if (text.isBlank()) null else PlainLine(time, text)
            }
            .sortedBy { it.begin }
            .toList()

    private fun parseEnhancedLrc(
        value: String,
        translations: Map<Long, String>
    ): List<RichLyricLine> {
        val sourceLines = value.lineSequence()
            .mapNotNull { source ->
                val line = source.trim()
                val match = lineTag.matchEntire(line) ?: return@mapNotNull null
                val begin = parseTime(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                )
                begin to match.groupValues[4]
            }
            .sortedBy { it.first }
            .toList()

        val parsed = ArrayList<RichLyricLine>(sourceLines.size)

        sourceLines.forEachIndexed { index, item ->
            val lineBegin = item.first
            val body = item.second
            val matches = wordTag.findAll(body).toList()

            if (matches.isEmpty()) {
                val text = body.trim()
                if (text.isNotBlank()) {
                    parsed += RichLyricLine(
                        begin = lineBegin,
                        text = text,
                        translation = translations[lineBegin]
                    )
                }
                return@forEachIndexed
            }

            val words = ArrayList<LyricWord>()
            var explicitLineEnd: Long? = null

            matches.forEachIndexed { wordIndex, match ->
                val wordBegin = parseTime(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                )
                val textStart = match.range.last + 1
                val textEnd = matches.getOrNull(wordIndex + 1)?.range?.first ?: body.length
                val segment = body.substring(textStart, textEnd)

                if (segment.isEmpty()) {
                    explicitLineEnd = max(explicitLineEnd ?: lineBegin, wordBegin)
                    return@forEachIndexed
                }

                val nextBegin = matches.getOrNull(wordIndex + 1)?.let { next ->
                    parseTime(
                        next.groupValues[1],
                        next.groupValues[2],
                        next.groupValues[3]
                    )
                } ?: (wordBegin + 400L)

                val wordEnd = max(wordBegin + 1L, nextBegin)
                words += LyricWord(
                    begin = wordBegin,
                    end = wordEnd,
                    duration = wordEnd - wordBegin,
                    text = segment
                )
            }

            val text = if (words.isNotEmpty()) {
                words.joinToString("") { it.text.orEmpty() }
            } else {
                wordTag.replace(body, "").trim()
            }

            if (text.isBlank()) return@forEachIndexed

            val nextLineBegin = sourceLines.getOrNull(index + 1)?.first
            val lineEnd = sequenceOf(
                explicitLineEnd,
                words.lastOrNull()?.end,
                nextLineBegin
            ).filterNotNull()
                .filter { it > lineBegin }
                .minOrNull()
                ?: (lineBegin + 5_000L)

            parsed += RichLyricLine(
                begin = lineBegin,
                end = lineEnd,
                duration = lineEnd - lineBegin,
                text = text,
                words = words.takeIf { it.isNotEmpty() },
                translation = translations[lineBegin]
            )
        }

        return applyEstimatedEnds(parsed, null)
    }

    private fun applyEstimatedEnds(
        source: List<RichLyricLine>,
        duration: Long?
    ): List<RichLyricLine> {
        if (source.isEmpty()) return source

        val sorted = source.sortedBy { it.begin }
        return sorted.mapIndexed { index, line ->
            if (line.end > line.begin) {
                line
            } else {
                val next = sorted.getOrNull(index + 1)?.begin
                val fallbackEnd = when {
                    next != null && next > line.begin -> next
                    duration != null && duration > line.begin -> duration
                    else -> line.begin + 5_000L
                }
                line.copy(
                    end = fallbackEnd,
                    duration = fallbackEnd - line.begin
                )
            }
        }
    }

    private fun parseTime(minute: String, second: String, fraction: String): Long {
        val minutes = minute.toLongOrNull() ?: 0L
        val seconds = second.toLongOrNull() ?: 0L
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100L
            2 -> (fraction.toLongOrNull() ?: 0L) * 10L
            else -> (fraction.take(3).toLongOrNull() ?: 0L)
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun metadataTrackKey(metadata: MediaMetadata): String {
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        return listOf(id, title, artist).joinToString("|")
    }
}
