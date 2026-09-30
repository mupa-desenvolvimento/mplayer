[OPEN] Cross-device command execution suspicion

## Session
- id: cross-device-commands
- repo: c:\src\mupa_player_enterprise
- date: 2026-06-15

## Symptom
- O Agent aparentemente executa comandos destinados a outros dispositivos, com pequeno delay.

## Hypotheses (falsifiable)
1) O Agent está consumindo o nó legado `commands` sem validar corretamente `device_id`/`id_device`, então espelhos globais ou payloads incompletos estão sendo assumidos pelo device errado.
2) O Agent está inscrito em múltiplos IDs alternativos (`persistent_device_id`, serial, android_id) e algum outro device/web está escrevendo no path de um ID alternativo compartilhado/incorreto.
3) O processamento de `command_queue` ou `commands` está usando `refDeviceId` como fallback quando o envelope não traz `device_id`, fazendo o device assumir comandos “órfãos”.
4) O Web está escrevendo envelopes/espelhos sem `device_id` consistente, e o Agent atual aceita e executa mesmo assim.
5) Há replay/estado residual local (`LocalCommandStore`) causando execução tardia de comandos antigos que parecem pertencer a outro device.

## Evidence to collect
- Logs de runtime do Agent mostrando `source`, `refDeviceId`, `incomingId`, `acceptedIds`, `command_id` e decisão de execução/ignore
- Estado atual do RTDB consumido pelo device (especialmente `command_queue` e `commands`)
- IDs aceitos pelo Agent neste device

## Notes
- Não alterar lógica antes de instrumentar e confirmar por evidência.

## Evidence (static + instrumentation prepared)
- `processSnapshot()` validava `device_id` apenas no snapshot raiz.
- Em `command_queue`, o snapshot raiz é o mapa de comandos e normalmente não contém `device_id`.
- `processCommandQueueSnapshot()` iterava todos os itens e `handleQueuedCommand()` executava sem validar `device_id` do envelope de cada comando.
- `handleCommand()` e `handlePlatformCommand()` também aceitavam o envelope sem validar `device_id`/`id_device` por item.
- O device não estava conectado no momento da verificação (`adb devices` vazio), então a confirmação runtime ficou pendente.

## Fix (implemented)
- Instrumentação adicionada em `RealtimeCommandManager.kt` para registrar aceitação/rejeição de snapshots e comandos.
- Proteção adicionada: cada envelope de `command_queue`, `commands` e legado agora só é processado se `device_id`/`id_device` pertencer ao conjunto `acceptedIds` do aparelho.
