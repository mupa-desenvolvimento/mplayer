# Sessão 2026-11-25/26 — Consulta Patrocinada: foto do produto, importador de XML, tamanho

**Dispositivo de teste:** Handheld-POS-EEA, serial `98001141` (empresa Komprão,
`54fc2743-5194-48b1-a002-ab42a45b834c`, integração nativa `integra-komprao`/SuperKoch OnWay).

## 1. Bug: foto do produto não aparecia quando o EAN já tinha arte própria em cache

### Sintoma
Na Consulta Patrocinada (peça do patrocinador em tela cheia + foto/nome/preço do produto
compostos dentro da área reservada), produtos que já tinham sido consultados antes (e por isso
já tinham uma arte publicitária por IA fresca em cache, `<ean>_arte.webp`) apareciam SEM foto —
só nome e preço.

### Causa raiz
`PriceQueryEngine.preloadProductImageAndTheme` roda `resolveNormalPreloadedImage` (passo 0: arte
publicitária por IA) ANTES de qualquer tentativa de buscar a foto crua (passos 1-3: foto própria
do cliente, API de imagem do Komprão, VTEX/Mupa). Quando a arte já existe em cache fresco, a
função retorna logo no passo 0 (`hasArt=true`) e NUNCA chega aos passos 1-3 — então
`plainProductPhoto` (que descarta explicitamente arquivos `_arte`) ficava `null`: a foto nunca
tinha sido buscada, só a arte.

### Fix
Novo método `PriceQueryEngine.fetchPlainProductPhoto(normalizedEan, config)` — extração dos
passos 1-3 (foto do cliente / Komprão / VTEX-Mupa), reutilizável fora do fluxo normal. Em
`preloadProductImageAndTheme`, quando `plainProductPhoto` continua `null` depois do fallback pro
cache local (mesmo stale), e o dispositivo está online, chama esse método pra buscar a foto na
hora — sem nunca buscar/usar arte como thumbnail.

### Teste ao vivo
EAN `7891010980061` (Shampoo Johnson's, Komprão) — antes do fix: `plainProductPhoto=null`. Depois:
`plainProductPhoto=.../7891010980061.webp`, baixada via API de imagem do Komprão na hora, "photo
load SUCCESS", confirmado visualmente no device.

## 2. Importador de "XML da consulta" (Mupa Studio → MPlayer)

### Contexto
O Mupa Studio já tinha um botão "XML da consulta" que exporta a configuração de integração
(`integration`, `steps` de `authenticate`/`lookup_price` com headers/body/mapping) como um XML
próprio (`<mupa-studio-query>`), mas o MPlayer não tinha NENHUM jeito de importar esse arquivo —
só aceitava a mesma config em JSON, vinda do manifesto do backend.

### Implementação
- **`PriceConfigXmlImporter.kt`** (novo, `price/`): `parse(xml: String): PriceConfig?` — parser
  DOM (`DocumentBuilderFactory`, primeira vez que o projeto usa parsing de XML em runtime fora de
  layouts Android) que converte o `<mupa-studio-query>` pro JSON que `PriceConfigParser` já
  entende, e delega 100% da validação/regras pra ele — sem duplicar schema. Cobre
  `integration`/`timeout-ms` (atributos da raiz), `<step type method url>`, `<header name>`,
  `<map field path>`, `<body>` (CDATA). Tag `<image>` é ignorada (resolução de foto/arte já roda
  à parte, não depende desse XML).
- **`SettingsManager.customQueryXml`** (DataStore, chave `custom_query_xml`) — mesmo padrão de
  `tc_server_address`/`unidasul_server_address`: texto colado manualmente, em branco = desativado,
  **prioridade sobre a empresa/manifesto** quando preenchido (útil pra testar a config de um
  cliente novo sem esperar suporte nativo).
- **`PlayerActivity.kt`**: `shouldForceCustomQueryIntegration()`/
  `ensureCustomQueryDefaultPriceConfigIfNeeded()` — mesmo padrão exato de
  `shouldForceTcServerIntegration()`, checado logo depois de TCServer/UnidaSul (antes da
  empresa/manifesto) em `applyPriceConfigFromManifestJson`, no boot (`startLoop`) e no fallback
  lazy do scan.
- **UI em Configurações** (`activity_settings.xml` + `SettingsActivity.kt`): novo card "Importar
  XML da Consulta (Mupa Studio)" — campo multi-linha + botão "Importar e Salvar" (valida o XML
  na hora, mostra `integration`/quantidade de steps ou erro).

