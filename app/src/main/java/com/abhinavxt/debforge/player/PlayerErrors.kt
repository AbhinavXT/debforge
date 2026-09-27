package com.abhinavxt.debforge.player

import androidx.media3.common.PlaybackException

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
}
