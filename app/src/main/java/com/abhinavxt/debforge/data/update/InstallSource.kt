package com.abhinavxt.debforge.data.update

import android.content.Context

/**
 * Which app installed DebForge. The APK is byte-identical on GitHub, F-Droid
 * and Obtainium, so this runtime check (not a build flavour) decides whether
 * our own update check is useful or would just duplicate the store's.
 */
object InstallSource {

    /** App stores and updaters that already keep DebForge up to date. */
    private val UPDATERS = setOf(
        // F-Droid and its clients
        "org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged",
        "com.looker.droidify", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic",
        // Obtainium
        "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid",
        // Google Play, in case the app is ever published there
        "com.android.vending"
    )

    // The installer can only change by reinstalling, which restarts the
    // process, so one binder call per process is enough.
    @Volatile private var cached: String? = null
    @Volatile private var resolved = false

    fun installer(context: Context): String? {
        if (!resolved) {
            cached = runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
            resolved = true
        }
        return cached
    }

    /** True when a store/updater installed us, so it will also deliver updates. */
    fun managedByUpdater(context: Context): Boolean = installer(context) in UPDATERS
}
