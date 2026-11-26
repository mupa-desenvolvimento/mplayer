# Diagnóstico: `device-api-v2/manifest` retorna "Device not found" (HTTP 404) mesmo com o dispositivo cadastrado

## REGRESSÃO (2026-11-24): o 404 voltou — mesmo bug do overflow de int4, de novo

O fix abaixo (v65) foi confirmado funcionando nesta mesma sessão, com curl direto, por volta das
12:xx de 2026-11-23. Ao testar de novo em 2026-11-24 (~02:15 UTC, pedido do usuário pra validar
"Consulta Patrocinada" com a Tag "Loja 18", que é automática pro `num_filial=18` — exatamente o
SK-210), **o mesmo 404 voltou**:

```
POST .../device-api-v2/manifest {"serial":"4001282408002517"} -> HTTP 404 {"error":"Device not found"}
```

Confirmado que **não é o device/linha que sumiu** — `get_dispositivo_por_serial` (RPC) continua
achando a linha normalmente (`id: 1533`, `atualizado: 2026-11-24T02:12:45Z` — ou seja, bem recente,
a linha está viva e sendo atualizada). Confirmado que **não é o endpoint inteiro fora do ar** — o
device de controle `C88AD8E8E4C9` (serial com letras) continua respondendo `200` normalmente.

**É uma regressão geral do mesmo bug**, não um caso isolado do SK-210: testei mais 3 seriais
puramente numéricos que já tinham sido citados como afetados no relatório de resolução original
(`4001132263002774`, `4001132263003445`, `4001132263002233`) — **todos voltaram a dar 404**.

Hipótese mais provável: um deploy novo da `device-api-v2` (possivelmente o mesmo que adicionou
`sponsored_lookups`/Tags, já que os dois foram entregues pelo mesmo time próximos no tempo) reverteu
ou quebrou de novo o fix do filtro `.or(...)` que excluía `id.eq.${serial}` pra serial fora do range
de `int4`. **Precisa ser escalado pro time do Mupa Connect com prioridade** — isso está afetando
dispositivos reais em produção agora (todo serial puramente numérico de 14-16 dígitos), não só o
SK-210 de teste.

Quem mantém o backend (`device-api-v2`) respondeu com o diagnóstico completo — ver
[docs/RESPOSTA_DIAGNOSTICO_DEVICE_API_V2_404_E_MELHORIAS_MPLAYER.md](RESPOSTA_DIAGNOSTICO_DEVICE_API_V2_404_E_MELHORIAS_MPLAYER.md).
A causa raiz estava exatamente onde a seção "ATUALIZAÇÃO (2026-11-23)" abaixo apontava: dentro do
código da própria Edge Function, não no banco.

**Causa exata**: o filtro `.or(...)` da função anexava `id.eq.${serial}` sempre que o serial era
puramente numérico (`/^\d+$/`). Como `dispositivos.id` é `int4` (máximo `2.147.483.647`) e o serial
do SK-210 tem 16 dígitos, o Postgres rejeitava a query inteira com `22003: value ... out of range for
type integer` — um erro `400` interno que a function mascarava como `404 "Device not found"`. Por
isso `C88AD8E8E4C9` (tem letras, nunca entrava nesse filtro) sempre funcionou, e qualquer serial
numérico de 14-16 dígitos (não só o SK-210) sempre falhava — ~24.599 erros 400 + 25.814 respostas 404
por dia na frota inteira.

**Fix**: `device-api-v2` v65 só inclui `id.eq.${serial}` quando o serial cabe em `int4`
(`/^\d{1,10}$/.test(serial) && Number(serial) <= 2147483647`). Verificado ao vivo nesta sessão via
curl direto (fora do dispositivo físico, que não estava conectado no momento): `POST .../manifest
{"serial":"4001282408002517"}` → `HTTP 200`, `success:true`, `playlist_id` presente.

