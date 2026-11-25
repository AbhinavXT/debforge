package com.abhinavxt.debforge.data.repository

import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.ProviderRegistry
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.data.provider.local.LocalFilesProvider
import com.abhinavxt.debforge.data.provider.DeviceCode
import com.abhinavxt.debforge.data.provider.torbox.TbDeviceTokenDto
import com.abhinavxt.debforge.data.provider.torbox.TorBoxApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxDeviceApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbUserDto
import com.abhinavxt.debforge.domain.DataResult
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AuthRepositoryDeviceLoginTest {

    private var accepted = false
    private var validations = 0

    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, m, _ ->
        check(m.name == "validateUser") { m.name }
        validations++
        TbEnvelope(accepted, null, "refused", TbUserDto(1, "me@example.com", 2, null))
    } as TorBoxApi

    private val deviceApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxDeviceApi::class.java)) { _, m, _ ->
        check(m.name == "token") { m.name }
        TbEnvelope(true, null, null, TbDeviceTokenDto("new-token"))
    } as TorBoxDeviceApi

    @After fun tearDown() {
        TokenCipher.testKey = null
    }

    @Test fun storedTokenIsReplacedOnlyOnceTheNewOneIsValidated() = runBlocking {
        TokenCipher.testKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val context = RuntimeEnvironment.getApplication()
        val tokens = TokenStore(context)
        tokens.setToken(ProviderId.TORBOX, "old-token")
        val auth = AuthRepository(
            ProviderRegistry(
                setOf(TorBoxProvider(api, deviceApi, tokens)),
                DirectLinks(context, OkHttpClient()),
                LocalFilesProvider()
            ),
            tokens
        )
        val code = DeviceCode("1", "u", null, 0, Long.MAX_VALUE, "dev")

        assertTrue(auth.pollDeviceLogin(ProviderId.TORBOX, code) is DataResult.Error)
        assertEquals("old-token", tokens.token(ProviderId.TORBOX))

        accepted = true
        validations = 0
        assertTrue(auth.pollDeviceLogin(ProviderId.TORBOX, code) is DataResult.Success)
        assertEquals("new-token", tokens.token(ProviderId.TORBOX))
        assertEquals("a second check could lose the token", 1, validations)
    }
}
