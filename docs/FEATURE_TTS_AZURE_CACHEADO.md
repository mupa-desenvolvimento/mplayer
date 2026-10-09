# TTS Azure com cache compartilhado (R2 + Supabase)

Data: 2026-10-09

## Pedido

"Quero implementar o TTS do bing... mas não quero gastar, conseguimos
criar uma forma de salvar os preços que vai gerar, reaproveitando os
preços que ela vai dizer... podemos ter uma tabela só pra isso, storage
em nosso R2 mesmo." (ver `docs/Request_TTS_BING.txt` — exemplo original
de chamada à API de voz da Azure/Microsoft Cognitive Services).

## O que já existia (nada reinventado à toa)

- `OfferIntelligence.kt` já calcula a frase certa de oferta
  (`ttsPhrase`), ex. `"De R$ 9,88 por R$ 7,59."`.
- `engage-tts/.../EngageTts.kt` já tinha a lógica de chamar a Azure
  (token + síntese), mas só é usada numa tela separada de engajamento
  (`EngageActivity`), com a chave da Azure hardcoded no código-fonte, e
  SEM nenhum cache — cada chamada gera e descarta o áudio.
- A tela de preço (`PlayerActivity.speakPriceIfPossible` e as outras 2
  funções de fala) usava o `TextToSpeech` nativo do Android (grátis,
  robótico, zero custo mas também zero qualidade).

## Decisões de arquitetura (confirmadas com o usuário antes de implementar)

1. **Síntese roda numa Supabase Edge Function**, não no app. A chave da
   Azure e as credenciais do R2 nunca vão pro APK — corrige de quebra a
   exposição que já existia no `EngageTts.kt` (não mexido nesta rodada,
   fica como dívida técnica separada).
2. **Fallback pro TTS nativo do Android** sempre que a voz Azure não
   puder ser usada (sem internet, cache frio E a function fora do ar) —
   a loja nunca fica muda.

## Arquitetura

```
PlayerActivity (fala "De R$ 9,88 por R$ 7,59.")
  └─> PriceVoiceSynth.speak(texto)
        1. arquivo local?  filesDir/tts_cache/<hash>.mp3           → toca, fim (zero rede)
        2. linha na tabela (Supabase REST, leitura direta)?        → baixa do R2, toca, fim
        3. Edge Function tts-synthesize (texto, voz)                → sintetiza+sobe+grava, devolve URL
        4. qualquer falha em 1-3                                    → onFallback() = TextToSpeech nativo
```

