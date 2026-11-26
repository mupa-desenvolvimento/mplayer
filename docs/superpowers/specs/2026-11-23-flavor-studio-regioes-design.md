# Novo flavor "studio" do MPlayer — pareamento com Mupa Studio pra posicionar blocos do layout de preço

## Intenção

Hoje, quando o MPlayer mostra o resultado de uma consulta de preço, a posição de cada bloco
(imagem, nome, preço, oferta) é fixa — vem do `xmlLayoutType` escolhido (split, atacado, de_por,
multi_price etc.) e de uma lógica hardcoded de orientação (`applyPriceOverlayLayout`). O pedido é
deixar o Mupa Studio (ferramenta local já usada pra outras coisas) ajustar a posição desses blocos
por cliente, sem precisar de um backend novo: o aparelho entra em **Configurações**, digita
IP+código de pareamento do computador rodando o Studio, e a partir daí qualquer região que o Studio
salvar pra esse aparelho é aplicada ao vivo.

## Decisões já confirmadas com o usuário

1. **Novo flavor Gradle deste mesmo repo `mplayer`** (não um repo separado) — reaproveita 100% do
   código real, como já é feito hoje com `legacy`/`modern`/`komprao`/`unidasul`. Os 4 flavors atuais
   ficam **idênticos a hoje** (o novo recurso só existe no flavor novo).
2. **O Studio controla só a posição dos blocos dentro do XML já escolhido** — não troca qual
   `xmlLayoutType` é usado, não é um editor visual livre tipo DivKit. É exatamente o que um plano
   anterior (ainda não implementado) já tinha desenhado como "Fase 2" — ver
   `C:\Users\rdpadmin\.claude\plans\synthetic-drifting-seal.md`, seção Fase 2 — só que agora o
   pareamento é direto por IP+código (sem backend), em vez de vir embutido no manifesto.

## O que já existe e vai ser reaproveitado (confirmado por leitura direta do código)

- **Protocolo de pareamento WebSocket já existe e já roda em produção** em dois lugares diferentes
  (`mplayer-studio/app/.../studio/LiveStudioLink.kt` e `MupaStudio/apps/companion/.../StudioLink.kt`)
  — os dois são **byte-idênticos** na parte de pareamento:
  - `Pairing(host, session)`, deep link `mupa-studio://pair?h=<host>&s=<session>`.
  - URL do WebSocket: `ws://$host/studio-ws?role=viewer&session=<code>` (sem TLS — rede local).
  - Reconexão com backoff linear (`1s × tentativa`, teto 10s) e um contador de geração que descarta
    callbacks de um socket antigo depois de um `disconnect()`/reconexão.
  - Mensagens JSON simples (`{"type": "layout", ...}`, `{"type": "presence", "studio": bool}` etc.),
    heartbeat feito pelo próprio OkHttp (`pingInterval(15s)`), sem ping/pong manual.
- **O relay do lado do Studio** (`MupaStudio/apps/web/relay.ts`) já aceita exatamente esse protocolo,
  já valida o código de sessão no upgrade do WebSocket (403 se não bater), e já faz broadcast pra
  todos os viewers conectados daquela sessão sempre que o "publisher" (a aba do Studio) manda um
  `{"type":"layout", "json":...}`. Pra essa feature nova, o "json" que o Studio vai mandar não é
  mais um card DivKit — vai ser o JSON das 4 regiões (ver schema abaixo). Isso é reaproveitamento de
  protocolo, não mudança no relay.
- **Já existe uma classe de região retangular** (frações 0..1 da tela) no próprio `mplayer`: `
  SponsorRegion` (`PlayerActivity.kt`, atualmente privada/aninhada dentro de `PlayerActivity`, usada
  só pra patrocínio). Mesmo formato (`x0, x1, y0, y1`) serve pra isso aqui — só que a lógica da Fase
  2 original já previa um `LayoutRegion` equivalente dentro do pacote `price` (`PriceModels.kt`),
  que é onde deveria morar de verdade (não dentro de `PlayerActivity`), pra ser consumido tanto pelo
  `LayoutConfig` (vindo do manifesto, fluxo de hoje) quanto por esse pareamento ao vivo (fluxo novo).
- **`PriceConfigParser.parse()`** (`price/PriceConfigParser.kt`) já lê `layout.style`,
  `xml_layout_type`, `color_mode`, `solid_color`, `price_slots[]` — não lê nada de regiões hoje.
  Precisa ganhar um `layout.regions` opcional, no mesmo padrão dos outros campos (ausente = `null`,
  zero mudança de comportamento).
