package com.abhinavxt.debforge.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Sorts playback errors into what the player should do about them:
 *  - UNSUPPORTED: this device can't decode or read the file. Offer VLC & co.
 *  - NETWORK: the stream broke off or the link expired. Offer to retry.
 *  - OTHER: anything else. Offer VLC & co. too; they may cope.
 */
object PlayerErrors {

    enum class Kind { UNSUPPORTED, NETWORK, OTHER }

    private val UNSUPPORTED = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    )

    private val NETWORK = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT
    )

    fun kindOf(errorCode: Int): Kind = when (errorCode) {
        in UNSUPPORTED -> Kind.UNSUPPORTED
        in NETWORK -> Kind.NETWORK
        else -> Kind.OTHER
    }

    /** What a YouTube stream error means for the player. */
    enum class YouTubeFailure {
        /** YouTube refused the link (403/410): look the video up again. */
        LINK_REFUSED,
        /** Connection lost or timed out, or a server hiccup: wait for the network and retry. */
        NETWORK,
        /** This device can't decode the chosen format. */
        DECODER,
        OTHER
    }

    /** [httpStatus] is the response code of a failed request, or null. Only 403 and 410 count as YouTube refusing the link. */
    fun youTubeFailure(errorCode: Int, httpStatus: Int?): YouTubeFailure = when (kindOf(errorCode)) {
        Kind.UNSUPPORTED -> YouTubeFailure.DECODER
        Kind.NETWORK ->
            if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && (httpStatus == 403 || httpStatus == 410)) YouTubeFailure.LINK_REFUSED
            else YouTubeFailure.NETWORK
        Kind.OTHER -> YouTubeFailure.OTHER
    }

    /** The HTTP status behind [error], or null when it was not a bad response. */
    @OptIn(UnstableApi::class)
    fun httpStatusOf(error: Throwable): Int? {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }
}
