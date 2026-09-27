package com.abhinavxt.debforge.diagnostics

import android.content.Context
import android.os.Build
import com.abhinavxt.debforge.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reports. Nothing leaves the device on its own: when the app
 * crashes we write a redacted text report to app storage, and on the next
 * launch the user is asked whether to share it (share sheet — email, GitHub,
 * chat, whatever they pick) or delete it. No SDK, no server, no analytics.
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    /** Call once from Application.onCreate, before anything else can crash. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let the reporter itself mask the real crash.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report, if the last session crashed and the user hasn't dealt with it. */
    fun pending(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("DebForge crash report")
            appendLine("Time:    " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread:  ${thread.name}")
            appendLine()
            // Stack traces are capped: a 200-line cause chain is no more useful
            // than 120 and makes the share text unwieldy.
            append(Redactor.redact(trace).lineSequence().take(MAX_LINES).joinToString("\n"))
        }
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(report)
    }

    private const val MAX_LINES = 120

}
