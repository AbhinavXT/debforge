package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.remote.RealDebridApi
import com.abhinavxt.debforge.data.remote.dto.UserDto
import com.abhinavxt.debforge.domain.DataResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: RealDebridApi,
    private val tokenStore: TokenStore
) {
    /** True once a non-blank token has been saved (not necessarily validated). */
    val hasToken: Flow<Boolean> = tokenStore.tokenFlow.map { !it.isNullOrBlank() }

    /**
     * Validates a candidate token against /user WITHOUT persisting it. Only
     * stores the token after RD confirms it's valid — this avoids a race where
     * a transient setToken would flip the auth gate to Authenticated and bounce
     * the user into Home for a moment before validation failed.
     */
    suspend fun saveAndValidate(token: String): DataResult<UserDto> =
        withContext(Dispatchers.IO) {
            val trimmed = token.trim()
            try {
                val user = api.validateUser("Bearer $trimmed")
                tokenStore.setToken(trimmed)
                DataResult.Success(user)
            } catch (e: HttpException) {
                val msg = if (e.code() == 401) {
                    "Invalid token — check it at real-debrid.com/apitoken"
                } else {
                    "Real-Debrid error (HTTP ${e.code()})"
                }
                DataResult.Error(msg, e)
            } catch (e: IOException) {
                DataResult.Error("Network error — check your connection", e)
            }
        }

    suspend fun signOut() = tokenStore.clearToken()
}