- **`applyPriceOverlayLayout()`** (`PlayerActivity.kt`) é quem reposiciona hoje os 2 painéis
  (`priceLeftPanel`/`priceRightPanel`) por orientação, manipulando `ConstraintLayout.LayoutParams`
  direto (não usa `ConstraintSet`) — é o padrão a seguir pra uma função nova (`applyLayoutRegions`)
  que, se houver `regions` vindo do Studio, reposiciona os **blocos de dentro** desses painéis
  (`priceProductImage` e o que mais for identificado como "name"/"price"/"offer" dentro de cada
  `price_check_*.xml` — os nomes exatos de cada view variam por layout XML; a implementação real
  vai precisar olhar cada um dos ~13 `price_check_*.xml` pra mapear region→view, isso não foi feito
  nesta pesquisa e é trabalho de implementação, não de design).
- **Já existe um WebSocket client no próprio `mplayer`** (`network/ManifestEventsListener.kt`) — mas
  fala um protocolo totalmente diferente (Supabase Realtime/Phoenix, não o protocolo do Studio) — não
  dá pra reaproveitar a classe, só confirma que a dependência OkHttp-WebSocket já está no projeto.
- **`app/build.gradle.kts`** tem só 1 dimensão (`"api"`), 4 flavors, nenhum com
  `buildConfigField` por flavor hoje (os `buildConfigField` existentes são todos em `defaultConfig`)
  — pra isolar o recurso num flavor novo, precisa de um primeiro `buildConfigField("boolean",
  "STUDIO_REGIONS", "true"/"false")` por flavor, padrão já usado no `mplayer-studio` pro flavor
  `consulta`/`QUERY_ONLY`.

## Risco conhecido, de uma tentativa anterior parecida (mplayer-studio, Fase 5)

O `mplayer-studio` teve uma tentativa de "layout ao vivo" nesse mesmo protocolo (flavor `consulta`)
que ficou com um bug nunca resolvido: a conexão inicial chegava certa, mas edições seguintes no
Studio não chegavam ao aparelho (sem erro, sem log, `viewers` continuava contando o dispositivo como
conectado). **Essa investigação (`docs/STATUS_FASE5_CONSULTA_E_MCOMPANION.md`) não existe mais no
repo `mplayer-studio`** (confirmado por busca — pode ter sido um arquivo local nunca commitado, ou
já foi removido) — então não há repro/causa documentada pra herdar. Como o protocolo em si
(`LiveStudioLink`/`StudioLink`/`relay.ts`) é o mesmo código que ainda funciona pro mCompanion hoje, a
hipótese mais provável é que o bug estava em como o `mplayer-studio` consumia a mensagem, não no
protocolo — mas isso é risco a observar na implementação real (testar várias edições seguidas, não só
a primeira), não algo que este design já resolve.

## Desenho da solução

### Parte A — novo flavor `studio` (`app/build.gradle.kts`)

```kotlin
create("studio") {
    dimension = "api"
    minSdk = 24
}
```
`buildConfigField("boolean", "STUDIO_REGIONS", "false")` em `defaultConfig` (desligado por padrão),
sobrescrito pra `"true"` só no flavor `studio`. Todo o código novo fica gated por
`BuildConfig.STUDIO_REGIONS` — nos outros 4 flavors, flag `false`, zero diferença de comportamento.

### Parte B — modelo de dados (`price/PriceModels.kt`)

Mover o conceito de região pra cá (hoje só existe como `SponsorRegion`, privado dentro de
`PlayerActivity`, pra patrocínio):
```kotlin
data class LayoutRegion(val x0: Float, val x1: Float, val y0: Float, val y1: Float, val visible: Boolean = true)
```
`LayoutConfig` ganha `val regions: Map<String, LayoutRegion> = emptyMap()` (chaves: `"image"`,
`"name"`, `"price"`, `"offer"` — mesmo vocabulário da Fase 2 original). `PlayerActivity`'s
`SponsorRegion` existente fica como está (patrocínio é outro recurso, não vale a pena unificar os
dois agora e arriscar quebrar o que já funciona).

### Parte C — `PriceConfigParser.parse()`: ler `regions` do manifesto (fluxo offline, já existe)

Extensão pequena e aditiva: dentro do `if (layoutObj != null)`, ler
`layoutObj.optJSONObject("regions")` com o mesmo padrão de parsing já usado — isso cobre o caso de
alguém publicar regiões via manifesto no futuro, sem depender do pareamento ao vivo. Sem esse campo
no JSON, comportamento 100% inalterado.

### Parte D — pareamento ao vivo (só no flavor `studio`)

