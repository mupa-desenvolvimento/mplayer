package com.mupa.player.enterprise.managers

import org.json.JSONObject

sealed class RemoteCommand(val name: String) {
    data object ResetApp : RemoteCommand("reset_app")
    data object FechaApp : RemoteCommand("fecha_app")
    data object ConsultaEan : RemoteCommand("consulta_ean")
    data object AbrirApp : RemoteCommand("abrir_app")
    data object IpServer : RemoteCommand("ip_server")
    data object ClearCache : RemoteCommand("clear_cache")
    data object Reiniciar : RemoteCommand("reiniciar")
    data class Unknown(val raw: String) : RemoteCommand(raw)
}

object RemoteCommandParser {
    fun parse(json: String): RemoteCommand {
        val cmd = runCatching {
            JSONObject(json).optString("comando", "").trim()
        }.getOrDefault("")
        return when (cmd) {
            RemoteCommand.ResetApp.name -> RemoteCommand.ResetApp
            RemoteCommand.FechaApp.name -> RemoteCommand.FechaApp
            RemoteCommand.ConsultaEan.name -> RemoteCommand.ConsultaEan
            RemoteCommand.AbrirApp.name -> RemoteCommand.AbrirApp
            RemoteCommand.IpServer.name -> RemoteCommand.IpServer
            RemoteCommand.ClearCache.name -> RemoteCommand.ClearCache
            RemoteCommand.Reiniciar.name -> RemoteCommand.Reiniciar
            else -> RemoteCommand.Unknown(cmd.ifBlank { "unknown" })
        }
    }
}

class RemoteCommandManager {
    fun prepare() {
    }
}

