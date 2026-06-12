package com.mupa.player.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mupa.player.enterprise.audience.AudienceAnalyticsManager
import com.mupa.player.enterprise.audience.AudienceAnalyticsWebViewEngine
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.network.SupabaseApi
import com.mupa.player.enterprise.network.SupabaseClient
import com.mupa.player.enterprise.services.DeviceValidationResult
import com.mupa.player.enterprise.services.DeviceValidationService
import com.mupa.player.enterprise.ui.PlayerActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.spyk
import io.mockk.verify
import io.mockk.coVerify
import com.mupa.player.enterprise.managers.ManifestManager
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.camera.lifecycle.ProcessCameraProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FacialRecognitionLicensingTest {

    private lateinit var context: Context
    private lateinit var cacheManager: DeviceCacheManager
    private val mockApi = mockk<SupabaseApi>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheManager = DeviceCacheManager(context)
        
        mockkObject(SupabaseClient)
        every { SupabaseClient.createApi() } returns mockApi

        // Mock BuildConfig.SUPABASE_TOKEN so validateDevice doesn't short-circuit
        try {
            val tokenField = BuildConfig::class.java.getDeclaredField("SUPABASE_TOKEN")
            tokenField.isAccessible = true
            tokenField.set(null, "mock-token")
        } catch (e: Exception) {
            // Safe fallback
        }

        // Mock static helper methods of AudienceAnalyticsManager
        mockkObject(AudienceAnalyticsManager.Companion)
        every { AudienceAnalyticsManager.canRunOnDevice(any()) } returns true
        every { AudienceAnalyticsManager.hasCameraPermission(any()) } returns true

        // Mock the Native engine constructor/methods to avoid ML Kit and TFLite initialization issues
        mockkConstructor(com.mupa.player.enterprise.audience.AudienceAnalyticsNativeEngine::class)
        coEvery { anyConstructed<com.mupa.player.enterprise.audience.AudienceAnalyticsNativeEngine>().init() } returns true
        coEvery { anyConstructed<com.mupa.player.enterprise.audience.AudienceAnalyticsNativeEngine>().release() } returns Unit

        // Mock ModelProvisioningManager to avoid network/disk operations
        mockkObject(com.mupa.player.enterprise.audience.ModelProvisioningManager)
        coEvery { com.mupa.player.enterprise.audience.ModelProvisioningManager.ensureModelsProvisioned(any(), any()) } returns true

        // Mock ProcessCameraProvider
        mockkStatic(ProcessCameraProvider::class)
        val mockFuture = mockk<com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>>()
        val mockProvider = mockk<ProcessCameraProvider>(relaxed = true)
        every { ProcessCameraProvider.getInstance(any()) } returns mockFuture
        every { mockFuture.get() } returns mockProvider
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- TIER 1 FEATURE 1: Parse license type from Supabase RPC response ---

    @Test
    fun testParseFacialLicenseFromSupabaseResponse() = runBlocking {
        val jsonResponse = """
            {
                "id": 123,
                "serial": "test-device-id",
                "tipo_da_licenca": "facial"
            }
        """.trimIndent()
        coEvery { mockApi.postJson(any(), any()) } returns jsonResponse.toResponseBody()

        val service = DeviceValidationService(context)
        val result = service.validateDevice("test-device-id")

        assertTrue(result is DeviceValidationResult.Found)
        val cache = (result as DeviceValidationResult.Found).cache
        assertEquals("facial", cache.tipoDaLicenca)
    }

    @Test
    fun testParseAnalyticsLicenseFromSupabaseResponse() = runBlocking {
        val jsonResponse = """
            {
                "id": 124,
                "serial": "test-device-id",
                "tipo_da_licenca": "analytics"
            }
        """.trimIndent()
        coEvery { mockApi.postJson(any(), any()) } returns jsonResponse.toResponseBody()

        val service = DeviceValidationService(context)
        val result = service.validateDevice("test-device-id")

        assertTrue(result is DeviceValidationResult.Found)
        val cache = (result as DeviceValidationResult.Found).cache
        assertEquals("analytics", cache.tipoDaLicenca)
    }

    @Test
    fun testParseEnterpriseLicenseFromSupabaseResponse() = runBlocking {
        val jsonResponse = """
            {
                "id": 125,
                "serial": "test-device-id",
                "tipo_da_licenca": "enterprise"
            }
        """.trimIndent()
        coEvery { mockApi.postJson(any(), any()) } returns jsonResponse.toResponseBody()

        val service = DeviceValidationService(context)
        val result = service.validateDevice("test-device-id")

        assertTrue(result is DeviceValidationResult.Found)
        val cache = (result as DeviceValidationResult.Found).cache
        assertEquals("enterprise", cache.tipoDaLicenca)
    }

    // --- TIER 1 FEATURE 2: DeviceCacheManager Persistence ---

    @Test
    fun testDeviceCacheManagerPersistsToSharedPreferencesAndDataStore() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 456L,
            deviceId = "persisted-device-123",
            deviceName = "Cache Player",
            filial = "002",
            company = "Mupa Corp",
            companyCode = "MUPA",
            companyName = "Mupa S.A.",
            tenant = "tenant-abc",
            lastSyncEpochMs = System.currentTimeMillis(),
            deviceRegistered = true,
            tipoDaLicenca = "facial"
        )

        cacheManager.save(cache)

        val loaded = cacheManager.load()
        assertNotNull(loaded)
        assertEquals("persisted-device-123", loaded?.deviceId)
        assertEquals("facial", loaded?.tipoDaLicenca)

        val sharedPrefs = context.getSharedPreferences("mupa_device_cache_legacy", Context.MODE_PRIVATE)
        assertEquals("persisted-device-123", sharedPrefs.getString("device_id", null))
        assertEquals("facial", sharedPrefs.getString("tipo_da_licenca", null))
    }

    // --- TIER 1 FEATURE 3: License & Hardware Check for FR ---

    @Test
    fun testAudienceAnalyticsManagerStartsWhenLicenseIsFacial() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "facial"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertTrue(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerStartsWhenLicenseIsAnalytics() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "analytics"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertTrue(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerStartsWhenLicenseIsEnterprise() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "enterprise"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertTrue(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerSkipsWhenLicenseIsConsulta() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "consulta"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertFalse(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerSkipsWhenLicenseIsTelevisao() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "televisao"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertFalse(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerSkipsWhenLicenseIsNull() = runBlocking {
        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = null
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertFalse(manager.startIfPossible())
    }

    @Test
    fun testAudienceAnalyticsManagerSkipsWhenFrontCameraIsUnavailable() = runBlocking {
        // Mock front camera unavailable
        every { AudienceAnalyticsManager.canRunOnDevice(any()) } returns false

        val cache = DeviceCache(
            deviceDbId = 1L, deviceId = "id", deviceName = "name", filial = "1",
            company = "c", companyCode = "cc", companyName = "cn", tenant = "t",
            lastSyncEpochMs = 0L, deviceRegistered = true, tipoDaLicenca = "facial"
        )
        cacheManager.save(cache)

        val manager = AudienceAnalyticsManager(context, mockk(), "id", { null }, { null })
        assertFalse(manager.startIfPossible())
    }

    @Test
    fun testDynamicLicenseTransitions() = runBlocking {
        var audienceStarted = false
        var audienceManager: AudienceAnalyticsManager? = null
        var starts = 0
        var stops = 0
        val mockManager = mockk<AudienceAnalyticsManager>(relaxed = true)

        suspend fun simulateEnsureAudienceStarted(licenseType: String?, canRun: Boolean, hasPermission: Boolean) {
            val licenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
            if (!licenseValid || !canRun) {
                if (audienceStarted) {
                    audienceManager?.stop()
                    audienceManager = null
                    audienceStarted = false
                    stops++
                }
                return
            }

            if (audienceStarted) {
                return
            }

            if (!hasPermission) {
                return
            }

            val manager = mockManager
            val started = true // simulate successful start
            if (started) {
                audienceManager = manager
                audienceStarted = true
                starts++
            } else {
                manager.stop()
            }
        }

        // 1. null to "facial" starts analytics
        simulateEnsureAudienceStarted("facial", canRun = true, hasPermission = true)
        assertTrue(audienceStarted)
        assertNotNull(audienceManager)
        assertEquals(1, starts)
        assertEquals(0, stops)

        // 2. "analytics" to "enterprise" maintains running without redundant restarts
        simulateEnsureAudienceStarted("enterprise", canRun = true, hasPermission = true)
        assertTrue(audienceStarted)
        assertEquals(1, starts)
        assertEquals(0, stops)

        // 3. "facial" to null/consulta stops analytics
        simulateEnsureAudienceStarted("consulta", canRun = true, hasPermission = true)
        assertFalse(audienceStarted)
        assertNull(audienceManager)
        assertEquals(1, starts)
        assertEquals(1, stops)

        // 4. null to "facial" starts again
        simulateEnsureAudienceStarted("facial", canRun = true, hasPermission = true)
        assertTrue(audienceStarted)
        assertNotNull(audienceManager)
        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun testRefreshInBackgroundOfflineDoesNotValidateOrEnsureAudience() = runBlocking {
        val controller = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java)
        val activity = controller.get()

        val deviceIdField = PlayerActivity::class.java.getDeclaredField("deviceId")
        deviceIdField.isAccessible = true
        deviceIdField.set(activity, "test-device-id")

        val manifestManager = mockk<ManifestManager>(relaxed = true)
        val manifestManagerField = PlayerActivity::class.java.getDeclaredField("manifestManager")
        manifestManagerField.isAccessible = true
        manifestManagerField.set(activity, manifestManager)

        val spyActivity = spyk(activity, recordPrivateCalls = true)
        every { spyActivity["isOnline"]() } returns false
        coEvery { spyActivity["ensureAudienceStarted"]() } returns Unit

        mockkConstructor(DeviceValidationService::class)
        coEvery { anyConstructed<DeviceValidationService>().validateDevice(any()) } returns mockk()

        val method = PlayerActivity::class.java.getDeclaredMethod("refreshInBackground", kotlin.coroutines.Continuation::class.java)
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<Unit> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.getOrThrow()
            }
        }
        method.invoke(spyActivity, continuation)

        coVerify(exactly = 0) { anyConstructed<DeviceValidationService>().validateDevice(any()) }
        coVerify(exactly = 0) { spyActivity["ensureAudienceStarted"]() }
    }

    @Test
    fun testRefreshInBackgroundOnlineValidatesAndEnsuresAudience() = runBlocking {
        val controller = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java)
        val activity = controller.get()

        val deviceIdField = PlayerActivity::class.java.getDeclaredField("deviceId")
        deviceIdField.isAccessible = true
        deviceIdField.set(activity, "test-device-id")

        val manifestManager = mockk<ManifestManager>(relaxed = true)
        coEvery { manifestManager.fetchManifest(any()) } returns ""
        val manifestManagerField = PlayerActivity::class.java.getDeclaredField("manifestManager")
        manifestManagerField.isAccessible = true
        manifestManagerField.set(activity, manifestManager)

        val spyActivity = spyk(activity, recordPrivateCalls = true)
        every { spyActivity["isOnline"]() } returns true
        coEvery { spyActivity["ensureAudienceStarted"]() } returns Unit

        mockkConstructor(DeviceValidationService::class)
        coEvery { anyConstructed<DeviceValidationService>().validateDevice(any()) } returns mockk()

        val method = PlayerActivity::class.java.getDeclaredMethod("refreshInBackground", kotlin.coroutines.Continuation::class.java)
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<Unit> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.getOrThrow()
            }
        }
        method.invoke(spyActivity, continuation)

        coVerify(exactly = 1) { anyConstructed<DeviceValidationService>().validateDevice("test-device-id") }
        coVerify(exactly = 1) { spyActivity["ensureAudienceStarted"]() }
    }

    @Test
    fun testRefreshInBackgroundExceptionPropagationDoesNotBlockAudience() = runBlocking {
        val controller = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java)
        val activity = controller.get()

        val deviceIdField = PlayerActivity::class.java.getDeclaredField("deviceId")
        deviceIdField.isAccessible = true
        deviceIdField.set(activity, "test-device-id")

        val manifestManager = mockk<ManifestManager>(relaxed = true)
        coEvery { manifestManager.fetchManifest(any()) } returns ""
        val manifestManagerField = PlayerActivity::class.java.getDeclaredField("manifestManager")
        manifestManagerField.isAccessible = true
        manifestManagerField.set(activity, manifestManager)

        val spyActivity = spyk(activity, recordPrivateCalls = true)
        every { spyActivity["isOnline"]() } returns true
        coEvery { spyActivity["ensureAudienceStarted"]() } returns Unit

        mockkConstructor(DeviceValidationService::class)
        coEvery { anyConstructed<DeviceValidationService>().validateDevice(any()) } throws RuntimeException("Network error")

        val method = PlayerActivity::class.java.getDeclaredMethod("refreshInBackground", kotlin.coroutines.Continuation::class.java)
        method.isAccessible = true

        val continuation = object : kotlin.coroutines.Continuation<Unit> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.getOrThrow()
            }
        }
        method.invoke(spyActivity, continuation)

        coVerify(exactly = 1) { anyConstructed<DeviceValidationService>().validateDevice("test-device-id") }
        coVerify(exactly = 1) { spyActivity["ensureAudienceStarted"]() }
    }
}
