package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false
    if (startupRetryCount >= MAX_STARTUP_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            403 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
            410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
            500, 502, 503 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    // Check for codec/renderer errors
    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(com.nuvio.tv.R.string.player_error_decoder)
        val unsupported = context.getString(com.nuvio.tv.R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

private inline fun <reified T : Throwable> Throwable.findCauseOfType(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * When decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON) and the decoder
 * fails to initialise, this is often caused by Dolby Vision profile 7
 * content on devices without a DV decoder.  Enabling the DV7-to-HEVC
 * mapping allows the HEVC decoder to handle the stream instead.
 *
 * Unlike the PCM fallback this requires a full player rebuild because
 * the mapping is baked into the renderers factory at build time.
 * Tunneling state does not matter for this fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // Skip if DV7-to-HEVC is already active — nothing more we can do.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Show loading overlay with fallback info instead of error screen.
    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Computes a STABLE identity key for a stream so a failed source can be
 * excluded even after it lazily resolves to a concrete URL.
 *
 * Torrent and direct-debrid sources expose no playable URL until they are
 * resolved, and the debrid resolver preserves the infoHash / clientResolve
 * identity across that resolve (only url/externalUrl/behaviorHints change).
 * We therefore key on that preserved identity first and fall back to the
 * concrete URL only for plain HTTP streams that carry no torrent/debrid id.
 */
internal fun Stream.stableFailureKey(): String {
    val resolve = clientResolve
    val identity = infoHash
        ?: resolve?.infoHash
        ?: resolve?.magnetUri
        ?: resolve?.torrentName
        ?: resolve?.filename
    if (!identity.isNullOrBlank()) {
        val fileIdxPart = (fileIdx ?: resolve?.fileIdx)?.toString().orEmpty()
        val filenamePart = resolve?.filename.orEmpty().trim().lowercase()
        return "id|${identity.trim().lowercase()}|$fileIdxPart|$filenamePart"
    }
    getStreamUrl()?.takeIf { it.isNotBlank() }?.let { return "url|$it" }
    return "key|${stableKey()}"
}

/**
 * Whether [stream] is a viable fallback in [tryNextStream]: playable, not
 * external, and neither its concrete URL nor its stable identity has already
 * failed.
 */
internal fun PlayerRuntimeController.isPlayableFallbackCandidate(stream: Stream): Boolean {
    val streamUrl = stream.getStreamUrl()
    val isPlayable = !streamUrl.isNullOrBlank() || stream.isTorrent() || stream.isDirectDebrid()
    val notFailedByUrl = streamUrl.isNullOrBlank() || streamUrl !in failedStreamUrls
    val notFailedByKey = stream.stableFailureKey() !in failedStreamKeys
    return isPlayable && notFailedByUrl && notFailedByKey && !stream.isExternal()
}

/**
 * Attempts to switch to the next available stream that hasn't already failed.
 *
 * Marks the current source as failed — by concrete URL AND by stable identity,
 * so lazily-resolved torrent/direct-debrid sources (which have no URL yet) are
 * not re-picked — then searches the source list for the first still-playable
 * stream. If the source list hasn't loaded yet it re-scrapes asynchronously and
 * keeps the recovery overlay up, surfacing [errorMessage] only if that also
 * finds nothing.
 *
 * Returns `true` if a switch was initiated or an async recovery is in flight,
 * `false` if the source list is loaded but exhausted (caller shows the error).
 */
internal fun PlayerRuntimeController.tryNextStream(errorMessage: String? = null): Boolean {
    // Mark the current source as failed by both concrete URL and stable identity.
    val currentUrl = currentStreamUrl
    if (currentUrl.isNotBlank()) {
        failedStreamUrls.add(currentUrl)
    }
    currentSourceStream?.let { failedStreamKeys.add(it.stableFailureKey()) }

    // Search available source streams for the first non-failed, playable stream
    val allStreams = _uiState.value.sourceAllStreams
    val nextStream = allStreams.firstOrNull { isPlayableFallbackCandidate(it) }

    if (nextStream != null) {
        Log.w(
            PlayerRuntimeController.TAG,
            "tryNextStream: switching to next stream (failed ${failedStreamKeys.size} so far)"
        )
        resetErrorRetryState()
        _uiState.update { it.copy(error = null) }
        switchToSourceStream(nextStream)
        return true
    }

    // If we don't have source streams loaded yet, re-scrape and retry. Keep the
    // recovery overlay visible and report recovery-in-progress so the caller
    // does not flash the error overlay while the async load runs.
    if (allStreams.isEmpty()) {
        Log.w(
            PlayerRuntimeController.TAG,
            "tryNextStream: no source streams available, loading them"
        )
        showRecoveryOverlay()
        scope.launch {
            loadSourceStreams(forceRefresh = false)
            val fallback = _uiState.value.sourceAllStreams
                .firstOrNull { isPlayableFallbackCandidate(it) }
            if (fallback != null) {
                resetErrorRetryState()
                _uiState.update { it.copy(error = null) }
                switchToSourceStream(fallback)
            } else {
                // Recovery truly failed — now surface the error to the user.
                _uiState.update {
                    it.copy(
                        error = errorMessage
                            ?: context.getString(R.string.player_error_playback_fallback),
                        showLoadingOverlay = false,
                        showPauseOverlay = false
                    )
                }
            }
        }
        return true
    }

    Log.w(
        PlayerRuntimeController.TAG,
        "tryNextStream: no more streams available (failed ${failedStreamKeys.size})"
    )
    return false
}

