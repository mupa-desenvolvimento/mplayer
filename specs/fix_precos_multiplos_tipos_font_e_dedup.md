# Fix: preço com 3+ tipos estourando a tela + preço normal sumindo (2026-10-23)

## Contexto

Pedido do usuário (flavor Komprão, bancada MEFERITESTENOVO): produtos com 3
faixas de preço (ex.: "Preço Normal" + "Oferta" + "Oferta Clube K" — Colgate
Plax, Topz Bastonetes) estouravam a área reservada da tela, cortando números
por cima/baixo. Investigação inicial tentou resolver via `fontScale` do
Android (Policy nova no ARGOS Remote) — não funcionou porque o card de preço
é renderizado como `TextView` nativo com `setTextSize()` explícito em código
(`emphasizePriceSlot`/`updatePriceBadge`), não lê `Settings.System.FONT_SCALE`
do sistema. O fix certo tinha que ser no próprio MPlayer.

## Bugs reais encontrados e corrigidos

Todos em `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`.

### 1. Fonte não encolhia o suficiente com 3+ faixas (dois lugares)

- `emphasizePriceSlot` (layout nativo sem arte, `item_price_slot.xml`) já
  tinha um modo `compact` pra 3+ tipos, mas não bastava.
- `updatePriceBadge` (badge sobreposto à arte publicitária em tela cheia)
  **não tinha nenhuma redução** por quantidade de linhas — cada linha usava
  sempre o mesmo tamanho (105.6sp o preço principal), então com 3 linhas o
  card ficava alto o bastante pra `yMaximo` (ver cálculo de posicionamento)
  empurrar o card pra cima da área reservada, invadindo o nome do produto.

Fix: `compactShrink = if (rows.size >= 3) 0.7f else 1.0f` multiplicado em
cima do tamanho já existente, nos dois lugares — reduz 30% a fonte do preço
(moeda/inteiro/decimal) em todas as linhas quando há 3+ tipos.

### 2. `hasArt` confiava em cache desatualizado

`product.hasArt` vinha direto do cache (`PriceQueryEngine`), sem checar se o
arquivo de arte que ele referencia (`product.image`) ainda existe de verdade
no disco. Se o cache da arte fosse evictado/apagado, `localPath` caía
silenciosamente pra foto normal (`getLocalProductImagePathIfExists`, sem o
sufixo `_arte`) enquanto `updatePriceBadge` continuava recebendo
`hasArt=true` — aplicava o layout de arte (painel escondido, badge
sobreposto) em cima da foto normal, sem a área reservada pro preço.

Fix: `effectiveHasArt = product.hasArt && localFromProduct != null` — só
confia que "tem arte" quando o arquivo que a própria arte referencia existe
agora, não só porque existiu numa consulta anterior.

### 3. Preço normal deduplicado sumia por completo no badge da arte

`updatePriceBadge` tinha a mesma lógica de dedup de `populatePriceSlots`:
remove o slot "Preço Normal" quando o valor é igual ao `fromValue` da
oferta, assumindo que ele continua visível riscado dentro do próprio box de
oferta. Só que, ao contrário de `populatePriceSlots` (tem `slotFromPrice`),
o badge da arte **nunca desenhava esse "De: X" riscado** — resultado real:
o preço normal sumia da tela por completo (confirmado com o EAN de teste do
Topz Bastonetes, só "OFERTA R$ 9,99" aparecia, nada do valor de R$ 10,49).

Fix: `rows` passou a carregar `fromValue` (`ArteBadgeRow`, antes era um
`Triple` sem esse campo) e cada linha desenha "De: X" riscado quando
`fromValue != null && fromValue > value`, igual ao `populatePriceSlots`.

### 4. Layouts específicos (`price_check_clube_koch` etc.) só liam campos
   escalares, não `priceSlots[]`

`bindSpecificLayoutPrices` (usado pelos layouts `price_check_normal`/
`price_check_de_por`/`price_check_atacado`/`price_check_clube_koch`/
`price_check_cartao_koch` — anteriores ao sistema `priceSlots[]`) só lia
`product.price`/`pricePromotional`/`priceClub`/etc., campos que a integração
Komprão nem sempre preenche (o detalhe completo vem só em `priceSlots[]`).
Antes, isso nunca aparecia porque o badge da arte (bug #2) sempre vencia
visualmente. Com o `hasArt` corrigido, esse buraco ficou visível: só
"Oferta" aparecia (único campo escalar preenchido), sumindo normal/clube.

Fix: deriva `normalVal`/`promoVal`/`clubVal`/`wholesaleVal`/`fromVal` de
`product.priceSlots[]` primeiro (por `isPromo`/`isClub`/`field`), caindo
pros campos escalares só se não tiver slot correspondente.

### 5. TTS não nomeava o preço normal quando havia mais de 1 tipo

`speakSlotsIfPossible`: preço normal (não promo, não clube) sempre falava só
o valor, sem dizer "Preço Normal" — mesmo com 2-3 tipos na tela, só
oferta/clube eram anunciados com nome, o normal virava um número solto no
meio da fala.

Fix: com `validos.size > 1`, precede também o preço "normal" pelo rótulo
(ex.: "Preço Normal, R$ 15,99." em vez de só "15,99.").

## Testado

Bancada MEFERITESTENOVO (flavor `komprao`, debug), EANs reais (Colgate Plax,
Topz Bastonetes) via scan remoto (IME) e mock `test_zaf_full`. Confirmado
visualmente pelo usuário: "Topz com normal e oferta, apareceu certo agora" —
depois de publicado no Catálogo (canal `dev`/homologação) e entregue via
painel real pro MEFERI, confirmado de novo: "Testei, apareceu certo".

## Publicação no Catálogo

Versão 1.1.71 (code 89) subida no canal **dev** (homologação) de propósito
— os flavors do MPlayer (`legacy`/`modern`/`komprao`/`unidasul`)
compartilham o mesmo `applicationId` (`com.mupa.player.enterprise`), então
nunca sobe em `stable` sem avaliação, senão vaza pra outras lojas (ver
incidente real: `docs/INCIDENTE_CATALOGO_FLAVOR_UNIDASUL_VAZOU.md` no repo
`argos-remote`, 2026-10-14). Entregue manualmente só pro MEFERITESTENOVO via
"Entregar Aplicação" no painel.
