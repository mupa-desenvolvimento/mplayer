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
    private var activeEdit: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        applyAlwaysOnScreen()

        lifecycleScope.launch {
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            viewModel.setDeviceId(deviceId)
        }

        setupVirtualKeyboard()

        binding.companyCodeEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setCompanyCode(it) })
        binding.nicknameEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setNickname(it) })
        binding.filialEdit.addTextChangedListener(SimpleTextWatcher { viewModel.setFilial(it) })

        disableSystemIme(binding.companyCodeEdit)
        disableSystemIme(binding.nicknameEdit)
        disableSystemIme(binding.filialEdit)

        setActiveField(binding.companyCodeEdit, "Código empresa")
        binding.companyCodeEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setActiveField(binding.companyCodeEdit, "Código empresa")
        }
        binding.nicknameEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setActiveField(binding.nicknameEdit, "Apelido do dispositivo")
        }
        binding.filialEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setActiveField(binding.filialEdit, "Número loja/filial")
        }

        binding.companyCodeEdit.setOnClickListener { setActiveField(binding.companyCodeEdit, "Código empresa") }
        binding.nicknameEdit.setOnClickListener { setActiveField(binding.nicknameEdit, "Apelido do dispositivo") }
        binding.filialEdit.setOnClickListener { setActiveField(binding.filialEdit, "Número loja/filial") }

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

                        if (!state.isLoading && state.status == "OK") {
                            lifecycleScope.launch {
                                openPlayerAndFinish(state.deviceId.trim())
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

    private fun openPlayerAndFinish(serial: String) {
        val url = "https://midias.mupa.app/player-consulta/${serial.trim()}"
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_START_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shouldBlockHardwareKeyboard(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun shouldBlockHardwareKeyboard(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) return false
        val device = event.device ?: return false
        if (device.isVirtual) return false
        return true
    }

    private fun disableSystemIme(editText: EditText) {
        editText.showSoftInputOnFocus = false
    }

    private fun hideSoftKeyboard(view: View) {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setActiveField(editText: EditText, label: String) {
        activeEdit = editText
        binding.activeFieldLabel.text = label
        editText.requestFocus()
        editText.setSelection(editText.text?.length ?: 0)
        hideSoftKeyboard(editText)
    }

    private fun setupVirtualKeyboard() {
        val container = binding.virtualKeyboardContainer
        container.removeAllViews()
        container.addView(createKeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")))
        container.addView(createKeyRow(listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")))
        container.addView(createKeyRow(listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")))
        container.addView(createKeyRow(listOf("Z", "X", "C", "V", "B", "N", "M")))
        container.addView(createKeyRow(listOf("ESP", "DEL", "LIMPAR")))
    }

    private fun createKeyRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        keys.forEach { label ->
            val btn = MaterialButton(this).apply {
                text = label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6
                }
                setOnClickListener { onVirtualKey(label) }
            }
            row.addView(btn)
        }
        return row
    }

    private fun onVirtualKey(label: String) {
        val target = activeEdit ?: return
        when (label) {
            "DEL" -> {
                val text = target.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    target.setText(text.dropLast(1))
                    target.setSelection(target.text?.length ?: 0)
                }
            }
            "LIMPAR" -> {
                target.setText("")
            }
            "ESP" -> appendToField(target, " ")
            else -> appendToField(target, label)
        }
    }

    private fun appendToField(target: EditText, value: String) {
        val raw = target.text?.toString().orEmpty()
        val next = (raw + value)
        val filtered = when (target.id) {
            binding.filialEdit.id -> next.filter { it.isDigit() }
            else -> next
        }
        target.setText(filtered)
        target.setSelection(target.text?.length ?: 0)
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
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString().orEmpty())
    }
}
