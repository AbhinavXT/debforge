package com.abhinavxt.debforge.data.trakt

import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.domain.MediaKind
import com.abhinavxt.debforge.domain.ReleaseInfo
import com.abhinavxt.debforge.domain.TraktMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trakt for DebForge: device sign-in with the user's own Trakt app, scrobbling
 * from DebForge's player, and the watched history mirrored into the Library.
 *
 * Every call is best effort: Trakt being down or the user being offline never
 * gets in the way of playing a file.
 */
@Singleton
class TraktRepository @Inject constructor(
    private val api: TraktApi,
    private val store: TraktStore
) {
    /** Trakt ids by [ReleaseInfo.groupKey]; a cached null means "searched, not found". */
    private val ids = ConcurrentHashMap<String, Optional>()
    private data class Optional(val id: Long?)

    private val refreshLock = Mutex()
    private val syncLock = Mutex()

    val userFlow: Flow<String?> = store.userFlow
    val watchedFlow: Flow<Set<String>> = store.watchedFlow

    // --- sign-in ---------------------------------------------------------------

    enum class Poll { PENDING, APPROVED, EXPIRED, DENIED, FAILED }

    /** Saves the Client ID and asks Trakt for a code to show the user. */
    suspend fun startLogin(clientId: String): DeviceCode {
        store.setCredentials(clientId)
        val c = api.deviceCode(TraktDeviceCodeRequest(clientId.trim()))
        return DeviceCode(
            userCode = c.userCode,
            verificationUrl = c.verificationUrl,
            directUrl = null,
            intervalSeconds = (c.interval ?: 5).coerceIn(2, 30),
            expiresAtMillis = System.currentTimeMillis() + (c.expiresIn ?: 600) * 1000L,
            handle = c.deviceCode
        )
    }

    suspend fun poll(code: DeviceCode): Poll {
        val creds = store.credentials() ?: return Poll.FAILED
        val r = try {
            api.deviceToken(TraktDeviceTokenRequest(code.handle, creds.clientId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Poll.PENDING // a dropped connection: keep trying until the code expires
        }
        return when (r.code()) {
            200 -> {
                val token = r.body() ?: return Poll.FAILED
                store.setToken(token)
                runCatching { api.userSettings().user }.getOrNull()?.let { u ->
                    store.setUser(u.username ?: u.name.orEmpty())
                } ?: store.setUser("")
                runCatching { syncWatched(force = true) }
                Poll.APPROVED
            }
            400, 429 -> Poll.PENDING
            410 -> Poll.EXPIRED
            418 -> Poll.DENIED
            else -> Poll.FAILED // 401/404 (wrong id), 409 (code already used)
        }
    }

    suspend fun signOut() {
        store.session()?.let { s ->
            runCatching { api.revoke(TraktRevokeRequest(s.accessToken, s.credentials.clientId)) }
        }
        store.signOut()
        ids.clear()
    }

    /** Signed in, with a token that's good for a while (refreshed first if it's about to run out). */
    private suspend fun session(): TraktStore.Session? {
        val s = store.session() ?: return null
        if (s.expiresAt - System.currentTimeMillis() > REFRESH_BEFORE_MS) return s
        return refreshLock.withLock {
            val current = store.session() ?: return@withLock null
            if (current.expiresAt - System.currentTimeMillis() > REFRESH_BEFORE_MS) return@withLock current
            val refresh = current.refreshToken ?: return@withLock current
            val r = try {
                api.refresh(TraktRefreshRequest(refresh, current.credentials.clientId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock current // offline: try the old token, refresh next time
            }
            val token = r.body()
            when {
                r.isSuccessful && token != null -> {
                    store.setToken(token)
                    store.session()
                }
                // Refresh token revoked (signed out on trakt.tv, app deleted): sign out here too.
                r.code() == 400 || r.code() == 401 -> {
                    store.signOut()
                    null
                }
                else -> current
            }
        }
    }

    // --- scrobbling ----------------------------------------------------------

    enum class Action(val path: String) { START("start"), PAUSE("pause"), STOP("stop") }

    /**
     * Tells Trakt what's playing. [info] must be an episode with season and
     * number, or a movie; anything else is ignored. Returns true if Trakt took it.
     */
    suspend fun scrobble(action: Action, info: ReleaseInfo, positionMs: Long, durationMs: Long): Boolean {
        if (!store.scrobbleEnabled()) return false
        session() ?: return false
        val progress = TraktMatch.progress(positionMs, durationMs) ?: return false
        val body = when (info.kind) {
            MediaKind.SHOW -> {
                val season = info.season ?: return false
                val number = info.episode ?: return false
                val show = traktId(info) ?: return false
                TraktScrobble(
                    show = TraktMediaRef(TraktIds(trakt = show)),
                    episode = TraktEpisodeRef(season, number),
                    progress = progress,
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
            MediaKind.MOVIE -> {
                val movie = traktId(info) ?: return false
                TraktScrobble(movie = TraktMediaRef(TraktIds(trakt = movie)), progress = progress, appVersion = BuildConfig.VERSION_NAME)
            }
            MediaKind.OTHER -> return false
        }
        val ok = try {
            // 409: the same item was scrobbled moments ago (e.g. stop sent twice); fine.
            api.scrobble(action.path, body).let { it.isSuccessful || it.code() == 409 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok && action == Action.STOP && progress >= WATCHED_AT_PERCENT) {
            TraktMatch.keyOf(info)?.let { store.addWatched(it) }
        }
        return ok
    }

    /** Trakt's id for the show or movie, found by title (and year), cached for the session. */
    private suspend fun traktId(info: ReleaseInfo): Long? {
        ids[info.groupKey]?.let { return it.id }
        val type = if (info.kind == MediaKind.SHOW) "show" else "movie"
        val hits = try {
            api.search(type, info.title, info.year).ifEmpty {
                if (info.year != null) api.search(type, info.title) else emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // not cached: try again next time
        }
        val id = hits.firstNotNullOfOrNull { (if (info.kind == MediaKind.SHOW) it.show else it.movie)?.ids?.trakt }
        ids[info.groupKey] = Optional(id)
        return id
    }

    // --- watched history -----------------------------------------------------

    /**
     * Adds [infos] (episodes and movies) to the Trakt history, or removes
     * them, and updates the Library's copy right away. Returns false if not
     * signed in or Trakt couldn't be reached; titles Trakt doesn't know are
     * skipped.
     */
    suspend fun markWatched(infos: List<ReleaseInfo>, watched: Boolean): Boolean {
        session() ?: return false
        val episodes = infos.filter { it.kind == MediaKind.SHOW && it.season != null && it.episode != null }
        val movies = infos.filter { it.kind == MediaKind.MOVIE }
        val shows = episodes.groupBy { it.groupKey }.mapNotNull { (_, eps) ->
            val id = traktId(eps.first()) ?: return@mapNotNull null
            TraktHistoryShow(
                TraktIds(trakt = id),
                eps.groupBy { it.season!! }.map { (s, list) ->
                    TraktHistorySeason(s, list.map { TraktWatchedEpisode(it.episode!!) }.distinct())
                }
            )
        }
        val movieRefs = movies.distinctBy { it.groupKey }.mapNotNull { m -> traktId(m)?.let { TraktMediaRef(TraktIds(trakt = it)) } }
        if (shows.isEmpty() && movieRefs.isEmpty()) return false
        val body = TraktHistory(shows, movieRefs)
        val ok = try {
            (if (watched) api.addHistory(body) else api.removeHistory(body)).isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            val keys = infos.mapNotNull { TraktMatch.keyOf(it) }
            if (watched) keys.forEach { store.addWatched(it) } else store.removeWatched(keys)
        }
        return ok
    }

    /**
     * Mirrors Trakt's watched history into the Library. Cheap when nothing
     * changed: one request to compare Trakt's last-activity time. Runs at
     * most every [SYNC_EVERY_MS] unless [force]d.
     */
    suspend fun syncWatched(force: Boolean = false) = syncLock.withLock {
        if (!store.syncWatchedEnabled()) return@withLock
        session() ?: return@withLock
        val now = System.currentTimeMillis()
        if (!force && now - store.lastSync() < SYNC_EVERY_MS) return@withLock
        try {
            val activity = api.lastActivities().all
            if (!force && activity != null && activity == store.lastActivity()) {
                store.touchSync(now)
                return@withLock
            }
            val keys = HashSet<String>()
            api.watchedShows().forEach { w ->
                val title = w.show?.title ?: return@forEach
                w.seasons.orEmpty().forEach { s ->
                    s.episodes.orEmpty().forEach { e -> keys += TraktMatch.episodeKey(title, s.number, e.number) }
                }
            }
            api.watchedMovies().forEach { w ->
                val m = w.movie ?: return@forEach
                keys += TraktMatch.movieKeys(m.title ?: return@forEach, m.year)
            }
            store.setWatched(keys, activity, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or Trakt busy: keep the last copy, try again next time.
        }
    }

    suspend fun isSignedIn(): Boolean = store.userFlow.first() != null

    companion object {
        private const val REFRESH_BEFORE_MS = 60L * 60 * 1000
        private const val SYNC_EVERY_MS = 15L * 60 * 1000
        /** Trakt counts a stop at 80 % or more as a watch. */
        const val WATCHED_AT_PERCENT = 80.0
    }
}

/**
 * Adds Trakt's required headers (API version, the app's Client ID) and the
 * user's token. Requests that already carry Authorization are left alone.
 */
class TraktAuthInterceptor(private val store: TraktStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val (creds, session) = runBlocking { store.credentials() to store.session() }
        val b = chain.request().newBuilder()
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("User-Agent", "DebForge/${BuildConfig.VERSION_NAME}")
        creds?.let { b.header("trakt-api-key", it.clientId) }
        if (chain.request().header("Authorization") == null && session != null &&
            !chain.request().url.encodedPath.startsWith("/oauth/")
        ) {
            b.header("Authorization", "Bearer ${session.accessToken}")
        }
        return chain.proceed(b.build())
    }
}
