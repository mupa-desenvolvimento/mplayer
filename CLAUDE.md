# mplayer — Terminal de consulta de preço (Mupa)

App Android (Kotlin) para os terminais de consulta de preço nas lojas. Integra com o backend `produtos-imgs` (repo separado, `C:\Users\rdpadmin\produtos-imgs`) para buscar produto, foto e a "arte publicitária" gerada por IA.

## Fluxo de consulta com arte

`PriceQueryEngine.preloadProductImageAndTheme` → `PlayerActivity.updatePriceBadge` decide o layout:
- **Sem arte** (`hasArt = false`): layout tradicional, painel esquerdo com nome/preço visível (`priceLeftPanel` = VISIBLE), imagem do produto pequena com `FIT_CENTER`.
- **Com arte** (`hasArt = true`): a arte vira o fundo em tela cheia (`makeFullBleed` esconde `priceLeftPanel`, expande `priceRightPanel`), `imgView.scaleType = FIT_CENTER`, e o preço é sobreposto por cima como um badge (`updatePriceBadge`) usando a cor dominante/vibrante extraída da própria imagem (`Palette`).

### `CENTER_CROP` → `FIT_CENTER` (decisão revertida, pedido explícito do usuário)

Nesta mesma sessão a decisão tinha sido pelo caminho inverso — `CENTER_CROP` pra cobrir a tela toda sem tarja, aceitando cortar as bordas quando a proporção da arte (sempre 1344x768, ver `produtos-imgs` CLAUDE.md) não batesse com a da tela do terminal. Na prática esse corte já tinha comido conteúdo real: numa tela 1280x800, os ícones de benefício encostados na borda direita da arte (a 96.5% da largura) ficavam parcialmente cortados. Usuário pediu explicitamente que a arte **nunca** passe das bordas da tela, priorizando ver a peça inteira sobre preencher 100% da tela — trocado pra `FIT_CENTER`, que escala pelo MENOR fator entre largura/altura da view (em vez do maior, como o `CENTER_CROP`) e centraliza o resultado, sobrando uma tarja no eixo que não bateu a proporção em vez de cortar.

Efeito colateral bom, não só neutro: como o fator de escala do `FIT_CENTER` é sempre `<=` o do `CENTER_CROP`, a troca nunca amplia a imagem mais do que já ampliava antes — ou seja, nunca piora a nitidez percebida (só pode empatar ou reduzir a ampliação necessária).

`updatePriceBadge`'s `imgView.post{}` (posicionamento do card de preço) foi recalculado pra esse novo modo: em vez de `escalaCenterCrop = max(...)` + `cortadoNaEsquerda` (offset de corte), agora é `escalaFitCenter = min(...)` + `tarjaEsquerda`/`tarjaSuperior` (offset de tarja, ambos os eixos — `CENTER_CROP` só cortava um eixo por vez, mas `FIT_CENTER` deixa tarja em qualquer um dos dois dependendo de qual proporção "sobra"). Testado no dispositivo físico (1280x800): arte inteira visível, ícones de benefício não cortam mais, card de preço segue alinhado com o texto da arte.

### `arteLarguraOriginal`/`arteAlturaOriginal` = 1280x800 (resolução real do terminal, não mais um 16:9 genérico)

Passo seguinte, mesmo dia: o usuário mediu a resolução real do terminal físico (`adb shell wm size` → `1280x800`) e pediu pra gerar a arte EXATAMENTE nesse tamanho em vez do 1344x768 anterior (um 16:9 arredondado, nunca tinha sido a resolução real de nenhum aparelho). As duas constantes em `updatePriceBadge` (`arteLarguraOriginal`/`arteAlturaOriginal`) foram atualizadas de `1344f`/`768f` pra `1280f`/`800f`, espelhando a mudança equivalente de `ARTE_LARGURA_HORIZONTAL`/`ARTE_ALTURA_HORIZONTAL` no `produtos-imgs` (ver CLAUDE.md de lá pro raciocínio completo).

