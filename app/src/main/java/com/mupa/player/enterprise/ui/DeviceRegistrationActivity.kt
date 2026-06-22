package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.mupa.player.enterprise.databinding.ActivityDeviceRegistrationBinding
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceRegistrationActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityDeviceRegistrationBinding
    private val viewModel: DeviceRegistrationViewModel by viewModels()

    private var lastOverlayState: Boolean = false
    private var handledSuccess: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"

        applyAlwaysOnScreen()

        lifecycleScope.launch {
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            if (deviceId.isBlank()) {
                binding.deviceIdValue.text = "Identificação automática falhou. Insira o ID manualmente abaixo."
                binding.manualDeviceIdLayout.visibility = View.VISIBLE
                binding.manualDeviceIdEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setDeviceId(it) })
            } else {
                viewModel.setDeviceId(deviceId)
            }
        }

        binding.companyCodeEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setCompanyCode(it) })
        binding.nicknameEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setNickname(it) })
        binding.filialEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setFilial(it) })

        binding.registerButton.setOnClickListener {
            viewModel.register()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.deviceIdValue.text = state.deviceId
                        binding.deviceIdWatermark.text = "ID: ${state.deviceId.trim()}"
                        binding.companyName.text = state.company?.name ?: ""
                        binding.loading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                        binding.statusText.text = state.status
                        binding.registerButton.isEnabled = !state.isLoading

                        if (!state.isLoading && state.status == "OK" && !handledSuccess) {
                            handledSuccess = true
                            lifecycleScope.launch {
                                promptLockAfterRegistrationAndProceed(state.deviceId.trim())
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                val offline = !isOnline()
                setOfflineUiVisible(offline)
                binding.registerButton.isEnabled = !offline && binding.loading.visibility != View.VISIBLE
                delay(1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAlwaysOnScreen()
    }

    private fun promptLockAfterRegistrationAndProceed(serial: String) {
        openPlayerAndFinish(serial)
    }

    private fun openPlayerAndFinish(serial: String) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_DEVICE_ID, serial.trim())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setOfflineUiVisible(visible: Boolean) {
        if (visible == lastOverlayState) return
        lastOverlayState = visible
        val overlay = binding.offlineOverlay
        if (visible) {
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(220).start()
        } else {
            overlay.animate().alpha(0f).setDuration(220).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (android.os.Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(this)) {
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
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString().orEmpty())
    }
}