### Testes
4 testes unitários em `PriceConfigXmlImporterTest.kt` (amostra real On-Way/SuperKoch com 2 steps,
XML em branco, tag raiz errada, XML mal formado, `integration` ausente usa `"generic"`) — todos
passando. *Nota: corrigido de passagem um bug pré-existente e não relacionado em
`FacialRecognitionLicensingTest.kt` (mock de `ManifestManager.fetchManifest` retornando `String`
em vez de `ManifestFetchResult`) que bloqueava a compilação de QUALQUER teste do módulo.*

## 3. Foto do produto: +50% de tamanho, atrás da arte do patrocínio

### Pedido
Aumentar a foto do produto em 50% e fazer ela ficar ATRÁS da arte do patrocinador (não mais na
frente, ao lado do nome/preço).

### Implementação
A foto deixou de ser filha de `container` (o LinearLayout com nome/preço) e virou uma
`ImageView` própria (`sponsoredPhotoView`, tag `PRICE_ART_SPONSORED_PHOTO_TAG`), inserida no
**índice 0** do `parent` (a mesma `FrameLayout` onde `imgView`, a arte, já está) — ou seja,
desenhada ANTES/ABAIXO de tudo, inclusive da arte. Posicionamento (em `imgView.post`): foto e
`container` (texto) são tratados como um par centralizado no centro da região reservada
(`sponsorRegion`), foto à esquerda + texto à direita, exatamente como antes — só que agora em
duas views com z-order diferente. Tamanho: `sponsoredLookupPhotoSizeDp` ganhou `* 1.5` (150dp→
225dp, ou 120dp→180dp com mais de 1 faixa de preço).

### Achado real (teste ao vivo, peça Nestlé/KitKat)
Com a foto atrás da arte, ela sumiu completamente nesta peça — **a área branca reservada é
OPACA, não um recorte transparente**. Confirmado com o usuário que esse é o comportamento
**esperado**: a foto só aparece quando a peça tem transparência real (alpha=0) na área reservada.

**Requisito de autoria pra quem desenha criativos no Mupa Studio:** a área reservada pro
produto (dentro do "oval"/retângulo) precisa ser exportada com fundo TRANSPARENTE (PNG com
canal alpha), nunca branco sólido — senão a foto do produto fica escondida atrás da peça.

## 4. TCServer local (ambiente de teste)

Rodado localmente (JRE 8, `tc-server-2.7.1.jar`, porta HTTP 8080) nesta máquina
(`192.168.1.23:8080`) pra testar a integração TCServer antes de desligar e voltar pro Komprão
nativo. Lembrete: o jar certo é `tc-server-2.7.1.jar` — `tc-server.jar-2.7.1.jar` (com esse nome)
é uma PASTA (zip já extraído), não o jar executável.

## Arquivos alterados

- `app/src/main/java/com/mupa/player/enterprise/price/PriceQueryEngine.kt`
- `app/src/main/java/com/mupa/player/enterprise/price/PriceConfigXmlImporter.kt` (novo)
- `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`
- `app/src/main/java/com/mupa/player/enterprise/managers/SettingsManager.kt`
- `app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/test/java/com/mupa/player/enterprise/price/PriceConfigXmlImporterTest.kt` (novo)
- `app/src/test/java/com/mupa/player/enterprise/FacialRecognitionLicensingTest.kt` (fix incidental)