Resultado prático: como a arte agora sai na resolução EXATA da tela do terminal, `FIT_CENTER` escala 1:1 (fator de escala = 1) e a arte cobre a tela inteira sem cortar nada E sem nenhuma tarja — o `FIT_CENTER` deixou de ser um "second best" (nunca corta, mas às vezes sobra tarja) pra virar visualmente idêntico ao antigo `CENTER_CROP` de tela cheia, só que sem o risco de cortar conteúdo. Testado no terminal físico depois de reconfigurar `SettingsManager` → "Servidor de Imagens" pro IP local (limpar dados do app via `pm clear` também limpa esse campo, se precisar testar de novo): tela coberta 100%, sem tarja visível, sem corte nos ícones de benefício.

**Cuidado**: essas duas constantes (aqui e em `produtos-imgs`) são hardcoded pra ESSE terminal específico (`SK100_Mupa`, serial `4001442606003389`). Se a rede de lojas tiver terminais com resoluções físicas diferentes, essa premissa de "uma resolução única pra todos" quebra — nesse cenário, o app precisaria reportar a própria resolução (`displayMetrics`) pro backend gerar a arte sob medida por dispositivo, em vez de uma constante fixa nos dois lados. Não implementado — não foi pedido, e hoje só há esse modelo de terminal em uso.

`makeFullBleed(panel, fullBleed: Boolean)` é genérico o bastante pra cobrir os ~12 variantes de layout `price_check_*.xml` que compartilham os ids `priceLeftPanel`/`priceRightPanel`/`priceLandscapeSplitGuide` — guarda o estado original (`FullBleedOriginalState`, na `tag` da view) pra poder reverter quando um próximo produto não tiver arte.

### Dois bugs distintos de "não trocou o layout" (histórico, não repetir a causa)

Ambos em `PriceQueryEngine.kt`, mascarados pelo cache de foto crua já estar "fresco":
1. `preloadProductImageAndTheme` tinha um early-return que pulava a checagem de `hasArt` sempre que a foto crua já estava em cache (<60min) — corrigido acrescentando `&& cachedHasArt` na condição.
2. Mesmo com (1) corrigido, `showPriceOverlayProduct`'s `needsBackgroundImageFetch` (que decide se a corrotina de rebusca roda) também ignorava `hasArt` — corrigido com `|| !product.hasArt`.

Moral: **qualquer lógica de "já está em cache, não precisa buscar de novo" precisa considerar `hasArt` explicitamente**, porque uma arte pode ter sido gerada no backend DEPOIS que a foto crua já foi cacheada localmente.

### Cache de imagem: foto crua vs. arte usam chaves diferentes

`downloadProductImageIfNeeded(ean, rawUrl, maxDim)` é usado para as duas coisas, mas com EANs de cache diferentes: a foto crua usa `ean` puro; a arte usa `"${ean}_arte"`. **Nunca unificar essas chaves** — já causou a arte "roubar" o arquivo da foto crua já baixada (e extrair cor de tema errada).

`maxDim` também é diferente por design: foto crua = 512px (thumbnail pequeno, ok); arte = `maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)` — a arte precisa da resolução real da tela do aparelho porque vira plano de fundo em tela cheia.

### Imagem vinda da API do próprio cliente também precisa disparar a geração de arte

`preloadProductImageAndTheme` tem várias fontes de imagem em ordem de prioridade (ver os comentários numerados dentro da função). Duas delas vêm de fora da Mupa — da própria integração do cliente, não do `produtos-imgs`:
- **Passo 1** (`clientImageUrl`, genérico): populado por qualquer step `lookup_price` cujo JSON tenha um campo de imagem (`link_imagem`, `clientImageUrl` ou `client_image_url` — ver os vários `.optString(...)` em `PriceQueryEngine.kt`). É o caminho genérico pra "qualquer cliente cuja API própria devolve a foto do produto".
- **Passo 2** (Komprão/OnWay SKU image API): específico de um cliente, `config.integration.contains("komprao", ...)`.

Como essa foto **não passa pelo `produtos-imgs`**, o backend não sabe que ela existe nem tem a arte publicitária gerada a partir dela — se nada for feito, o produto nunca ganha arte via esse caminho. A correção: depois de baixar a imagem do cliente com sucesso, chamar `requestArtGeneration(ean, imagePath)`, que faz um `POST /produto-imagem/<ean>/gerar-arte` fire-and-forget pro `produtos-imgs` — o endpoint já salva a foto crua (se ainda não tiver) E gera a arte a partir dela. O Komprão (passo 2) **já fazia isso**; o passo 1 genérico **não fazia** — foi corrigido pra chamar `requestArtGeneration` também, espelhando exatamente o padrão do Komprão. Idempotente: o endpoint do backend já checa se a arte existe antes de gerar de novo, então não custa nada chamar toda vez que uma foto nova do cliente é baixada.

