package com.abhinavxt.debforge.data.subtitles

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.abhinavxt.debforge.di.DownloadHttpClient
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.openSubtitlesDataStore by preferencesDataStore(name = "debforge_opensubtitles")

/** One subtitle OpenSubtitles offers for the file playing. */
data class OnlineSubtitle(
    val fileId: Long,
    /** ISO 639-1 where OpenSubtitles has one ("en", "pt-BR"). */
    val language: String?,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    /** Found by the file's hash: timed for exactly this release. */
    val hashMatch: Boolean
)

/**
 * The user's OpenSubtitles API key (and, optionally, account for the bigger
 * daily download allowance), encrypted with the Keystore key like service
 * tokens. Not part of backups.
 */
@Singleton
class OpenSubtitlesStore @Inject constructor(private val context: Context) {
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyUser = stringPreferencesKey("username")
    private val keyPassword = stringPreferencesKey("password")

    private fun Preferences.secret(key: Preferences.Key<String>): String? =
        this[key]?.let(TokenCipher::decrypt)?.takeIf { it.isNotBlank() }

    data class Account(val username: String, val password: String)

    val apiKeyFlow: Flow<String?> = context.openSubtitlesDataStore.data.map { it.secret(keyApiKey) }.distinctUntilChanged()
    val usernameFlow: Flow<String?> = context.openSubtitlesDataStore.data.map { it.secret(keyUser) }.distinctUntilChanged()

    suspend fun apiKey(): String? = apiKeyFlow.first()

    suspend fun account(): Account? {
        val p = context.openSubtitlesDataStore.data.first()
        return Account(p.secret(keyUser) ?: return null, p.secret(keyPassword) ?: return null)
    }

    /** Blank [username] or [password] removes the account (the key alone still works). */
    suspend fun save(apiKey: String, username: String, password: String) = context.openSubtitlesDataStore.edit {
        it[keyApiKey] = TokenCipher.encrypt(apiKey.trim())
        if (username.isBlank() || password.isEmpty()) {
            it.remove(keyUser); it.remove(keyPassword)
        } else {
            it[keyUser] = TokenCipher.encrypt(username.trim())
            it[keyPassword] = TokenCipher.encrypt(password)
        }
    }

    suspend fun clear() = context.openSubtitlesDataStore.edit { it.clear() }
}

/**
 * Finds subtitles on OpenSubtitles for a file without any, and saves the
 * one picked into the app's cache, so it outlives the temporary link and
 * the stream's own link refreshes.
 */
