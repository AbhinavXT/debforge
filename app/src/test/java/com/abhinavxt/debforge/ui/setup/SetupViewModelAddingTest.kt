package com.abhinavxt.debforge.ui.setup

import com.abhinavxt.debforge.data.prefs.TokenCipher
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.data.provider.ProviderRegistry
import com.abhinavxt.debforge.data.provider.direct.DirectLinks
import com.abhinavxt.debforge.data.provider.local.LocalFilesProvider
import com.abhinavxt.debforge.data.provider.torbox.TbDeviceCodeDto
import com.abhinavxt.debforge.data.provider.torbox.TorBoxApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxDeviceApi
import com.abhinavxt.debforge.data.provider.torbox.TorBoxProvider
import com.abhinavxt.debforge.data.repository.AuthRepository
import com.abhinavxt.debforge.data.provider.torbox.dto.TbEnvelope
import com.abhinavxt.debforge.data.provider.torbox.dto.TbUserDto
import com.abhinavxt.debforge.domain.ProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.lang.reflect.Proxy
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SetupViewModelAddingTest {

    private lateinit var tokens: TokenStore
    private lateinit var auth: AuthRepository
    private var pollCalls = 0

    @Before fun setUp() {
        TokenCipher.testKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val context = RuntimeEnvironment.getApplication()
        tokens = TokenStore(context)
        val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxApi::class.java)) { _, _, _ ->
            TbEnvelope(true, null, null, TbUserDto(1, "me@example.com", 2, null))
        } as TorBoxApi
        val deviceApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TorBoxDeviceApi::class.java)) { _, m, _ ->
            if (m.name == "start") TbEnvelope(true, null, null, TbDeviceCodeDto(deviceCode = "d", code = "C", interval = 1, expiresIn = 600))
            else { pollCalls++; TbEnvelope(false, null, "not yet", null) }
        } as TorBoxDeviceApi
        auth = AuthRepository(
            ProviderRegistry(setOf(TorBoxProvider(api, deviceApi, tokens)), DirectLinks(context, OkHttpClient()), LocalFilesProvider()),
            tokens
        )
    }

    @After fun tearDown() {
        TokenCipher.testKey = null
    }

    /** viewModelScope runs on Main (Robolectric looper); the store writes on IO. Waits up to 5s for [cond]. */
    private fun until(cond: () -> Boolean) {
        repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (cond()) return; Thread.sleep(25) }
    }

    private fun settle() { repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }

    private suspend fun addingVm(): SetupViewModel {
        tokens.setToken(ProviderId.TORBOX, "old-token")
        return SetupViewModel(auth).apply { adding = true }.also { settle() } // initial "follow the active service" lands first
    }

    @Test fun addingKeepsTheActiveServiceUntilSignInSucceeds() = runBlocking {
        val vm = addingVm()
        vm.onProviderSelected(ProviderId.DIRECT) // stands in for a service with no token
        settle()
        assertEquals(ProviderId.DIRECT, vm.state.value.selected)
        assertEquals(ProviderId.TORBOX, auth.activeProvider.first())
        assertFalse(vm.done.value)
    }

    @Test fun notAddingPersistsTheChoiceAsBefore() = runBlocking {
        tokens.setToken(ProviderId.TORBOX, "old-token")
        val vm = SetupViewModel(auth)
        settle()
        vm.onProviderSelected(ProviderId.DIRECT)
        until { runBlocking { auth.activeProvider.first() } == ProviderId.DIRECT }
        assertEquals(ProviderId.DIRECT, auth.activeProvider.first())
        assertFalse(vm.done.value)
    }

    @Test fun successfulSignInWhileAddingSignalsDone() = runBlocking {
        val vm = addingVm()
        vm.onTokenChanged("a-new-valid-token")
        vm.submit()
        until { vm.done.value }
        assertTrue(vm.done.value)
        vm.doneHandled()
        assertFalse(vm.done.value)
    }

    @Test fun tappingAServiceWithATokenWhileAddingSignalsDone() = runBlocking {
        val vm = addingVm()
        vm.onProviderSelected(ProviderId.TORBOX) // the pre-selected, already-active service
        until { vm.done.value }
        assertTrue(vm.done.value)
        assertEquals(ProviderId.TORBOX, auth.activeProvider.first())
    }

    @Test fun leavingCancelsDeviceLoginAndResetsAddingState() = runBlocking {
        val vm = addingVm()
        vm.onProviderSelected(ProviderId.DIRECT)
        vm.onTokenChanged("half-typed")
        vm.onProviderSelected(ProviderId.TORBOX) // back to the preselected one; also done, ignored here
        vm.startDeviceLogin()
        until { vm.state.value.deviceCode != null }
        assertTrue(vm.state.value.deviceCode != null)

        vm.onLeave()
        val before = pollCalls
        Thread.sleep(1500); settle()

        assertEquals(null, vm.state.value.deviceCode)
        assertEquals("", vm.state.value.token)
        assertEquals(ProviderId.TORBOX, vm.state.value.selected)
        assertEquals("poll must stop after leaving", before, pollCalls)
    }
}