- **Novo arquivo** `network/StudioRegionsLink.kt`, porte quase literal de `LiveStudioLink.kt`
  (mesma `Pairing`, mesma URL, mesmo backoff/geração) — só troca o que é feito com a mensagem
  `{"type":"layout", "json":...}` recebida: em vez de `onLayout(json: String)` pra um card DivKit,
  o `json` aqui é `{"regions": {"image": {...}, "name": {...}, ...}}`, parseado direto pro
  `Map<String, LayoutRegion>` da Parte B.
- Persistência do pareamento: `SharedPreferences("studio_regions_link")`, chaves `host`/`session`
  (mesmo padrão do `mplayer-studio`). Persistência das ÚLTIMAS regiões recebidas: arquivo local
  (`regions_override.json` em `filesDir`), pra sobreviver a um restart do app sem precisar que o
  Studio reenvie — lido no boot, antes mesmo de reconectar.
- **Prioridade de fonte**: regiões recebidas ao vivo (cache local) **sempre vencem** sobre
  `regions` vindo do manifesto (Parte C), enquanto o pareamento estiver ativo — mesmo princípio já
  usado no `mplayer-studio` (`bundle_override.json` vence sobre o asset embutido).

### Parte E — Settings: campos de IP + código (`SettingsActivity.kt`, só no flavor `studio`)

Novo card colapsável (mesmo padrão visual dos outros cards de `SettingsActivity`, ex. "Servidor de
Preços (Local)"), gated por `if (BuildConfig.STUDIO_REGIONS)`: 2 campos (IP:porta do Studio, código
de pareamento) + botão Conectar/Desconectar + status (conectado/desconectado, versão da última
região recebida). Ao conectar, salva em `SharedPreferences` e inicia o `StudioRegionsLink`.

### Parte F — aplicar as regiões (`PlayerActivity.kt`)

Novo `applyLayoutRegions(regions: Map<String, LayoutRegion>?)`, chamado logo depois de
`applyPriceOverlayLayout()` (mesmo ponto). Sem `regions` (null/vazio — caso de hoje nos outros 4
flavors e no `studio` sem pareamento ativo): não faz nada, comportamento idêntico ao atual. Com
`regions`: pra cada chave presente, localizar a view correspondente dentro do layout já inflado
(`priceProductImage` confirmado pro "image"; os demais — "name"/"price"/"offer" — precisam ser
mapeados view-a-view durante a implementação, já que os ~13 XMLs de `price_check_*` não têm nomes
de view padronizados entre si) e reposicionar via `ConstraintLayout.LayoutParams` (mesma técnica de
`applyPriceOverlayLayout`), ou `View.GONE` se `visible=false`.

### Parte G — lado do Studio (`MupaStudio/apps/web`)

Fora do alcance desta sessão de design detalhado (é outro repo, outra pessoa pode implementar com
esta spec) — mas resumindo o que muda lá: uma aba/painel novo "Regiões MPlayer" com um canvas e 4
retângulos arrastáveis (reaproveita o mecanismo de drag já construído em `src/sponsor.ts` pra outra
feature), um campo pra ver/gerar o código de pareamento (reaproveita `relay.ts` como já é), e um
botão "Enviar" que manda `{"type":"layout","json": JSON.stringify({regions: {...}})}` pro socket —
o `relay.ts` já faz o broadcast, zero mudança nele.

## Fora do alcance desta fase

- Resolver o bug não documentado da tentativa anterior (Fase 5/`mplayer-studio`) — não foi
  encontrado registro da causa; esta fase só monitora o mesmo sintoma (editar e reenviar repetidas
  vezes) durante o teste em hardware real.
- Unificar `SponsorRegion` (patrocínio) com `LayoutRegion` (este recurso) — mesmo formato, propositalmente
  mantidos separados por ora.
- Qualquer coisa no lado do Studio (Parte G) além da spec — fica pra quando alguém com acesso a esse
  repo/sessão implementar.

## Verificação

- Build dos 4 flavors existentes (`assembleDebug`) sem nenhuma mudança de comportamento — mesmo
  teste de regressão já usado nas outras features desta sessão.
- Build do flavor `studio` novo, instalar em 1 device de teste, parear com um Mupa Studio local
  (`npm run dev` em `apps/web`), confirmar que:
  - Sem nunca ter enviado regiões: layout idêntico ao padrão (regressão).
  - Enviar regiões 1x: blocos se movem pras posições enviadas.
  - Editar e reenviar 3-4 vezes seguidas: cada envio precisa chegar (é exatamente o ponto que falhou
    na tentativa anterior) — se travar depois da 1ª, é o mesmo bug não resolvido, documentar de novo
    com mais instrumentação (log de PING/PONG e `onMessage` bruto, como o próprio status doc perdido
    já recomendava).
  - Reiniciar o app (force-stop + abrir): últimas regiões recebidas persistem (lidas do arquivo
    local), sem precisar reconectar primeiro.