@Singleton
class OpenSubtitlesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OpenSubtitlesApi,
    private val store: OpenSubtitlesStore,
    @DownloadHttpClient private val http: OkHttpClient
) {
    val enabled: Flow<Boolean> = store.apiKeyFlow.map { it != null }

    /** Bearer token after logging in with the user's account; null = anonymous. */
    private var token: String? = null
    private val loginLock = Mutex()

    enum class KeyCheck { VALID, REJECTED, BAD_LOGIN, UNREACHABLE }

    class QuotaException : IOException("daily download limit reached")

    /** Checks a key (and account, if given) before saving it. */
    suspend fun check(apiKey: String, username: String, password: String): KeyCheck = try {
        val r = api.search(query = "test", languages = "en", apiKeyOverride = apiKey.trim())
        when {
            r.code() == 401 || r.code() == 403 -> KeyCheck.REJECTED
            !r.isSuccessful -> KeyCheck.UNREACHABLE
            username.isBlank() || password.isEmpty() -> KeyCheck.VALID
            else -> {
                val login = api.login(OsLoginRequest(username.trim(), password), apiKeyOverride = apiKey.trim())
                when {
                    login.isSuccessful && login.body()?.token != null -> KeyCheck.VALID
                    login.code() == 401 || login.code() == 400 -> KeyCheck.BAD_LOGIN
                    else -> KeyCheck.UNREACHABLE
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KeyCheck.UNREACHABLE
    }

    suspend fun save(apiKey: String, username: String, password: String) {
        store.save(apiKey, username, password)
        token = null
    }

    suspend fun remove() {
        store.clear()
        token = null
    }

    /**
     * Subtitles for [info] in [languages] (empty = any), best first: matches
     * by [movieHash], then the most downloaded. A hash with no results is
     * retried as a plain title search.
     */
    suspend fun search(info: ReleaseInfo, movieHash: String?, languages: List<String>): List<OnlineSubtitle> {
        val langs = languages.map { it.lowercase() }.distinct().sorted().joinToString(",").ifEmpty { null }
        val isShow = info.kind == MediaKind.SHOW
        suspend fun query(hash: String?): List<OsSubtitle> {
            val r = api.search(
                episode = info.episode.takeIf { isShow },
                languages = langs,
                movieHash = hash,
                query = info.title.lowercase(),
                season = info.season.takeIf { isShow },
                type = when (info.kind) {
                    MediaKind.SHOW -> "episode"
                    MediaKind.MOVIE -> "movie"
                    MediaKind.OTHER -> null
                },
                year = info.year.takeIf { info.kind == MediaKind.MOVIE }
            )
            if (!r.isSuccessful) throw IOException("OpenSubtitles: HTTP ${r.code()}")
            return r.body()?.data.orEmpty()
        }
        val found = query(movieHash).ifEmpty { if (movieHash != null) query(null) else emptyList() }
        return found.mapNotNull { s ->
            val a = s.attributes ?: return@mapNotNull null
            val file = a.files?.firstOrNull() ?: return@mapNotNull null
            OnlineSubtitle(
                fileId = file.fileId,
                language = a.language,
                release = a.release?.takeIf { it.isNotBlank() } ?: file.fileName.orEmpty(),
                downloads = a.downloadCount ?: 0,
                hearingImpaired = a.hearingImpaired == true,
                hashMatch = a.movieHashMatch == true
            )
        }
            .distinctBy { it.fileId }
            .sortedWith(compareByDescending<OnlineSubtitle> { it.hashMatch }.thenByDescending { it.downloads })
            .take(MAX_RESULTS)
    }

    /** Downloads [sub] into the cache (once; picked again it's already there). */
    suspend fun download(sub: OnlineSubtitle): File {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val file = File(dir, "os-${sub.fileId}.srt")
        if (file.length() > 0) return file
        var r = api.download(OsDownloadRequest(sub.fileId), bearer())
        if (r.code() == 401 && token != null) {
            token = null // expired: log in again once
            r = api.download(OsDownloadRequest(sub.fileId), bearer())
        }
        if (r.code() == 406) throw QuotaException()
        val link = r.body()?.link ?: throw IOException("OpenSubtitles: HTTP ${r.code()}")
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(link).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val tmp = File(dir, file.name + ".part")
                (resp.body ?: throw IOException("empty")).byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(file)) throw IOException("can't save subtitle")
            }
        }
        return file
    }

    private suspend fun bearer(): String? = loginLock.withLock {
        token?.let { return@withLock "Bearer $it" }
        val account = store.account() ?: return@withLock null
        val r = runCatching { api.login(OsLoginRequest(account.username, account.password)) }.getOrNull()
        token = r?.body()?.token
        token?.let { "Bearer $it" }
    }

    companion object {
        private const val MAX_RESULTS = 40
    }
}

/**
 * Adds the API key and the User-Agent OpenSubtitles requires. A request
 * carrying its own key (one being checked before it's saved) keeps it.
 */
class OpenSubtitlesInterceptor(private val store: OpenSubtitlesStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val b = req.newBuilder()
            .header("User-Agent", "DebForge v${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
        if (req.header(API_KEY) == null) {
            runBlocking { store.apiKey() }?.let { b.header(API_KEY, it) }
        }
        return chain.proceed(b.build())
    }

    companion object {
        const val API_KEY = "Api-Key"
    }
}
