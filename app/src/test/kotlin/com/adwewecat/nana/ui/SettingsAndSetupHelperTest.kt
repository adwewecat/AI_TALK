package com.adwewecat.nana.ui

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.ui.settings.ApiKeyMasker
import com.adwewecat.nana.ui.settings.OriginOsSetupHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndSetupHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nana_robolectric_vault", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("nana_robolectric_vault", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test(timeout = 15_000)
    fun maskApiKeyHidesMiddleCharactersSafely() {
        assertEquals("AQ.Ab8...1234", ApiKeyMasker.mask("AQ.Ab8RN6ExampleSecretKey1234"))
        assertEquals("***", ApiKeyMasker.mask("shortkey"))
        assertEquals("***", ApiKeyMasker.mask("1234567890"))
        assertEquals("***", ApiKeyMasker.mask(""))
        assertEquals("AIzaSy...9876", ApiKeyMasker.mask("  AIzaSyDummyValueForUnitTest9876  "))
    }

    @Test(timeout = 15_000)
    fun originOsSetupHelperProvidesValidFallbackIntents() {
        val autostart = OriginOsSetupHelper.autostartIntent(context)
        val battery = OriginOsSetupHelper.batteryOptimizationIntent(context)
        val notificationListener = OriginOsSetupHelper.notificationListenerIntent()
        val accessibility = OriginOsSetupHelper.accessibilitySettingsIntent()
        val appDetails = OriginOsSetupHelper.appDetailsIntent(context)

        assertNotNull(autostart)
        assertNotNull(battery)
        assertNotNull(notificationListener)
        assertNotNull(accessibility)
        assertNotNull(appDetails)

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, notificationListener.action)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accessibility.action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetails.action)
        assertEquals("package:${context.packageName}", appDetails.data?.toString())
        assertTrue(autostart.action != null || autostart.component != null)
        assertTrue(battery.action != null || battery.component != null)
    }

    @Test(timeout = 15_000)
    fun appContainerWiresRealServicesCleanlyWithoutHardcodedKeys() = runTest {
        val container = AppContainer(context)

        assertTrue(container.vault.loadKeys().isEmpty())
        assertTrue(container.keyPool.snapshot().isEmpty())
        assertEquals(QuotaState.ACTIVE_NANA, container.quotaStateMachine.state)
        assertNotNull(container.aiChatRouter)
        assertNotNull(container.ttsRouter)
        assertNotNull(container.voiceOrchestrator)
        assertNotNull(container.syncScheduler)
        assertNotNull(container.authManager)
        assertNotNull(container.callRecordingWatcher)
        assertNotNull(container.inAppCallRecorder)
        assertNotNull(container.callStateMonitor)

        // Verify Gemini provider auto-detection on save, cooldown recovery, and Wi-Fi gate.
        val syntheticEntry = ApiKeyEntry(
            id = "test-gem-1",
            label = "Test Gemini",
            provider = ProviderType.GEMINI_AI_STUDIO,
            apiKey = "AQ.SyntheticVertexKey123456",
            modelId = "gemini-2.5-flash",
            priority = 1,
            isFreeTier = false,
        )
        container.saveApiKey(syntheticEntry)
        val saved = container.keysFlow.value.single()
        assertEquals(ProviderType.GEMINI_VERTEX_EXPRESS, saved.provider)
        assertEquals(KeyStatus.HEALTHY, saved.status)

        container.keyPool.markChatQuotaExhausted("test-gem-1")
        container.quotaStateMachine.onAllPaidChatKeysExhausted()
        assertEquals(QuotaState.NANA_TIRED, container.quotaStateMachine.state)

        container.reviveNaNaNow()
        assertEquals(QuotaState.ACTIVE_NANA, container.quotaStateMachine.state)
        assertEquals(KeyStatus.HEALTHY, container.keysFlow.value.single().status)

        assertEquals("Chưa nhập API key.", container.testApiKey(syntheticEntry.copy(apiKey = "")))
        assertEquals(SyncOutcome.PAUSED_NO_WIFI, container.runManualWifiSync())
    }
}
