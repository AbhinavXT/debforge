package com.abhinavxt.debforge.data.update

import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.di.ApiHttpClient
import com.abhinavxt.debforge.domain.AppVersion
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class GhRelease(
    @Json(name = "tag_name") val tagName: String,
    @Json(name = "html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GhAsset(
    val name: String,
    @Json(name = "browser_download_url") val downloadUrl: String,
    val size: Long = 0
)

data class UpdateInfo(
    /** "1.4.0" (tag without the v). */
    val version: String,
    val tag: String,
    /** Release page on GitHub — always works, shows notes + all assets. */
    val pageUrl: String,
    /** Direct APK link when the release has one. */
    val apkUrl: String?,
    val notes: String
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * Checks GitHub Releases for a newer version. No account, no tracking: one
 * anonymous GET to api.github.com, at most once per app launch unless the user
 * taps "Check now". Installing is left to the browser/package installer, so the
 * app needs no REQUEST_INSTALL_PACKAGES permission.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApiHttpClient private val client: OkHttpClient,
    moshi: Moshi,
    private val settings: SettingsStore
) {
    private val adapter = moshi.adapter(GhRelease::class.java)
    private val mutex = Mutex()
    private var checkedThisLaunch = false

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val repo: String get() = BuildConfig.GITHUB_REPO

    /** Automatic check: respects the setting and runs once per launch. */
    suspend fun checkIfDue() {
        if (!settings.autoUpdateCheckFlow.first()) return
        val firstTime = mutex.withLock { (!checkedThisLaunch).also { checkedThisLaunch = true } }
        if (firstTime) check()
    }

    suspend fun check() {
        if (_status.value == UpdateStatus.Checking) return
        _status.value = UpdateStatus.Checking
        _status.value = try {
            val release = fetchLatest()
            val remote = release?.tagName?.removePrefix("v")
            if (release == null || remote == null || !AppVersion.isNewer(remote, currentVersion)) {
                UpdateStatus.UpToDate
            } else {
                UpdateStatus.Available(
                    UpdateInfo(
                        version = remote,
                        tag = release.tagName,
                        pageUrl = release.htmlUrl,
                        apkUrl = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                            ?.downloadUrl,
                        notes = release.body.orEmpty().trim()
                    )
                )
            }
        } catch (e: IOException) {
            UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: com.squareup.moshi.JsonDataException) {
            UpdateStatus.Failed("unexpected response")
        }
    }

    /** Null when the repo has no published release yet (404). */
    private suspend fun fetchLatest(): GhRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DebForge/$currentVersion")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code}")
                else -> {
                    val body = resp.body?.string() ?: throw IOException("empty response")
                    adapter.fromJson(body)?.takeUnless { it.draft || it.prerelease }
                }
            }
        }
    }
}