Se um novo cliente/integração trouxer imagem por outro campo/fonte no futuro, o padrão a seguir é sempre o mesmo: baixar a imagem local, chamar `requestArtGeneration(ean, imagePath)`, retornar. Não inventar um caminho novo de upload — reaproveitar essa função.

## Servidor de imagens configurável

`SettingsManager.getImageServerBaseUrl()` (host/porta salvos em DataStore, chave `imageHost`/`imagePort`, default `srv-mupa.ddns.net:5050`) — configurável em Configurações → "Servidor de Imagens (produtos-imgs)", com um campo de teste ("Buscar imagem" por EAN) pra validar contra o servidor escolhido sem precisar escanear um produto de verdade. Usado tanto por `fetchProductImageMeta` quanto por `requestArtGeneration` — não hardcodar o host de novo se mexer nesse fluxo.

Ao testar localmente (rodando `produtos-imgs` nesta mesma rede), apontar pro IP local da máquina de dev (muda por DHCP — conferir antes de cada sessão de teste), não pro `srv-mupa.ddns.net` (produção, máquina física diferente).

## Preço sobre a arte (`updatePriceBadge`)

Visual iterado bastante nesta sessão — decisões já tomadas, não reabrir sem motivo:
- Fonte do valor: Poppins ExtraBold (`BrandTypography.poppinsExtraBold`, carrega `res/font/poppins_extrabold.ttf`), tamanhos em `buildPriceSpannable` (cifrão menor, inteiro grande, decimais menores) via `AbsoluteSizeSpan`/`ForegroundColorSpan` — não dá pra fazer isso com `<span>` de XML porque o valor é montado em runtime.
- Cor do badge: `prepared?.secondary ?: prepared?.dominant ?: "#DC2626"` (cor extraída da própria arte via `Palette`, com fallback pro vermelho de marca).
- Divisor entre 2 linhas de preço: a largura **tem que ser medida** (`container.measure(UNSPECIFIED, UNSPECIFIED)` antes de adicionar o divisor) e não `MATCH_PARENT` — um `LinearLayout` com `wrap_content` mede filhos `match_parent` contra o ancestral (tela inteira), não contra o próprio container, e isso esticava o card de preço quase até a borda da tela. Ver `uiautomator dump` no histórico se essa classe de bug voltar (é sutil e não aparece óbvio no XML).
- `container.elevation` alto + margem no bottom — pedido explícito do usuário pra dar profundidade/sombra ao card sobre a arte.

### Até 3 faixas de preço (era `take(2)`, descartava a 3ª silenciosamente)

`updatePriceBadge` fazia `product.priceSlots?.filter { it.value > 0.0 }?.take(2)` — limite deliberado de quando o card só previa "preço normal + 1 oferta". Usuário pediu pra verificar o comportamento com um produto real que retorna 3 faixas (`PRECO_NORMAL` + `PRECO_PDV` + `PRECO_CLUBE_KOCH`, EAN `7891515555917`, Perdigão Pizza Calabresa Moída) — confirmado que a 3ª faixa (`Oferta Clube K`, tipicamente a mais barata das três) era descartada sem aviso nenhum, mesmo a API retornando ela certinho (`parseKompraoResponse` em `PriceQueryEngine.kt:2149` já monta as 3 no `slots`, o corte era só na hora de renderizar). Trocado pra `take(3)`. A 2ª/3ª linha usam a mesma fonte reduzida (`scale = 0.78f`) — não foi pedido reduzir ainda mais a 3ª, então ficou igual à 2ª.

### Posição vertical: metade da tela + 50px, ancorado pelo TOPO com limite na base (não mais ancorado na base nem centralizado pelo meio do card)

