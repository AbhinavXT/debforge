package com.abhinavxt.debforge.player

import android.net.Uri
import com.abhinavxt.debforge.domain.MkvChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Chapters of a Matroska file being played (Media3 doesn't read them). Empty for anything else. */
@Singleton
class ChapterReader @Inject constructor(private val ranges: RangeReader) {

    suspend fun chapters(uri: Uri, filename: String): List<MkvChapters.Chapter> {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext != "mkv" && ext != "webm") return emptyList()
        return withContext(Dispatchers.IO) {
            MkvChapters.read { offset, length -> ranges.read(uri, offset, length)?.bytes }
        }
    }
}
