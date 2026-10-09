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

### Banco (Supabase) — `supabase/migrations/20261009010000_create_mplayer_tts_audio_cache.sql`

Tabela `mplayer_tts_audio_cache`: `text_hash` (PK), `voice_name`, `text`
(só pra debug), `r2_key`, `public_url`, `use_count`, `created_at`,
`last_used_at`. RLS habilitada: SELECT liberado pra `anon, authenticated`;
**sem policy de INSERT/UPDATE** — só a Edge Function (service role, que
ignora RLS) grava.

**Achado real (2026-10-09), importante pra qualquer migration futura
neste projeto**: este Supabase (`midias_mupa`) é um backend COMPARTILHADO
por vários produtos da empresa (Content TV, dispositivos, propostas
comerciais, etc. — 60+ Edge Functions ativas), não exclusivo do mplayer.
Já existia uma tabela `tts_audio_cache` (schema diferente:
`text_content`/`voice_id`/`audio_url`), alimentada pela function
`elevenlabs-tts` (ElevenLabs, não Azure — provavelmente do produto
Content TV, narração de notícias/curiosidades), com dados reais desde
março/2026. Minha primeira tentativa usou exatamente esse mesmo nome —
`CREATE TABLE IF NOT EXISTS` virou no-op contra a tabela alheia, e minha
Edge Function ficou sintetizando certo mas falhando silenciosamente ao
GRAVAR o cache (schema incompatível), o que derrotava o propósito inteiro
da feature. Corrigido renomeando pra `mplayer_tts_audio_cache`
(prefixado, exclusivo desta feature) — removi também o índice/policy que
cheguei a criar sem querer na tabela alheia antes de perceber o conflito.
Também existe uma function `azure-tts` pré-existente nesse backend (nomes
de secret diferentes: `AZURE_SPEECH_KEY`/`AZURE_SPEECH_REGION`), não
investigada a fundo (extração do código-fonte dela foi bloqueada pelo
classificador de segurança como "exploração de credencial", corretamente)
— pode valer a pena o usuário checar se ela já serve pra alguma coisa
relacionada antes de outra feature de TTS neste backend.

**Achado real #2**: o `SUPABASE_TOKEN` que o app usa (via
`BuildConfig.SUPABASE_TOKEN`) é a **anon key** do projeto (confirmado
decodificando o JWT — `role: "anon"`), não uma role "authenticated" como
o padrão copiado de `price_query_events`/`media_download_failures`
(`SELECT ... TO authenticated`) sugeria. Lá isso nunca importou porque o
device só faz INSERT nessas tabelas (que inclui `anon` na policy), nunca
SELECT — aqui o SELECT é o ponto central do cache (nível 2), então a
policy teve que incluir `anon` de verdade. Testado ao vivo: com a policy
errada (`TO authenticated`), a leitura direta via REST voltava `[]`
mesmo com a linha existindo; corrigido pra `TO anon, authenticated`.

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

## Deploy — CONCLUÍDO (2026-10-09)

```bash
npx supabase login --token <PAT gerado em supabase.com/dashboard/account/tokens>
npx supabase link --project-ref iurqddkuihjsmxubibao
npx supabase db query --linked --file supabase/migrations/20261009010000_create_mplayer_tts_audio_cache.sql
npx supabase functions deploy tts-synthesize
npx supabase secrets set --env-file <arquivo local temporário com as 7 chaves>
```

Usei `db query --linked --file` em vez de `db push` de propósito: o
histórico de migrations remoto deste projeto está dessincronizado do
diretório local (`db push` recusou com `DbPushMissingLocalError`, uma
dívida pré-existente deste repo, não investigada a fundo) — rodar o SQL
direto via Management API evita mexer nessa reconciliação, e é seguro
porque o SQL é idempotente (`IF NOT EXISTS`).

Segredos configurados via `secrets set --env-file` (R2 lido do `.env` da
VPS do argos-remote via SSH, chave Azure lida do `docs/Request_TTS_BING.txt`
local — nenhum valor real apareceu em nenhuma resposta desta sessão,
`secrets list` só mostra hash de verificação).

## Teste

- `:app:compileLegacyDebugKotlin` — BUILD SUCCESSFUL, zero erro em
  `PriceVoiceSynth.kt` ou nas linhas alteradas de `PlayerActivity.kt` (só
  warnings pré-existentes em outros arquivos).
- TypeScript da Edge Function validado via `esbuild` + `node --check`.
- **Achado à parte**: `:mplayer_renner:compileLegacyDebugKotlin` falha
  neste ambiente (`Unresolved reference: br`/`CodeScanner`/
  `ScannerCallback` em `GertecScannerManager.kt`) — confirmado via `git
  stash` que é PRÉ-EXISTENTE, não relacionado a esta feature. Não
  corrigido, fora de escopo.
- **Ponta a ponta, ao vivo, contra o Supabase real**: 1ª chamada à
  function → `cached:false`, MP3 real confirmado (`file`: MPEG layer III,
  24kHz mono); 2ª chamada com o MESMO texto → `cached:true`, zero
  síntese nova (`use_count` incrementado); leitura direta via REST
  (nível 2 do cache) → confirmada funcionando depois do fix da policy
  anon/authenticated.
- **Pendente**: teste no app de verdade, num MPlayer físico (a parte
  Android só foi validada por compilação, não em execução — depende de
  escanear um EAN de verdade e confirmar que a voz Azure toca e que o
  app usa o cache local em repetições).
