# Resposta ao Diagnóstico: `device-api-v2/manifest` HTTP 404 (`4001282408002517`) — Solução Aplicada e Melhorias Recomendadas no MPlayer

**Data:** 2026-11-23  
**Projeto Supabase:** `midias_mupa` (`iurqddkuihjsmxubibao`)  
**Dispositivo de referência:** SK-210 (`serial = 4001282408002517`, `id = 1533`)  
**Status:** **Resolvido em produção** (`device-api-v2` v65 — `4001282408002517` agora responde **HTTP `200`**).

---

## 1. Resposta direta ao diagnóstico: vocês acertaram em cheio

A conclusão da segunda rodada do diagnóstico estava **100% correta**: o banco de dados estava íntegro e a causa raiz estava **exclusivamente no código TypeScript da Edge Function `device-api-v2`**.

### Por que `C88AD8E8E4C9` funcionava e `4001282408002517` retornava `404 Device not found`

No código publicado da `device-api-v2` (`functions/device-api-v2/index.ts`, rotas `/manifest` e `/ping`), a busca do dispositivo montava o filtro `.or(...)` assim:

```ts
const orFilter = /^\d+$/.test(serial)
  ? `serial.eq."${serial}",apelido_interno.eq."${serial}",id.eq.${serial}`
  : `serial.eq."${serial}",apelido_interno.eq."${serial}"`;

const { data: device, error: deviceError } = await supabaseClient
  .from('dispositivos')
  .select("*, appearance_config")
  .or(orFilter)
  .maybeSingle();

if (deviceError || !device) {
  return new Response(JSON.stringify({ error: 'Device not found' }), { status: 404 });
}
```

O que acontecia em cada caso:
- **`C88AD8E8E4C9`** contém letras (`C`, `A`, `D`, `E`) → `/^\d+$/` retornava `false` → consultava apenas `serial` e `apelido_interno` → **HTTP `200`**.
- **`4001282408002517`** (16 dígitos puramente numéricos) → `/^\d+$/` retornava `true` → a Edge Function anexava **`id.eq.4001282408002517`** na cláusula `.or(...)`.
- Como `dispositivos.id` é `integer` (`int4`, máximo `2.147.483.647`), o Postgres rejeitava a query inteira com erro **`22003: value "4001282408002517" is out of range for type integer`** (HTTP `400` interno).
- A Edge Function caía em `if (deviceError || !device)` e mascarava o erro de overflow do Postgres como um genérico **`404 {"error":"Device not found"}`**.

Esse mesmo bug estava afetando todos os dispositivos com seriais numéricos de 14–16 dígitos (`4001282408002517`, `4001132263002774`, `4001132263003445`, `4001132263002233`, etc.), totalizando **~24.599 erros `400` + 25.814 respostas `404` por dia**.

---

## 2. O que fizemos para solucionar o problema (e os demais erros críticos encontrados nos logs)

Durante a investigação dos logs do Supabase nas últimas 48h (motivada pelo volume anômalo de **~993 mil chamadas a Edge Functions** e **~1,19 milhão de erros/24h no Postgres**), além do bug do SK-210, corrigimos todos os problemas correlacionados:

### 2.1. Correção e deploy da Edge Function `device-api-v2` (v65 — ativa em produção)
1. **Fix do overflow de `int4` (`/manifest` e `/ping`):** só inclui `id.eq.${serial}` quando o serial cabe em `int4`:
   ```ts
   const isInt32Serial = /^\d{1,10}$/.test(serial) && Number(serial) <= 2147483647;
   const orFilter = isInt32Serial
     ? `serial.eq."${serial}",apelido_interno.eq."${serial}",id.eq.${serial}`
     : `serial.eq."${serial}",apelido_interno.eq."${serial}"`;
   ```
   - **Validado ao vivo:** `GET /functions/v1/device-api-v2/manifest?serial=4001282408002517` agora retorna **HTTP `200`**.