O mesmo diagnóstico (logs de produção) revelou e corrigiu uma série de problemas correlacionados —
ver o documento de resposta pra detalhes completos: `device_tags` recebendo `device.id` (integer) em
vez de `device.device_uuid` (uuid); 6 players legados em loop infinito contra `device_commands`
(18.485 comandos pending); `device_events` inexistente; `get_dispositivo_por_serial` lançando
exceção em vez de retornar null; sync managers sem idempotência (`media_play_logs`, `device_events`).

**Pendente (cliente MPlayer, P0-P2 no documento de resposta)**: backoff exponencial no retry do
manifesto (hoje é `3s` fixo pra sempre, mesmo em 404/400 determinístico); preservar status HTTP +
mensagem de erro real em `ManifestManager.fetchManifest()` (hoje descarta tudo em
`runCatching{}.getOrDefault("")`); idempotência padronizada nos demais Sync Managers; comparação
semântica de manifesto em vez de raw-string. Nenhuma dessas foi implementada ainda — discutir com o
usuário qual(is) priorizar antes de começar.

---

**Dispositivo de teste:** SK-210, serial `4001282408002517` (fica fisicamente conectado à máquina de testes, apelido "Sk210").
**Empresa de teste:** `101MUP` / "Mupa Testes" (`company_id = a7794ee1-4bf7-47e9-aa2b-b685ab4533e9`, `tenant_id = 56940f6d-f147-4534-a7ad-0ef300f95b8a`).
**Endpoint afetado:** `POST https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/manifest`, body `{"serial": "<serial>"}`.
**Sintoma:** o MPlayer nunca sai de "Não foi possível obter a programação. Tentando novamente...". O endpoint sempre responde `{"error":"Device not found"}` com HTTP 404, em retry contínuo (a cada ~3s).

## ATUALIZAÇÃO (2026-11-23): a hipótese do `playlist_id` foi REFUTADA — leia isto primeiro

A seção "Atualização — causa confirmada via SELECT direto" mais abaixo concluiu que `playlist_id IS
NULL` na linha do SK-210 (`id 1533`) era a causa, e recomendava o UPDATE abaixo como fix imediato.
**Esse UPDATE foi executado e CONFIRMADO via SELECT** (o `playlist_id` da linha passou a ser
`785ab823-5369-404f-a6ce-80a154f66175`, idêntico ao de `id 1531`). **O manifesto continuou devolvendo
exatamente o mesmo 404 "Device not found" depois do fix.** Ou seja: `playlist_id` nulo pode até ser um
problema real (vale manter corrigido), mas **não é a causa do 404** — ou não é a causa sozinho.

Depois disso, foi feita uma comparação **campo a campo, linha completa**, entre `id 1531` (funciona) e
`id 1533`/SK-210 (não funciona). Resultado: **todo campo que poderia influenciar a lógica de negócio é
idêntico nas duas linhas** — `empresa`, `company_id`, `tenant_id`, `grupo_dispositivos`
("PlaylistPadrão" nos dois), `playlist_id` (igual, pós-fix), `current_playlist_id` (null nos dois),
`is_licensed` (`true` nos dois), `environment` ("production" nos dois), `type`/`tipo_da_licenca`
("ST103" nos dois), `is_maintenance` (`false` nos dois), `appearance_config` (`{}` nos dois),
`device_type`/`sector_id`/`model`/`android_version`/`notes` (null nos dois), `online` (`true` nos
dois), `pin` ("101MUP" nos dois), `apps_instalados`/`campanhas` (`[]` nos dois), `ip_dispositivo` ("" nos
dois), `comando`/`last_proof_at`/`store_id`/`external_id` (null nos dois). As únicas diferenças são
campos que DEVEM ser diferentes por definição: `id`, `apelido_interno`, `serial`, `num_filial`,
`device_uuid`, `player_status`, timestamps.

Mais 3 hipóteses testadas e também descartadas, com acesso de service role/SQL direto:

- **Caractere invisível/lixo no `serial`:** `encode(serial::bytea, 'hex')` decodificado manualmente deu
  exatamente os 16 caracteres ASCII esperados, sem padding, espaço ou caractere de controle. Não é isso.
