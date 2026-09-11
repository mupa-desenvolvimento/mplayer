package com.mupa.agent.argos.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager as AndroidWifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class WifiManager(private val context: Context) {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? AndroidWifiManager
    private val tag = "ArgosWifiManager"

    fun openWifiSettings(): Intent {
        return Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Conecta à uma rede WiFi usando SSID e senha WPA2-PSK.
     * Retorna true se a conexão foi enfileirada; false se WiFi desativado ou erro.
     *
     * Android 10+ (API 29): usa WifiNetworkSuggestion — addNetwork(WifiConfiguration) foi
     * bloqueado para apps com targetSdk >= 29 e sempre retorna -1.
     * Android < 10: mantém a API legada WifiConfiguration.
     */
    fun connectToWifi(ssid: String, password: String): Boolean {
        return try {
            val wm = wifiManager ?: return false.also { Log.e(tag, "WifiManager não disponível") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectModern(wm, ssid, password)
            } else {
                connectLegacy(wm, ssid, password)
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao conectar WiFi: ${e.message}", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectModern(wm: AndroidWifiManager, ssid: String, password: String): Boolean {
        if (!wm.isWifiEnabled) {
            // setWifiEnabled() é ignorado para apps não-sistema no Android 10+
            Log.e(tag, "WiFi desativado; ative manualmente (não é possível forçar no Android 10+)")
            return false
        }
        // Remove sugestões antigas para este SSID para evitar conflito
        val old = wm.networkSuggestions.filter { it.ssid == ssid }
        if (old.isNotEmpty()) wm.removeNetworkSuggestions(old)

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val status = wm.addNetworkSuggestions(listOf(suggestion))
        Log.d(tag, "addNetworkSuggestions: status=$status para $ssid")
        return status == AndroidWifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(wm: AndroidWifiManager, ssid: String, password: String): Boolean {
        if (!wm.isWifiEnabled) {
            Log.d(tag, "WiFi desativado, ativando...")
            wm.isWifiEnabled = true
        }
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            hiddenSSID = false
        }
        val netId = wm.addNetwork(config)
        return if (netId != -1) {
            wm.disconnect()
            val connected = wm.enableNetwork(netId, true)
            wm.reconnect()
            Log.d(tag, "WiFi conectando: $ssid (netId=$netId, connected=$connected)")
            true
        } else {
            Log.e(tag, "Falha ao adicionar rede WiFi: $ssid")
            false
        }
    }

    fun isWifiEnabled(): Boolean = wifiManager?.isWifiEnabled ?: false

    /**
     * Retorna true se ACCESS_FINE_LOCATION está concedida em runtime.
     * Necessária para getScanResults() no Android 10+.
     */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun getCurrentSsid(): String? = try {
        @Suppress("DEPRECATION")
        wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
    } catch (e: Exception) {
        null
    }

    suspend fun getScanResults(): List<String> {
        val wm = wifiManager ?: return emptyList()

        // Android 10+ exige ACCESS_FINE_LOCATION em runtime para ler resultados de scan.
        // Sem ela, scanResults retorna lista vazia sem qualquer erro visível.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasLocationPermission()) {
            Log.w(tag, "getScanResults: ACCESS_FINE_LOCATION não concedida — lista ficará vazia")
            return emptyList()
        }

        // Trigger a scan — may silently fail on Zebra if another scan is already running,
        // which is fine: fusionconfd/SkinnyCat scans every ~10s so cached results exist.
        @Suppress("DEPRECATION")
        runCatching { wm.startScan() }
        // If cached results are empty (very first boot), wait 3s for the in-progress scan.
        val immediate = @Suppress("DEPRECATION") wm.scanResults
        val raw = if (immediate.isEmpty()) {
            delay(3_000)
            @Suppress("DEPRECATION") wm.scanResults
        } else {
            immediate
        }
        Log.i(tag, "getScanResults: ${raw.size} results")
        return raw.mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }
            .distinct()
            .sorted()
    }
}
