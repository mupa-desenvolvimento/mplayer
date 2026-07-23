package com.mupa.player.enterprise.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recebe o comando do Argos (app MDM separado, `com.mupa.agent.argos`) para abrir o Settings
 * do MPlayer automaticamente. Ver `ARGOS_OPEN_SETTINGS_CONTRACT.md` para o contrato completo.
 */
class OpenSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OPEN_SETTINGS) return
        context.startActivity(
            Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_OPEN_SETTINGS = "com.mupa.player.enterprise.ACTION_OPEN_SETTINGS"
    }
}
