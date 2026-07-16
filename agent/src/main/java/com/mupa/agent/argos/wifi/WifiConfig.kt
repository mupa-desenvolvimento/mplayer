package com.mupa.agent.argos.wifi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mupa.agent.argos.admin.WifiManager

class WifiConfig(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wifi_config", Context.MODE_PRIVATE)
    private val wifiManager = WifiManager(context)
    private val tag = "ArgosWifiConfig"

    fun setCredentials(ssid: String, password: String) {
        prefs.edit()
            .putString("wifi_ssid", ssid.trim())
            .putString("wifi_password", password)
            .putBoolean("wifi_configured", true)
            .apply()
        Log.d(tag, "Credenciais salvas para SSID: $ssid")
    }

    fun getSsid(): String = prefs.getString("wifi_ssid", "") ?: ""
    fun getPassword(): String = prefs.getString("wifi_password", "") ?: ""
    fun isConfigured(): Boolean = prefs.getBoolean("wifi_configured", false)

    /**
     * Tenta conectar à rede WiFi salva automaticamente.
     * Chamado na inicialização do agent.
     */
    fun autoConnectIfConfigured() {
        if (!isConfigured()) {
            Log.d(tag, "WiFi não configurada, pulando auto-conexão")
            return
        }

        val ssid = getSsid()
        val password = getPassword()

        if (ssid.isBlank()) {
            Log.w(tag, "SSID vazio, não é possível conectar")
            return
        }

        val current = wifiManager.getCurrentSsid()
        if (current == ssid) {
            Log.d(tag, "Já conectado a: $ssid")
            return
        }

        Log.d(tag, "Tentando conectar à WiFi salva: $ssid")
        val success = wifiManager.connectToWifi(ssid, password)
        if (success) {
            Log.i(tag, "WiFi salva ativada: $ssid")
        } else {
            Log.w(tag, "Falha ao conectar à WiFi: $ssid")
        }
    }

    fun clearCredentials() {
        prefs.edit()
            .remove("wifi_ssid")
            .remove("wifi_password")
            .putBoolean("wifi_configured", false)
            .apply()
        Log.d(tag, "Credenciais de WiFi limpas")
    }
}