Estava ancorado perto da base (`arteAlturaOriginal * 0.92f * escalaFitCenter - container.height`, ~92% da altura). Usuário pediu, com uma imagem de referência anotada (caixa vermelha na área de texto, caixa branca indicando onde queria o card), pra mover o card pra **metade vertical da tela**, logo abaixo do bloco de texto — não mais perto do rodapé. Primeira tentativa: `container.y = imgView.top + (imgView.height - container.height) / 2f` (centraliza o card no meio de `imgView`, que representa a tela inteira nesse ponto do código — `hasArt=true` já deixa a imagem em full-bleed). Deliberadamente usa `imgView.height` (a TELA), não a altura da arte multiplicada pela escala — "metade da tela" é sobre a tela, não sobre a arte; como a arte casa 1:1 com a tela (ver `ARTE_LARGURA_HORIZONTAL`/`ARTE_ALTURA_HORIZONTAL` = 1280x800 no `produtos-imgs` CLAUDE.md), as duas métricas coincidem de qualquer forma. Depois, pedido de mais um ajuste fino: `+ 50f` (50px pra baixo a partir desse centro).

**3 faixas de preço expuseram dois bugs em sequência, cada um corrigido antes do próximo aparecer** (ver seção abaixo sobre `take(2)` → `take(3)`):
1. Com centralização pelo MEIO do card, um card de 3 linhas (mais alto que o de 1-2) tinha seu TOPO empurrado pra cima o suficiente pra invadir a 2ª linha da descrição do produto acima (confirmado visualmente com EAN `7891515555917`, Perdigão Pizza Calabresa Moída — 3 faixas: normal/oferta/clube). Corrigido trocando a referência de "centro do card" pra "topo do card": `container.y = imgView.top + imgView.height / 2f + 50f` — o card sempre começa no mesmo Y e cresce pra BAIXO conforme o número de faixas, nunca pra cima, então nunca mais invade o texto.
2. Isso só trocou o problema de lugar: ancorado pelo topo, o mesmo card de 3 linhas ficava alto o bastante pra ULTRAPASSAR a base da tela — a 3ª linha ("Oferta Clube K") saía cortada por baixo (confirmado visualmente, mesmo EAN). Corrigido com um teto (`yMaximo = imgView.top + imgView.height - margemInferiorCard - container.height`, `margemInferiorCard = 24dp`) — `container.y = minOf(yDesejado, yMaximo)` desliza o card pra cima só o suficiente pra caber inteiro antes da base, sem nunca voltar a invadir o texto (o texto de um produto normal não chega nem perto da base da tela, então o slide-up nunca colide com ele na prática).

Testado com 3 cenários reais no terminal físico: 1 preço (padrão), 2 preços (Coca-Cola, EAN `7894900011609`) e 3 preços (Perdigão, EAN `7891515555917`) — todos com o card completo, sem cortes, sem invadir o texto acima. `container.x` (alinhamento horizontal com o texto) não mudou em nenhum desses ajustes.

## Scanner Gertec

`GertecScannerManager.scheduleNextCycle` re-arma indefinidamente (sem teto de tentativas) até uma leitura real confirmar — antes tinha um `ARM_MAX_CYCLES` que podia deixar o scanner "morto" até reiniciar o app. `getGertecScannerEnabled()` default é `true`.

## Git

**Identidade do git não está configurada neste repo** (nem local nem global) — commits falham com `Author identity unknown`. Isso não é algo que a Claude configura sozinha (regra absoluta: nunca mexer em git config), o usuário precisa rodar:
```bash
git config user.name "Seu Nome"
git config user.email "seu@email.com"
```
antes de qualquer commit neste repo.

## Débito/limitações conhecidas

- A nitidez da arte depende inteiramente do backend `produtos-imgs` respeitar `maxDim` corretamente e do dispositivo ter WEBP habilitado (padrão no Android, sem problema conhecido). Tarja (letterbox) no topo/base ou nas laterais é esperada e intencional com `FIT_CENTER` sempre que a proporção da tela do terminal não bate exatamente com 1344x768 — não é bug, é a troca deliberada por "nunca cortar a arte" (ver seção acima).
- Testes de UI nesta sessão foram feitos via ADB (`uiautomator dump` pra bounds reais, `adb shell input text` simulando leitura de código de barras) — não há testes automatizados de UI no projeto.
