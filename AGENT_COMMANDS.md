# Comandos aceitos pelo Agent ARGOS

Referência para adaptar o ARGOS Web. Fonte da verdade: [`CommandDispatcher.kt`](agent/src/main/java/com/mupa/agent/argos/commands/CommandDispatcher.kt).

## Como enviar um comando

Via fila Supabase (`enqueue_device_command` RPC), com `p_device_id` = **serial do dispositivo** (não o ID interno do Firebase). O agent puxa via `/api/public/agent/commands/pending` e confirma via `/commands/ack`. Também é aceito via Firebase RTDB legado em `m_argos/devices/{serial}/commands` → `{comando, timestamp, ...extra}`.

`payload`/parâmetros podem vir tanto na raiz do JSON quanto dentro de um objeto `payload` — o agent aceita os dois formatos.

---

## Apps

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `open_app` | `abrir_app` | `package` (ou `pacote`) | Abre um app |
| `close_app` | `fechar_app`, `fecha_app` | `package` | Fecha um app |
| `restart_app` | `reset_app` | `package` | Reinicia um app |
| `install_apk` | `install_app`, `update_app`, `atualizar_agent`, `update_agent`, `atualizar_mplayer` | `url`, `package`, `version`, `force`, `auto_open`, `checksum` | Instala/atualiza um APK. Requer Device Owner para instalação silenciosa; senão delega pro Managed Play se configurado |
| `uninstall_app` | `remove_app`, `delete_app` | `package`, `force` (bypassa proteção do próprio agent), `remove_from_whitelist` (default true), `silent`, `timeout_ms` | **Remove um app** |
| `set_whitelist` | `set_allowed_apps`, `set_kiosk_apps` | `whitelist` / `allowedPackages` / `packages` (array) | Define a lista de apps permitidos no kiosk |
| `list_kiosk_apps` 🆕 | `get_whitelist`, `listar_apps_kiosk`, `get_allowed_apps` | — | **Retorna a lista atual de apps do kiosk** + o app de autostart configurado, no campo `message` da resposta (JSON: `{"packages":[...],"autostart_package":"..."}`) |
| `list_installed_apps` 🆕 | `listar_apps_instalados`, `get_installed_apps` | `include_system` (bool, default `false`) | **Lista todos os apps "abríveis" instalados** (com ícone de launcher — exclui serviços/bibliotecas do sistema sem UI). Retorna `{"apps":[{"package","label","version","is_system"},...]}`. Pensado exatamente pra popular um seletor "abrir app" no ARGOS Web |
| `disable_autostart` 🆕 | `remove_autostart`, `desativar_autostart` | `package` (opcional) | **Desativa o autostart**. Se `package` for informado e não for o pacote atualmente configurado, não faz nada (idempotente) |

## Kiosk / Lock

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `kiosk_on` | `locktask_on`, `lock_device` | — | Ativa modo kiosk (lock task) |
| `kiosk_off` | `locktask_off`, `kiosk_disable`, `unlock_device` | — | Desativa modo kiosk |

## Dispositivo

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `reboot_device` | `reiniciar`, `reboot`, `restart_device`, `reiniciar_dispositivo` | — | Reinicia o dispositivo (requer Device Owner) |
| `volume` | — | `level` (0-100, ver implementação) | Ajusta volume |
| `brightness` | — | `level` | Ajusta brilho |
| `keep_awake` | — | `enabled` | Mantém tela acesa |
| `awake_schedule` | `set_awake_schedule` | horário(s) | Agenda de tela acesa |
| `rest_mode_on` | `rest_on`, `sleep_on`, `rest`, `sleep` | — | Ativa modo descanso (apaga conteúdo/tela) |
| `rest_mode_off` | `rest_off`, `wake_mode`, `wake_up` | — | Desativa modo descanso |
| `rest_screen_timeout` | — | timeout | Define timeout de tela no modo descanso |
| `open_settings` | `abrir_configuracoes`, `abrir_config` | — | Abre Configurações do Android |
| `clear_cache` | `limpar_cache` | — | Limpa cache |
| `screenshot` | `take_screenshot`, `capturar_tela` | — | Captura e envia screenshot |

## Sync / Status

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `sincronizar` | `sync`, `sync_now`, `report_now` | — | Força ciclo de sync (puxa comandos + envia heartbeat) imediatamente |

