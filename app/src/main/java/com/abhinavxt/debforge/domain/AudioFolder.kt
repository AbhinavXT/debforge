package com.abhinavxt.debforge.domain

/** Where audio files go when the library is organised: "Audio", beside "Movies" and "Shows". */
object AudioFolder {
    const val NAME = "Audio"

    private val EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "alac", "ape", "mka", "weba")

    fun isAudioName(filename: String): Boolean {
        val dot = filename.lastIndexOf('.')
        return dot >= 0 && filename.substring(dot + 1).lowercase() in EXTENSIONS
    }

    /** "Audio" for an audio file when [organize] is on, otherwise "" (no audio folder). */
    fun subfolderFor(filename: String, organize: Boolean): String =
        if (organize && isAudioName(filename)) NAME else ""
}