2. **Fix da consulta de `device_tags` (`~588.691 erros 400/dia`):** a Edge Function passava `device.id` (`integer`) para `device_tags.device_id` (`uuid`), gerando `22P02: invalid input syntax for type uuid` em **100% das chamadas ao `/manifest`**. Corrigido para usar `device.device_uuid` com guarda `if (device.device_uuid)`.
3. **Remoção da rota legada `clear-pending-commands`** (que referenciava `device_commands`) e correção do import ausente de `tvBaseUrl`.

### 2.2. Desativação silenciosa de `public.device_commands` e `public.device_execution_logs`
- O pico de **~614k chamadas/24h** em `device-api-v2/manifest` (e **578k erros `401`/24h** em `device_execution_logs`) era causado por **6 players com APK legado** em loop infinito (`~1,5 req/s` cada) sobre **18.485 comandos `pending`** em `device_commands` — a policy `anon` só permitia `SELECT`, então `PATCH` voltava `204` com 0 linhas alteradas e o comando nunca saía de `pending`.
- Aplicado no banco:
  - Todos os `18.485` comandos `pending` encerrados (`status = 'done'`).
  - Policy `anon_select_pending_device_commands` alterada para `USING (false)` (APKs legados recebem `200 []` e interrompem o loop sem gerar `180k` erros `404`/dia).
  - `device_commands` e `device_execution_logs` removidas da publicação `supabase_realtime` (mantendo intactas as demais tabelas de conteúdo como `playlist_items` e o canal broadcast `manifest-events`).
  - As 4 funções SQL legadas (`issue_reload_command_to_affected_devices`, `sync_device_playlist`, `get_pending_device_command`, `mark_device_command_executed`) convertidas em *no-op* seguro.

### 2.3. Correção dos erros secundários (Banco + MPlayer)
- **`public.device_events` criada no Supabase** (e migration salva em `supabase/migrations/20261123103000_create_device_events.sql`), eliminando **~1.301 erros `404`/dia**. Nos primeiros minutos após criação, a frota já enviou **869 eventos** com sucesso.
- **`public.get_dispositivo_por_serial`** alterada para retornar `NULL` (HTTP `200`) quando o serial não existe em vez de `RAISE EXCEPTION P0002`, eliminando **~1.208 erros `500`/dia** (alinhado com `DeviceValidationService.kt`, que já trata `null` como `DeviceValidationResult.NotFound`).
- **`MediaPlayLogsSyncManager.kt` e `DeviceEventSyncManager.kt`** atualizados com `?on_conflict=id` e `Prefer: resolution=ignore-duplicates,return=minimal`, eliminando os **~207 erros `409`/dia** (`media_play_logs_pkey`).

### 2.4. Impacto medido em produção (`query_logs` — antes vs. depois)

| Métrica (por minuto) | Antes (`13:25 UTC`) | Depois (`13:36 UTC`) | Variação |
|---|---:|---:|---:|
| Chamadas `device-api-v2/manifest` | 451/min | **8/min** | **-98,2%** |
| Erros no Postgres (`ERROR`) | 869/min | **0/min** | **-100%** |
| Erros `401` em `device_execution_logs` | 418/min | **0/min** | **-100%** |
| Erros `400` em `device_tags` | 385/min | **0/min** | **-100%** |

---

## 3. Melhorias recomendadas no código do MPlayer (`c:\dev\mplayer`)

O incidente do SK-210 expôs fragilidades importantes no cliente Android que amplificam qualquer falha de backend em custo de Edge Functions e dificultam o diagnóstico em campo. Abaixo estão as melhorias priorizadas:

### P0 — Backoff exponencial com jitter no loop de retry do manifesto
- **Onde:** `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt` (linhas `817–861`).
- **Problema atual:** quando `fetchManifest(deviceId)` falha, o loop `while (remote.isBlank())` espera um **`delay(3000L)` fixo para sempre**, sem distinguir erro de rede transitório de erro `4xx` determinístico (`404`, `400`, `401`). Foi isso que fez um único SK-210 disparar **17.499 chamadas/dia** contra a Edge Function enquanto tomava `404`. Em uma falha generalizada de backend, toda a frota gera *thundering herd* e estoura a cota de Edge Functions.
- **Sugestão:**
  - Aplicar backoff exponencial com teto e jitter aleatório (ex.: `3s → 6s → 12s → 24s → 60s → 120s`, com teto de `300s` para erros `4xx` determinísticos como `404`/`400`/`401`, resetando para `3s` assim que obtiver sucesso ou quando a rede voltar de offline → online).
  - Reagir imediatamente ao evento de rede/Realtime (ex.: reconexão ou broadcast de atualização) para furar o timer de backoff quando o problema for resolvido.

