package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.KeyguardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.databinding.ActivitySplashBinding
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.services.DeviceValidationResult
import com.mupa.player.enterprise.services.DeviceValidationService
import com.mupa.player.enterprise.services.MupaKeepAliveService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        MupaKeepAliveService.start(this)
        val settings = SettingsManager(applicationContext)
        if (settings.getKioskModeCached() || settings.getMdmLockedCached()) {
            applyWakeAndUnlock()
        }
        applyAlwaysOnScreen()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.deviceIdWatermark.text = "ID: -"

        lifecycleScope.launch {
            binding.stepText.text = "1 Identificando dispositivo"
            SettingsManager(applicationContext).getOrCreateDeviceUuid()
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            binding.deviceIdWatermark.text = "ID: ${deviceId.trim()}"
            val cacheManager = DeviceCacheManager(applicationContext)
            val cached = cacheManager.load()
            val defaultUrl = "https://midias.mupa.app/player-consulta/${deviceId.trim()}"

            binding.stepText.text = "2 Validando cadastro"
            val nextIntent = if (isOnline()) {
                when (val result = DeviceValidationService(applicationContext).validateDevice(deviceId)) {
                    is DeviceValidationResult.Found -> {
                        val serial = result.cache.deviceId.ifBlank { deviceId }
                        val url = "https://midias.mupa.app/player-consulta/${serial.trim()}"
                        Intent(this@SplashActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_START_URL, url)
                    }
                    DeviceValidationResult.NotFound -> {
                        Intent(this@SplashActivity, DeviceRegistrationActivity::class.java)
                    }
                    DeviceValidationResult.NotConfigured -> {
                        val cachedRegistered = cached?.takeIf { it.deviceRegistered }?.deviceId?.trim()
                        val url = if (!cachedRegistered.isNullOrBlank()) {
                            "https://midias.mupa.app/player-consulta/${cachedRegistered}"
                        } else {
                            defaultUrl
                        }
                        Intent(this@SplashActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_START_URL, url)
                    }
                    is DeviceValidationResult.Error -> {
                        val cachedRegistered = cached?.takeIf { it.deviceRegistered }?.deviceId?.trim()
                        val url = if (!cachedRegistered.isNullOrBlank()) {
                            "https://midias.mupa.app/player-consulta/${cachedRegistered}"
                        } else {
                            defaultUrl
                        }
                        Intent(this@SplashActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_START_URL, url)
                    }
                }
            } else {
                val cachedRegistered = cached?.takeIf { it.deviceRegistered }?.deviceId?.trim()
                val url = if (!cachedRegistered.isNullOrBlank()) {
                    "https://midias.mupa.app/player-consulta/${cachedRegistered}"
                } else {
                    defaultUrl
                }
                Intent(this@SplashActivity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_START_URL, url)
            }

            delay(250)
            binding.stepText.text = "3 Carregando"
            startActivity(nextIntent)
            finish()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255,
                )
            }
        }
    }

    private fun applyWakeAndUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { km.requestDismissKeyguard(this, null) }
        }
    }
}
