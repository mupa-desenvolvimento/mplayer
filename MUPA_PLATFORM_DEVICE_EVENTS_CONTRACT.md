# Contrato: MPlayer → Plataforma Mupa — monitoramento proativo (heartbeat + falhas + conectividade)

## Por quê

Consultas de preço normais já provam que o terminal está vivo, mas não existia nenhum sinal
explícito de saúde do dispositivo — nem contagem de falhas repetidas do mesmo item, nem
duração de quedas de conectividade, nem heartbeat de qualquer tipo. Este documento cobre os 3
sinais novos que o MPlayer passa a enviar, e o único campo que precisa ser adicionado do lado
da plataforma para viabilizar o "heartbeat sob demanda".

## 1) Tabela `device_events` (Supabase, projeto `iurqddkuihjsmxubibao`, mesmo dos outros)

```sql
create table device_events (
  id uuid primary key default gen_random_uuid(),
  device_id text not null,
  company_id text,
  filial text,
  event_type text not null,        -- 'heartbeat' | 'repeated_scan_failure' | 'connectivity_restored'
  reason text,                     -- só em 'heartbeat': 'idle_timeout' | 'state_change' | 'requested'
  ean text,                        -- só em 'repeated_scan_failure'
  fail_count int,                  -- só em 'repeated_scan_failure'
  offline_duration_seconds int,    -- só em 'connectivity_restored'
  created_at timestamptz not null default now()
);
```

O app envia em lote (POST array, `Prefer: return=minimal`) na mesma fila-local-Room + upload
horário já usado pelas outras 4 sincronizações (`price_query_events`, sessões de audiência,
logs de mídia, imagens ausentes) — falha repetida e retomada de conectividade também tentam
subir imediatamente (melhor esforço) além do lote horário, pra visibilidade mais rápida.

### 1.1 `repeated_scan_failure`

Disparado quando o **mesmo EAN** falha (não encontrado/erro) **5 vezes seguidas** (sem sucesso
no meio) — não é disparado a cada tentativa, só uma vez quando cruza o limiar, resetando se o
item mudar ou se uma consulta daquele EAN funcionar. Sinal de possível problema de API/rede ou
cadastro daquele item específico, não de "produto não existe" avulso.

### 1.2 `connectivity_restored`

Disparado quando o device volta a ficar online depois de um período offline, com
`offline_duration_seconds` = duração da queda. Não existe evento explícito de "ficou offline
agora" — a ausência de eventos novos já é o sinal do lado da plataforma; o valor está na
duração da retomada.

### 1.3 `heartbeat`

**Nunca em intervalo fixo.** Só em 4 situações, no campo `reason`:
- `idle_timeout`: nenhuma consulta de preço nos últimos N minutos (configurável no device,
  padrão 45min, faixa 30–120min — Configurações → "Heartbeat Inteligente" no app).
- `state_change`: dev mode/demo mode alternado, ou logo após `connectivity_restored`.
- `requested`: a plataforma pediu (ver seção 2).

## 2) Heartbeat sob demanda: novo campo no RPC `get_dispositivo_por_serial`

O MPlayer já chama esse RPC a cada 1h (revalidação de licença) — decisão confirmada: **não**
criar canal novo, só aproveitar essa chamada que já existe. Para pedir um heartbeat imediato de
um device específico, a resposta desse RPC precisa passar a incluir:

```json
{
  "...": "campos já existentes (serial, apelido_interno, num_filial, tipo_da_licenca, etc.)",
  "heartbeat_requested": true
}
```

Quando o MPlayer ler `heartbeat_requested: true` na próxima chamada horária, ele envia
`event_type=heartbeat, reason=requested` e segue seu funcionamento normal. **Latência
esperada: até ~1h** (intervalo do ciclo atual) — se precisarem de confirmação em segundos, a
alternativa seria o canal Argos/Firebase (ver `ARGOS_OPEN_SETTINGS_CONTRACT.md` para o padrão
já implementado de broadcast Argos→MPlayer), não coberto por este documento.

Sugestão de implementação no RPC: resetar `heartbeat_requested` para `false` assim que o
device o consumir (ou usar `updated_at` do device_events mais recente para inferir se o pedido
já foi atendido) — a decidir do lado do backend.

## Validação

1. Escanear 6x o mesmo EAN inexistente (>2s entre elas) → 1 linha `repeated_scan_failure` em
   `device_events`, `fail_count=5` (ou o valor no momento do disparo).
2. Derrubar/restaurar a rede do device → 1 linha `connectivity_restored` com
   `offline_duration_seconds` condizente + 1 `heartbeat`/`state_change` junto.
3. Deixar o device sem nenhum scan pelo tempo configurado em "Heartbeat Inteligente" →
   1 `heartbeat`/`idle_timeout`.
4. Marcar `heartbeat_requested=true` na resposta do RPC pra um device de teste → confirmar
   `heartbeat`/`requested` até 1h depois (ou forçar o ciclo manualmente em teste).