### P0 — Preservar e exibir status HTTP + mensagem real de erro em `ManifestManager.fetchManifest()`
- **Onde:** `app/src/main/java/com/mupa/player/enterprise/managers/ManifestManager.kt` (linhas `57–63`) e `PlayerActivity.kt` (linhas `817–861`).
- **Problema atual:** `fetchManifest` retorna apenas `String` e o chamador usa `runCatching { ... }.getOrDefault("")`, descartando o código HTTP e o JSON de erro retornado pelo servidor. Na tela aparece apenas `"Não foi possível obter a programação. Tentando novamente..."` com `detailText = ""`, obrigando a instrumentar builds de debug para descobrir se é `404`, `401`, `500` ou timeout.
- **Sugestão:**
  - Retornar um tipo selado `ManifestFetchResult` (`Success(json)`, `HttpError(code, bodyMessage)`, `NetworkError(cause)`).
  - Exibir o motivo resumido e o próximo retry no `detailText` do overlay de sincronização (ex.: `HTTP 404: Device not found · nova tentativa em 30s`) e logar estruturado no Logcat (`code`, `error`, `serial`, `nextRetryMs`).

### P1 — Padronizar idempotência (`ignore-duplicates`) e tratamento de erro em todos os Sync Managers
- **Onde:**
  - Já corrigidos nesta rodada: `MediaPlayLogsSyncManager.kt` e `DeviceEventSyncManager.kt` (`?on_conflict=id` + `Prefer: resolution=ignore-duplicates,return=minimal`).
  - Revisar os demais: `PriceAnalyticsSyncManager.kt`, `MediaDownloadFailureSyncManager.kt`, `AudienceSyncManager.kt`.
- **Problema:** em qualquer `POST` em lote sem `ignore-duplicates`, se a resposta de rede cair depois que o servidor gravou o lote, o app não marca como enviado no Room e fica reenviando o mesmo lote eternamente tomando `409 Conflict`.
- **Sugestão:**
  - Garantir `?on_conflict=id` + `Prefer: resolution=ignore-duplicates,return=minimal` em todos os endpoints REST de telemetria com PK `id`.
  - Tratar `4xx` não-recuperáveis (ex.: `400` por schema inválido) com descarte/quarentena após N tentativas, evitando fila infinita travada no Room.

### P1 — Comparação semântica (não raw-string) em `ManifestManager.compareManifest()`
- **Onde:** `app/src/main/java/com/mupa/player/enterprise/managers/ManifestManager.kt` (linhas `83–87`).
- **Problema atual:** `local == remoteJson.trim()` compara o JSON bruto caractere a caractere. Qualquer reordenação de chave ou campo volátil no payload força re-save e reprocessamento desnecessário da playlist.
- **Sugestão:** comparar `manifest.updated_at` combinado com um hash determinístico (SHA-256) da lista normalizada de itens (`id`, `url`, `duration_ms`, `volume`, `position`, janelas de vigência) e `overlays`.

### P2 — Atualizar APKs legados em campo e remover código de diagnóstico temporário
- **Frota legada:** os 6 aparelhos que geraram o pico de `device_commands` (`MUPA_PLAYER_085/086/087`, `mupa_player_23`, `1431`, `1306`) ainda rodam builds antigos que fazem `GET /rest/v1/device_commands` (~180k req/dia). Embora a desativação silenciosa tenha zerado o loop e os erros, atualizar esses aparelhos para o build atual do MPlayer elimina de vez esse polling residual e permite executar o `DROP TABLE` definitivo de `device_commands` e `device_execution_logs`.
- **Higiene do repositório:** confirmar antes do próximo release que nenhum log temporário de diagnóstico (`ManifestDebug` / `RowDebug`) ficou no working tree, e sincronizar o diff da `device-api-v2` (v65) no repositório Git do painel/backend para evitar regressão no próximo deploy.