A CHAVE do cache é `sha256(voiceName + "|" + text)` — **não** o EAN nem o
preço em si. Duas lojas diferentes anunciando a mesma frase ("De R$ 9,88
por R$ 7,59.") reusam o MESMO arquivo de áudio. Isso é o que faz "não
gastar": a Azure só é chamada na PRIMEIRA vez que uma frase exata aparece
em QUALQUER dispositivo da frota inteira.

### Banco (Supabase) — `supabase/migrations/20261009000000_create_tts_audio_cache.sql`

Tabela `tts_audio_cache`: `text_hash` (PK), `voice_name`, `text` (só pra
debug), `r2_key`, `public_url`, `use_count`, `created_at`,
`last_used_at`. RLS habilitada: SELECT liberado pro mesmo token
"authenticated" que o app já usa em outras tabelas (`price_query_events`
etc.); **sem policy de INSERT/UPDATE** — só a Edge Function (service
role, que ignora RLS) grava.

### Edge Function — `supabase/functions/tts-synthesize/index.ts`

Mesmo estilo/runtime da única function já existente
(`audience-analytics`). Recebe `{text, voiceName?}`, calcula o hash,
confere a tabela — se achar, devolve a `public_url` e incrementa
`use_count` (fire-and-forget); se não achar, pega token da Azure
(cacheado em memória do processo por 9min, mesmo princípio do
`EngageTts.kt`), sintetiza (MP3 24kHz/48kbps, mesmo formato já usado no
engage-tts), sobe pro R2 (`tts-cache/<hash>.mp3`, `S3Client` +
`requestChecksumCalculation: "WHEN_REQUIRED"` — mesmo achado de produção
já documentado no gateway do argos-remote, sem isso o PUT pro R2 pode
voltar 403), grava a linha (`upsert` por `text_hash`, pra não colidir se
duas lojas sintetizarem a mesma frase ao mesmo tempo) e devolve a URL
nova.

### Android — `app/src/main/java/com/mupa/player/enterprise/price/PriceVoiceSynth.kt`

Classe nova, standalone (não reaproveita `engage-tts`, que fica intacto
pra outra tela). Cache local é só arquivo em disco
(`filesDir/tts_cache/<hash>.mp3`) — **sem tabela Room nova**: o nome do
arquivo já é a chave, não precisa de índice separado. `speak(text,
onFallback)` tenta os 3 níveis em sequência e cai pro `onFallback()` em
qualquer falha.

`PlayerActivity.kt`: os 3 pontos que falavam preço/produto
(`speakPriceIfPossible`, `speakSlotsIfPossible`, `speakNotFoundIfPossible`)
agora chamam `speakWithVoiceSynth(texto)` — função nova que tenta
`priceVoiceSynth.speak(texto) { speakNative(texto) }`. `speakNative` é
exatamente o código antigo (TextToSpeech), preservado como fallback.
Debounce por EAN (3500ms/2500ms) continua idêntico, só o "como fala"
mudou.

## Segredos necessários (Edge Function, nunca no APK)

`AZURE_TTS_SUBSCRIPTION_KEY`, `AZURE_TTS_REGION` (default `brazilsouth`),
`R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`,
`R2_PUBLIC_BASE_URL` — reaproveitando a MESMA conta R2 já usada pelo
`argos-remote` (nomes de env var idênticos, confirmado contra o `.env` da
VPS). `SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` são injetados
automaticamente pelo Supabase em toda Edge Function, não precisam ser
configurados.

## Deploy (pendente — Supabase CLI não estava autenticado nesta sessão)

```bash
npx supabase login
npx supabase link --project-ref iurqddkuihjsmxubibao
npx supabase db push
npx supabase functions deploy tts-synthesize
npx supabase secrets set \
  AZURE_TTS_SUBSCRIPTION_KEY=<a chave do docs/Request_TTS_BING.txt> \
  AZURE_TTS_REGION=brazilsouth \
  R2_ENDPOINT=<mesmo valor do .env da VPS argos-remote> \
  R2_ACCESS_KEY_ID=<idem> \
  R2_SECRET_ACCESS_KEY=<idem> \
  R2_BUCKET=<idem> \
  R2_PUBLIC_BASE_URL=<idem>
```

## Teste

- `:app:compileLegacyDebugKotlin` — BUILD SUCCESSFUL, zero erro em
  `PriceVoiceSynth.kt` ou nas linhas alteradas de `PlayerActivity.kt` (só
  warnings pré-existentes em outros arquivos).
- TypeScript da Edge Function validado via `esbuild` + `node --check`
  (sintaxe OK — Deno não está instalado neste ambiente pra um type-check
  completo).
- **Achado à parte**: `:mplayer_renner:compileLegacyDebugKotlin` falha
  neste ambiente (`Unresolved reference: br`/`CodeScanner`/
  `ScannerCallback` em `GertecScannerManager.kt`) — confirmado via `git
  stash` que é PRÉ-EXISTENTE, não relacionado a esta feature (falta
  provavelmente o AAR do SDK da Gertec neste ambiente de dev). Não
  corrigido, fora de escopo.
- **Pendente**: teste ao vivo num MPlayer de verdade (depende do deploy
  Supabase acima estar feito) — escanear um EAN, confirmar que a primeira
  vez soa como voz Azure (não a robótica), confirmar que uma segunda
  chamada da MESMA frase (outro device ou o mesmo, reiniciado) não gera
  uma chamada nova à Azure (ver `use_count` na tabela/logs da function).