- **Tabela/view secundária fazendo o "registro" de verdade:** busca em `information_schema.views` e
  `information_schema.routines` por "manifest"/"device" não encontrou nenhuma view nem function
  relacionada — não existe lógica de manifesto implementada em SQL/view. Busca por todas as tabelas que
  referenciam `serial`/`device_id`/`device_uuid` no schema inteiro só achou tabelas de log/analytics
  (audit_logs, device_commands, device_heartbeat, impression_logs, media_play_logs etc.) e 2 tabelas de
  backup/import — nenhuma é um "gate" de cadastro.
- **RLS bloqueando a própria Edge Function:** `dispositivos` tem `relrowsecurity = true` mas
  `relforcerowsecurity = false` — ou seja, RLS está ativo mas NÃO forçado, o que significa que o
  `service_role` (papel que qualquer Edge Function usa para acessar o banco) **ignora RLS
  automaticamente**, independente das 3 policies existentes na tabela. RLS não pode ser a causa.

**Conclusão desta rodada: o lado do banco de dados está 100% exonerado.** A linha do SK-210 é
indistinguível, campo a campo, de uma linha que funciona. A causa só pode estar dentro do código
TypeScript/Deno da própria Edge Function `device-api-v2` (lógica, não dado) — código que **não existe em
nenhum repositório desta máquina** (confirmado por busca recursiva em todos os `supabase/functions` de
todos os repos em `C:\Users\rdpadmin\src\`: existem functions em `argus-device-hub`, `mplayer`,
`mplayer-studio`, `mplayer_x96`, nenhuma chamada `device-api-v2`).

**O que falta, e só quem tem acesso ao projeto Supabase consegue fazer:**
1. Abrir o Dashboard do Supabase → Edge Functions → `device-api-v2` → aba "Code" (mostra o TypeScript
   publicado, se o deploy permitir visualização pelo dashboard).
2. OU rodar `supabase functions download device-api-v2 --project-ref iurqddkuihjsmxubibao` com o
   Supabase CLI instalado e autenticado.
3. Ler a função procurando exatamente onde ela decide "Device not found" — provavelmente um
   `.select(...).single()`/`.maybeSingle()` seguido de `if (!device) return 404`, mas o filtro exato da
   query (quais colunas, quais condições WHERE além do serial) é o que precisa ser visto no código de
   verdade, já que todo campo de dado já foi eliminado como causa.
4. Os logs da Edge Function (puxados do Dashboard nesta investigação) não ajudam: só mostram a linha
   genérica `[Device-API-V2] Request: POST manifest` repetida, sem detalhe de decisão por request.

O log de Edge Function também confirmou algo não relacionado à causa, mas relevante pro volume: a
função recebe requisições de `POST manifest` a cada ~20-300ms (produção real tem muitos dispositivos
fazendo polling concorrente) — não há indício de rate-limit ou cache "não encontrado" afetando
especificamente este serial, mas isso também não pôde ser confirmado nem descartado sem ver o código.

## O que foi tentado (nesta sessão, todas as tentativas reproduzidas ao vivo no SK-210 físico)

1. **Recadastro via wizard real do app** (`DeviceRegistrationActivity`, código da empresa `101MUP`, apelido, filial, tag) — gera um `UPDATE`/upsert na linha existente (mesmo `id`, já que `create_dispositivo` faz upsert por `serial`). Resultado: `company_id`/`tenant_id` corretos confirmados no cache local do app (via `get_dispositivo_por_serial`), mas o manifesto continuou 404.
2. **Forçar um INSERT genuíno** (apagar a linha antiga direto no banco via SQL e repetir o cadastro pelo wizard) — gerou de fato um `id` novo (confirmado: o `device_db_id` mudou), com `company_id`/`tenant_id` corretos desde o início. Resultado: manifesto continuou 404 mesmo ~45s depois.
3. **Hard reset completo do aparelho via ARGOS Remote** (`wipeData` com aprovação de senha mestre) + recadastro automático. O Device Owner e os 2 apps geridos (MPlayer, ARGOS Remote) sobreviveram ao reset "seguro" do ARGOS (confirmado via uptime do aparelho ~7,6min pós-reset e `pm list packages -3` só mostrando os 2 pacotes esperados — ou seja, o reset realmente aconteceu, só que o ARGOS tem uma re-provisão automática embutida). O MPlayer recadastrou sozinho (mesma linha de antes) e, de novo, precisou do wizard manual pra ficar com `company_id`/`tenant_id` certos. Resultado: 404 continua.

Em nenhuma das 3 tentativas o campo `company_id`/`tenant_id`/`pin` incorreto ou desatualizado foi a causa real — o problema persiste mesmo com os dados 100% corretos e uma linha recém-criada.

## Achado decisivo: a tabela `dispositivos` não é visível via SELECT direto com a anon key

Foi adicionado um diagnóstico temporário em `ManifestManager.fetchManifest()` (revertido depois, ver nota no fim) que, a cada falha do manifesto, dispara 3 queries extras via REST (PostgREST), usando o MESMO token anon que o app já usa (`BuildConfig.SUPABASE_TOKEN`):

```
GET /rest/v1/dispositivos?serial=eq.4001282408002517&select=*
GET /rest/v1/dispositivos?company_id=eq.a7794ee1-4bf7-47e9-aa2b-b685ab4533e9&select=id,serial,apelido_interno,tenant_id,company_id,online&limit=10
GET /rest/v1/dispositivos?select=id&limit=1   -- sem NENHUM filtro
```

**As 3 voltaram `[]` (array vazio, HTTP 200)** — inclusive a terceira, que não filtra por serial nem empresa nenhuma. Ou seja: com a anon key, **nenhuma linha da tabela `dispositivos` é visível via SELECT direto**, para nenhum dispositivo, de nenhuma empresa. Isso é consistente com RLS (Row Level Security) bloqueando `SELECT` pra esse role — bem possivelmente *intencional* (impedir que o anon key, que fica embutido em todo APK, consiga listar a frota inteira de dispositivos de qualquer empresa).

**Isso prova que o app em si está se comportando certo** — ele nunca teve acesso de leitura direto a essa tabela, sempre dependeu só das RPCs (`create_dispositivo`, `get_dispositivo_por_serial`), que aparentemente rodam com privilégio elevado (`SECURITY DEFINER` ou equivalente) e por isso conseguem ver/escrever a linha mesmo com RLS ativo.

## Atualização — causa confirmada via SELECT direto (service role), comparando as 2 linhas

Depois de escrito o diagnóstico acima, testamos direto por curl (anon key) que `device-api-v2/manifest`
**funciona perfeitamente para outro dispositivo da MESMA empresa/tenant** (`C88AD8E8E4C9`,
"TV_TESTE_BANCADA", `company_id`/`tenant_id` idênticos aos do SK-210) — isso descarta de vez a teoria de
RLS bloqueando a Edge Function inteira (achado da seção anterior): a function tem acesso de leitura
normal, só não encontra ESTE serial específico.

Isso deixou só uma pergunta: qual campo diferencia a linha que funciona (`id 1531`) da que não funciona
(`id 1533`, o SK-210)? Quem mantém o banco rodou, com acesso de service role, 2 SELECTs comparando as
duas linhas lado a lado:

```sql
SELECT id, serial, is_licensed, licensed_at, environment, tenant_id, device_uuid, company_id
FROM dispositivos WHERE id IN (1531, 1533);

SELECT id, serial, grupo_dispositivos, playlist_id, current_playlist_id, store_id,
       sector_id, is_maintenance, model, android_version, device_type
FROM dispositivos WHERE id IN (1531, 1533);
```

**Primeira hipótese testada e REFUTADA:** `is_licensed`, `licensed_at`, `environment`, `tenant_id` e
`company_id` saíram **idênticos** nas duas linhas (`is_licensed: true` em ambas) — não é isso.

**A diferença real, única entre as duas linhas:**

| campo | 1531 (funciona) | 1533 — SK-210 (não funciona) |
|---|---|---|
| `grupo_dispositivos` | `"PlaylistPadrão"` | `"PlaylistPadrão"` (igual) |
| `playlist_id` | `"785ab823-5369-404f-a6ce-80a154f66175"` | **`null`** |
| `current_playlist_id` | `null` | `null` (igual) |
| `store_id`, `sector_id`, `is_maintenance`, `model`, `android_version`, `device_type` | todos iguais (null/false nos dois) | idem |

**Causa confirmada:** `playlist_id` está `null` na linha do SK-210. O campo `grupo_dispositivos` (uma
string, `"PlaylistPadrão"`) é o único relacionado a playlist que a RPC `create_dispositivo`
(`DeviceRegistrationService.kt`, usada pelo wizard do app) manda no cadastro — ela NUNCA manda
`playlist_id` diretamente. Alguma coisa (trigger, job agendado, ou um passo manual de vinculação feito
só quando o dispositivo é criado direto pelo painel/SQL) é responsável por resolver o nome do grupo
("PlaylistPadrão") pra um `playlist_id` de verdade, e isso nunca aconteceu pra esta linha — nas 3
tentativas desta sessão (upsert, insert novo via SQL+wizard, hard reset + recadastro automático), o
cadastro sempre passou pela RPC do app, nunca por esse outro caminho que resolve o `playlist_id`.
`device-api-v2/manifest` muito provavelmente trata "sem playlist resolvida" como "nada pra mostrar" e
devolve o mesmo erro genérico `"Device not found"` em vez de um erro mais específico tipo "sem
playlist vinculada".

## O que isso implica sobre a causa real (hipóteses descartadas/secundárias, mantidas por completude)

Como a Edge Function `device-api-v2/manifest` roda no servidor, ela quase certamente usa a **service role key** (não a anon key) pra consultar o banco — então o bloqueio de RLS acima não deveria, em teoria, afetá-la. Ou seja, **o diagnóstico acima não prova o que está quebrado dentro da Edge Function**, só prova que não é um problema de dado incorreto na linha (campo faltando, empresa errada, etc.) nem de permissão do lado do app/anon key.

Como o mesmo serial, cadastrado corretamente (confirmado 3 vezes, de 3 formas diferentes: upsert, insert novo, reset completo + recadastro) continua dando "Device not found", a causa mais provável está em um destes pontos **dentro da própria Edge Function `device-api-v2/manifest`** (código não presente em nenhum repo desta máquina):

1. A função pode estar consultando uma **tabela ou view diferente** de `dispositivos` (ex. uma view materializada, um cache, uma tabela de "dispositivos ativos" que só é populada por outro processo/trigger que não dispara em cima do upsert do `create_dispositivo`).
2. A função pode ter algum filtro adicional nessa consulta que essa linha específica não atende silenciosamente (ex. `is_licensed = true`, algum campo de ambiente/`environment`, alguma condição de tenant que não é só "tenant_id = X").
3. Pode haver uma chave de cache (Redis, memória, CDN) na frente dessa função que guarda "não encontrado" por tempo indeterminado pra esse serial específico, e só um TTL vencer (ou uma invalidação manual) resolveria — isso explicaria por que nenhuma das 3 tentativas (que mudam só o banco, não esse possível cache) teve efeito.

## Requests pra reproduzir (curl)

Substitua `SUA_ANON_KEY_AQUI` pela anon key do projeto Supabase (`iurqddkuihjsmxubibao`) — não incluída aqui de propósito.

```bash
ANON_KEY="SUA_ANON_KEY_AQUI"

# 1) A request que falha (manifesto) — devolve {"error":"Device not found"} / HTTP 404
curl -X POST "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/manifest" \
  -H "apikey: $ANON_KEY" \
  -H "Authorization: Bearer $ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"serial":"4001282408002517"}'

# 2) Prova de que a anon key não enxerga NENHUMA linha de dispositivos via SELECT direto — devolve []
curl "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/dispositivos?select=id&limit=1" \
  -H "apikey: $ANON_KEY" \
  -H "Authorization: Bearer $ANON_KEY"

# 3) A mesma linha específica, por serial — também devolve []
curl "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/dispositivos?serial=eq.4001282408002517&select=*" \
  -H "apikey: $ANON_KEY" \
  -H "Authorization: Bearer $ANON_KEY"

# 4) Controle positivo: MESMA empresa/tenant, outro dispositivo de teste — funciona (200, success:true)
curl -X POST "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/manifest" \
  -H "apikey: $ANON_KEY" \
  -H "Authorization: Bearer $ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"serial":"C88AD8E8E4C9"}'

# 5) RPC que o app usa pra validar/cachear o cadastro — devolve a linha do SK-210, mas com bem menos
#    campos que o objeto "device" dentro da resposta #4 (sem is_licensed/tenant_id/device_uuid/etc)
curl -X POST "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/rpc/get_dispositivo_por_serial" \
  -H "apikey: $ANON_KEY" \
  -H "Authorization: Bearer $ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"p_serial":"4001282408002517"}'
```

## Recomendação pra quem mantém o backend

**O fix de `playlist_id` abaixo já foi aplicado e NÃO resolveu — não repetir como se fosse a solução.**
Ele continua documentado porque pode ser um problema real e independente (um dispositivo sem playlist
resolvida é um estado ruim de qualquer forma), só não é a causa do 404:

```sql
-- Já executado nesta investigação (id/serial do SK-210), confirmado via SELECT, não resolveu o 404:
UPDATE dispositivos SET playlist_id = '785ab823-5369-404f-a6ce-80a154f66175'
WHERE serial = '4001282408002517';
```

**O que precisa ser investigado agora (única via que resta):** a comparação campo-a-campo entre a
linha que funciona (`id 1531`) e a do SK-210 (`id 1533`) não encontrou NENHUMA diferença de dado
relevante (ver seção "ATUALIZAÇÃO" no topo deste documento) — todos os campos de negócio são idênticos.
RLS foi descartado (não está "forced", `service_role` ignora). Não existe view/function SQL
implementando a lógica de manifesto. O serial não tem lixo/caractere invisível. **A causa só pode estar
dentro do código da própria Edge Function `device-api-v2`**, que não está em nenhum repositório local —
só quem tem acesso ao projeto Supabase (`iurqddkuihjsmxubibao`) pode:

1. Abrir Dashboard → Edge Functions → `device-api-v2` → aba "Code" (ou `supabase functions download
   device-api-v2 --project-ref iurqddkuihjsmxubibao` via CLI) e ler a query/condição real que decide
   "Device not found" para este serial — ela usa algum filtro, coluna ou junção que a comparação de
   linha completa acima não revela por ser só dado, não lógica.
2. Considerar também: uma camada de **cache** (Redis, memória do runtime, CDN) na frente da function
   guardando "não encontrado" por esse serial específico há algum tempo, sem relação com o estado atual
   do banco — isso explicaria por que nenhuma mudança de dado (upsert, insert novo, hard reset +
   recadastro, fix do playlist_id) teve efeito. Um TTL vencer ou uma invalidação manual resolveria sem
   precisar mudar código.
3. Depois de achar a causa real, `device-api-v2/manifest` deveria devolver um erro mais específico
   (ex. "sem playlist vinculada", "cache stale") em vez do mesmo 404 genérico "Device not found" — teria
   economizado toda essa investigação (agora com 2 rodadas e ~6 hipóteses descartadas).

## Nota técnica: diagnóstico temporário revertido

O código de diagnóstico (logs `ManifestDebug`/`RowDebug` em `ManifestManager.kt`) foi usado só pra esta investigação e não foi commitado — reverter antes de qualquer novo build de produção/homologação.
