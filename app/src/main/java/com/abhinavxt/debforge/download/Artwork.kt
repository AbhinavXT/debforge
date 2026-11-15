package com.abhinavxt.debforge.download

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Where a download's thumbnail lives; the path is derived from the id, so no column is needed. */
fun artworkFile(context: Context, id: String): File {
    val sha1 = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(context.filesDir, "artwork/$sha1.jpg")
}
