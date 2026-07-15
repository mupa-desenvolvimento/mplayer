[OPEN] Agent not receiving commands

## Session
- id: agent-not-receiving-commands
- repo: c:\src\mupa_player_enterprise
- date: 2026-06-15

## Symptom
- O ARGOS Agent não está recebendo/executando comandos publicados no RTDB.

## Hypotheses (falsifiable)
1) Firebase RTDB não inicializou (config vazia / DefaultFirebaseApp ausente / erro de auth), então `RealtimeCommandManager.start()` não cria subscriptions.
2) O Agent está inscrito no path errado (IDs aceitos diferentes do `device_id` que o Web usa), então os comandos ficam em outro nó e nunca chegam.
3) O Agent recebe o snapshot, mas ignora por validação de `device_id`/`id_device` (mismatch no envelope).
4) O listener do RTDB está cancelando (permissões/regras RTDB, conectividade), então `onCancelled` impede o consumo.
5) O Web está escrevendo apenas no nó legado (`commands`) ou em formato inesperado, e o Agent não está enxergando (ou deduplicando) devido a `lastCommandId`/cooldown.

## Evidence to collect
- logcat: `start: dbUrl=... ids=...`, `.info/connected`, `onCancelled`, `onDataChange`
- IDs aceitos pelo Agent neste device (persistId/SN/android_id) e qual `device_id` o Web está usando
- logs locais (argos_command_logs) das entradas `debug_cross_device`

## Evidence (collected)
- logcat mostrou que o realtime iniciou e conectou:
  - `dbUrl=https://comandos-1621d-default-rtdb.firebaseio.com`
  - `ids=SN:80282604013239,80282604013239,3f12d8fc081915e0`
  - `.info/connected=true`
  - `onDataChange` para `commands` e `command_queue` nos 3 ids com `hasChildren=false` (sem comandos no nó observado)
- logs locais (`argos_command_logs`):
  - `system identity` confirmou `device_id="SN:80282604013239"`
  - `debug_cross_device snapshot_accepted` para `commands/command_queue/device_config/ota` nos 3 ids, com `has_command_queue=false` (snapshot vazio)
