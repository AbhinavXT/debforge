package com.abhinavxt.debforge.domain

import java.util.Locale

enum class FileKind { Video, Audio, File }

private val AUDIO = setOf("m4a", "aac", "mp3", "flac", "ogg", "opus", "wav", "weba")
private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "ts")

/** Which Media segment a downloaded file belongs to; anything unknown is [FileKind.File]. */
fun kindOf(filename: String): FileKind {
    val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        in AUDIO -> FileKind.Audio
        in VIDEO -> FileKind.Video
        else -> FileKind.File
    }
}
