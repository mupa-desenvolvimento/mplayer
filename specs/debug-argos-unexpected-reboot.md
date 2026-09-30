[OPEN] Argos unexpected reboot

## Session
- id: argos-unexpected-reboot
- repo: c:\src\mupa_player_enterprise
- date: 2026-06-16

## Symptom
- O ARGOS aparentemente está reiniciando o dispositivo após comando enviado pelo Web, sem o usuário esperar esse comportamento.

## Hypotheses (falsifiable)
1) O Web está enviando explicitamente `reboot_device` ou um alias equivalente, e o Agent está executando corretamente esse comando.
2) O Web está enviando outro comando, mas o Agent está interpretando/enfileirando o payload errado como `reboot_device` por erro de mapeamento/compatibilidade.
3) O Agent está consumindo um comando antigo ainda presente em `command_queue`/`commands`, e o reboot ocorre com atraso como replay de fila.
4) O reboot não vem de `CommandDispatcher`, mas de outro fluxo do app/MDM/policy acionado após sincronização ou recovery.
5) O dispositivo está reiniciando por causa externa (OEM/power/crash watchdog), e o timing com o comando do Web é coincidência.

## Evidence to collect
- logcat filtrando `ArgosRealtime`, `CommandDispatcher`, `ArgosForegroundService`, `DeviceOwnerPolicyManager`, `reboot`
- logs locais `argos_command_logs`
- estado atual de `command_queue` / `commands` e últimos `command_id`

## Evidence collected
- Device conectado via ADB: `80282604013239`
- Query em `argos_command_logs` não mostrou `reboot_requested`, mas mostrou comando legado visto como:
  - `source=commands`
  - `command=platform_command_seen`
  - `message=reiniciar`
  - `command_id=cmd:reiniciar:1781623565199`
  - `raw_has_id_device=false`
  - `raw_has_device_id=false`
- O mesmo `cmd:reiniciar:1781623565199` apareceu novamente após novo ciclo do app, sugerindo replay do nó legado `commands`.

## Confirmed hypothesis
- H3 confirmado parcialmente: há replay de comando legado `commands` com `reiniciar`.
- H1 também sustentada: o payload recebido pelo Agent continha `reiniciar`.

## Fix
- Preservar/deduzir `lastCommandId` também para snapshots legados sem `command_id`, evitando que um `reiniciar` antigo seja reexecutado após reboot/app restart.
- Arquivo alterado: `agent/src/main/java/com/mupa/agent/argos/commands/RealtimeCommandManager.kt`
- APK rebuildado e reinstalado no device conectado.

