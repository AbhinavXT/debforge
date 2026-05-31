package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.remote.RealDebridApi
import com.abhinavxt.debforge.data.remote.dto.DownloadDto
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadsRepository @Inject constructor(
    private val api: RealDebridApi
) {
    /**
     * Fetches one page of the RD downloads list. Page-based pagination only
     * (never combined with offset). An empty list signals the last page.
     */
    suspend fun getDownloadsPage(page: Int, limit: Int = DEFAULT_PAGE_SIZE): DataResult<List<DownloadItem>> =
        withContext(Dispatchers.IO) {
            try {
                // RD returns HTTP 204 when the list is empty (and 404 past the
                // end of pages on some accounts) — both map to an empty list.
                val items = api.getDownloads(page = page, limit = limit)
                    .orEmpty()
                    .map { it.toDomain() }
                DataResult.Success(items)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    DataResult.Success(emptyList())
                } else {
                    DataResult.Error("Failed to load downloads (HTTP ${e.code()})", e)
                }
            } catch (e: IOException) {
                DataResult.Error("Network error — check your connection", e)
            } catch (e: Throwable) {
                // Belt-and-braces: anything we didn't anticipate (parser errors,
                // unexpected null bodies on other endpoints, etc.) becomes a
                // surfaced error instead of a process crash.
                DataResult.Error("Unexpected error loading downloads", e)
            }
        }

    /**
     * Re-unrestricts an original hoster link to get a fresh direct URL.
     * Used by the engine when a stored download URL has died. Returns the new
     * URL plus the authoritative chunk count for the fresh link.
     */
    suspend fun refreshDownloadUrl(originalLink: String): DataResult<RefreshedLink> =
        withContext(Dispatchers.IO) {
            try {
                val r = api.unrestrictLink(originalLink)
                DataResult.Success(RefreshedLink(url = r.download, chunks = r.chunks, filesize = r.filesize))
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: HttpException) {
                DataResult.Error("Couldn't refresh link (HTTP ${e.code()})", e)
            } catch (e: IOException) {
                DataResult.Error("Network error while refreshing link", e)
            } catch (e: Throwable) {
                DataResult.Error("Unexpected error refreshing link", e)
            }
        }

    data class RefreshedLink(val url: String, val chunks: Int, val filesize: Long)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

private fun DownloadDto.toDomain() = DownloadItem(
    id = id,
    filename = filename,
    originalLink = link,
    downloadUrl = download,
    host = host,
    filesize = filesize,
    chunks = chunks.coerceAtLeast(1),
    streamable = (streamable ?: 0) == 1,
    generated = generated
)