**Status online/offline:** não existe (e não precisa existir) um comando dedicado — é calculado no painel a partir do heartbeat automático que já roda a cada 5 min (`HEARTBEAT_INTERVAL_MS` em [`AgentOrchestrators.kt:121`](agent/src/main/java/com/mupa/agent/argos/services/AgentOrchestrators.kt:121)), sem nenhuma chamada extra. Lógica em [`getDeviceStatus()`](https://github.com/.../argus-device-hub/blob/main/src/lib/argos-devices.ts) (argus-device-hub):
- `online`: heartbeat há menos de 5 min
- `warning`: heartbeat entre 5 e 15 min (provavelmente sem rede no momento, ainda não preocupante)
- `offline`: heartbeat há mais de 15 min

Isso já absorve atrasos normais entre ciclos de heartbeat sem gerar alarme falso — **não precisa de polling adicional do painel pro agent**, só ler a coluna de timestamp já existente.

## Conteúdo / Player

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `atualizar_playlist` | `update_playlist`, `refresh_playlist` | — | Atualiza playlist do MPlayer |
| `enviar_arquivo` | `send_file`, `push_file` | — | Envia arquivo ao dispositivo |
| `executar_script` | `run_script`, `execute_script` | — | Executa script |

## Suporte remoto

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `anydesk_accept` / `anydesk_start_now` | — | `duration_ms`, `tap_x`/`tap_y` ou `tap_x_pct`/`tap_y_pct` | Abre AnyDesk e arma auto-aceite de conexão |
| `remote_support_accept` / `remote_support_request` | — | mesmos parâmetros acima | Idem, mas para o app ARGOS Remote |

## DataWedge (Zebra TC22 / ET45 / coletores)

Controlado via Intents públicos do DataWedge (`com.symbol.datawedge.api.ACTION`) — não depende de EMDK/SDK, funciona em qualquer coletor Zebra com DataWedge instalado (já vem de fábrica). Implementação em [`DataWedgeManager.kt`](agent/src/main/java/com/mupa/agent/argos/datawedge/DataWedgeManager.kt). Comandos retornam `failed:not_zebra_device`-like se o pacote `com.symbol.datawedge`/`com.zebra.datawedge` não existir (broadcast simplesmente não tem quem responda).

| Comando | Aliases | Parâmetros | Descrição |
|---|---|---|---|
| `dw_switch_profile` | `datawedge_switch_profile`, `trocar_perfil_datawedge` | `profile_name` (ou `profile`) | Troca o perfil ativo do DataWedge — o mais pedido por quem usa coletor (perfil diferente por app/tela) |
| `dw_enable` | `datawedge_enable`, `ativar_datawedge` | — | Ativa o DataWedge |
| `dw_disable` | `datawedge_disable`, `desativar_datawedge` | — | Desativa o DataWedge (útil quando o app já lê código de barras por câmera e o leitor físico está dando conflito) |
| `dw_scan_start` | `datawedge_scan_start`, `disparar_leitura` | — | Dispara uma leitura remotamente (soft trigger), sem precisar apertar o gatilho físico |
| `dw_scan_stop` | `datawedge_scan_stop`, `parar_leitura` | — | Cancela uma leitura em andamento |
| `dw_restore_defaults` | `datawedge_restore_defaults`, `restaurar_datawedge` | — | Restaura todos os perfis/plugins do DataWedge pro padrão de fábrica |
| `dw_get_info` | `datawedge_get_info`, `datawedge_status`, `status_datawedge` | — | Consulta versão do DataWedge + perfil ativo (resposta assíncrona, timeout 4s). **Conhecido**: bug interno do DataWedge (`NullPointerException` em `Scanner.getScannerVersion()`) impede a resposta — confirmado em testes reais tanto no TC22 quanto no ET45, então é da versão do DataWedge instalada, não do modelo do aparelho. Os outros comandos acima não dependem desse caminho e funcionam normalmente nos dois aparelhos |

## Manutenção

| Comando | Aliases | Status |
|---|---|---|
| `maintenance_on` / `maintenance_off` | — | **Não implementado** — sempre retorna `failed: not_implemented` |

---

## Itens pedidos que já existiam

- ✅ Remover app → `uninstall_app`
- ✅ Atualizar app → `install_apk`/`update_app`
- ✅ Atualizar o agent → `atualizar_agent`/`update_agent` (manual) + auto-update automático via `ConfigurationEngine`
- ✅ Status online/offline → já calculado no web a partir do heartbeat, sem requisições extras

## Itens adicionados nesta mudança

- 🆕 `disable_autostart` — desativa o app de autostart configurado
- 🆕 `list_kiosk_apps` — devolve a lista atual de apps do kiosk + autostart, pro painel conseguir mostrar o estado real aplicado (não só o que foi enviado)

## Segurança

Comandos destrutivos (lista em `DESTRUCTIVE_COMMANDS_BLOCKED_VIA_ADB`) são bloqueados no canal ADB não-autenticado (broadcast local), mas continuam disponíveis via fila Supabase, que é autenticada por `device_token`.
