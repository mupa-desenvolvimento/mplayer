package com.mupa.player.enterprise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mupa.player.enterprise.managers.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsManager = SettingsManager(app.applicationContext)

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    private val _devText = MutableStateFlow("")
    val devText: StateFlow<String> = _devText.asStateFlow()

    private val _lastCommand = MutableStateFlow("")
    val lastCommand: StateFlow<String> = _lastCommand.asStateFlow()

    private val _lastAck = MutableStateFlow("")
    val lastAck: StateFlow<String> = _lastAck.asStateFlow()

    fun setStartUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            _url.value = trimmed
        }
    }

    fun load() {
        viewModelScope.launch {
            val settings = settingsManager.getSettings()
            _devMode.value = settings.devMode
            if (_url.value.isNullOrBlank()) {
                _url.value = buildInitialUrl(settings.serverUrl)
            }
        }
    }

    fun setOffline(offline: Boolean) {
        _isOffline.value = offline
    }

    fun setDevText(value: String) {
        _devText.value = value
    }

    fun setLastCommand(value: String) {
        _lastCommand.value = value
    }

    fun setLastAck(value: String) {
        _lastAck.value = value
    }

    fun setDevMode(enabled: Boolean) {
        _devMode.value = enabled
    }

    private fun buildInitialUrl(serverUrl: String): String {
        val base = serverUrl.trim().trimEnd('/')
        return if (base.contains(PATH_PLAY) || base.contains(PATH_CONSULTA)) {
            base
        } else {
            "$base$PATH_CONSULTA"
        }
    }

    companion object {
        private const val PATH_CONSULTA = "/player-consulta"
        private const val PATH_PLAY = "/play"
    }
}
