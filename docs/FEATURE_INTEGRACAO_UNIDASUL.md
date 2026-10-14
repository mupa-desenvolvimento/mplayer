# Integração UnidaSul (terminal de consulta de preço)

Data: 2026-10-13 (criação) · **Update 2026-10-14: bug real corrigido e
testado ao vivo pela primeira vez, ver seção no fim do documento.**

## Pedido

"Quero criar um apk terminal de consulta preço da Unidasul agora...
usar tudo que já existe no Mplayer, só adicionar a regra de apontar para
o servidor e ajustar todas as regras de resposta que temos no arquivo
.aia. Analise tudo documente e não deixe nenhuma falha por favor." —
junto com o arquivo `App_TC_Unidasul.aia` (app legado da UnidaSul feito
em MIT App Inventor/Kodular, usado hoje pelos terminais de consulta de
preço da rede).

## O que já existia (reaproveitado, nada reinventado)

O MPlayer já tinha toda a infraestrutura de terminal de consulta de
preço usada por outras redes (TCServer/Gertec, Assaí, Americanas,
Zaffari, Komprão) — só faltava a regra de como falar com o servidor da
UnidaSul e como interpretar a resposta dele:

- `PriceQueryEngine.query(ean, config, isOnline)` — dispatcher que olha
  `config.integration` e desvia pra uma função dedicada por rede antes
  de cair no motor declarativo genérico (`config.steps`).
- `ProductPriceSlot`/`populatePriceSlots` — UI de múltiplos preços lado
  a lado (normal, clube, oferta por quantidade etc.), já usada por
  TCServer pra "preço de/por".
- `buildSpokenPrice`/`speakSlotsIfPossible` — TTS por extenso ("três
  reais e setenta e nove centavos"), com cache Azure (ver
  `FEATURE_TTS_AZURE_CACHEADO.md`) e fallback nativo.
- `SettingsManager` com `tcServerAddress` — precedente exato de "IP de
  servidor local da loja configurado na tela de Configurações do
  device", usado pelo Gertec TCServer.
- Flavors de build (`legacy`/`modern`/`komprao`) — sistema de canal
  (beta/estável) do Catálogo de Software já versiona cada flavor
  separadamente.

## Engenharia reversa do app legado

O `.aia` é um .zip com o XML Blockly (`Screen1.bky`, 31.815 linhas,
2,4 MB) de todos os blocos visuais do app. Não cabia ler o arquivo
inteiro de uma vez — a estratégia foi `grep -n` por
`instance_name="request_produto"` (o componente Web que faz a
requisição) pra achar as linhas exatas de onde a URL é montada, onde
o cabeçalho é setado, onde o `.Get()` é disparado e onde o evento
`GotText` trata a resposta, e então ler só essas faixas.

O Blockly do App Inventor deixa comentários "Do It Result:" presos nos
blocos — são capturas REAIS de execução ao vivo, feitas pelo
desenvolvedor original durante depuração no próprio app em produção.
Foram a fonte de verdade pra tudo documentado abaixo (nenhum dado foi
inventado).

**Achado importante**: o mesmo arquivo `.bky` contém vestígios de PELO
MENOS 3 integrações diferentes e não relacionadas (histórico de
desenvolvimento compartilhado entre clientes no mesmo template
Kodular): uma de outra rede com formato `codigo_barras`/`ofertas`/
`valor`, uma de estilo VipCommerce (`data.descricao_produto`/
`preco_base`) pra pesáveis, e lógica vestigial de token da Zaffari.
Todos foram identificados e descartados como ruído — a confirmação de
que o formato `elementos.lblXxx` é o ativo da UnidaSul veio de uma
captura com `"lblEmpresa": "UnidaSul - Distribuidora Alimentícia S/A"`
literal na resposta.

## Arquitetura real (confirmada pelas capturas)

```
Terminal (app)  →  http://<servidor-da-loja>:5000/get_price?num_filial=<filial>&ean=<ean>
                         │
                         ▼
         servidor LOCAL da própria loja (Python/Flask, porta 5000)
                         │  faz scraping de uma página ASP.NET WebForms
                         │  legada (controles <asp:Label>, por isso os
                         │  nomes de campo tipo "lblPreco", "lblDcrProduto"
                         │  são literalmente os IDs desses controles)
                         ▼
              intranet de consulta de preço da filial
```

Não é um servidor central da UnidaSul — é um servidor DENTRO da rede de
cada loja, que faz de ponte pro sistema de preços legado daquela
filial. Por isso o IP é configurado por dispositivo, nunca fixo no
código (mesmo padrão do TCServer).

### Requisição

```
GET http://<ip ou ip:porta>/get_price?num_filial=<filial>&ean=<ean>
```

- Porta padrão **5000** quando o IP configurado não traz porta.
- `num_filial`: nas capturas reais aparece como string numérica de 3
  dígitos com zero à esquerda (`"086"`, `"035"`). Implementado como
  normalização defensiva: só aplica `padStart(3,'0')` quando o valor
  salvo pra filial for puramente numérico e tiver menos de 3 dígitos —
  nunca força formato em valores não-numéricos.

### Resposta — sucesso

```json
{
  "ean": "7896423456561",
  "elementos": {
    "lblDcrProduto": "CHOC SNICKERS MOUSSE MARACUJA 42G",
    "lblPreco": "3,79",
    "lblPrecoDC": "A partir de  3 Un:",
    "lblPrecoDC2": "3,33 cada",
    "lblScanner": "Na compra de  3 ou mais unidades, você economiza: R$ 0,46 por unidade"
  },
  "imagem_status": "indisponivel",
  "imagem_url": null,
  "success": true
}
```
(captura real, 2025-04-25, filial F035)

- `lblPreco` vem em formato BR (`"3,79"`), convertido com
  `.replace(".", "").replace(",", ".")`.
- **"Não encontrado" não é um `success: false`** — o app original
  detecta isso comparando `lblPreco` (já normalizado) contra a string
  literal `"null"` pra decidir falar "Produto não encontrado ou
  verifique o código de barras!" (confirmado em `Screen1.bky`, bloco de
  comparação perto da linha 21378). Replicado fielmente: `precoStr`
  vazio OU literalmente `"null"` → produto não encontrado.
- `imagem_status` só pode ser `"disponivel"` ou `"indisponivel"` —
  `imagem_url` só é usado quando o status é `"disponivel"` (ignorado
  mesmo se vier preenchido, caso contrário). `imagem_url` pode vir
  **relativo** (`"/imagem-produto/<ean>"`, prefixado com
  `http://<host:porta>`) ou **absoluto** (`"http://localhost:5000/..."`,
  passado direto) — ambos os formatos foram vistos em capturas reais.

### Resposta — erro de conexão com a filial

```json
{
  "details": "HTTPConnectionPool(host='f086srv01.filial.unidasul.local', port=80): Max retries exceeded ...",
  "device_info": {"ip_address": "192.168.100.191", ...},
  "error": "Não foi possível conectar ao servidor da filial 086."
}
```
(captura real, 2025-09-11)

**Ponto não-óbvio e crítico**: essa resposta de erro chega com **HTTP
200 OK**, nunca com um status de erro HTTP — o servidor-bridge da loja
sempre responde 200, delegando o sucesso/erro pro campo `"error"` do
corpo. Por isso `fetchUnidasulPrice` NÃO filtra por
`response.isSuccessful`; quem decide é `parseUnidasulElementosJson`
checando `json.has("error")` primeiro, antes de qualquer outra coisa.

## Decisões de negócio confirmadas com o usuário antes de implementar

1. **Oferta por quantidade ("A partir de 3 Un: R$ 3,33 cada") passa a
   aparecer**, como um 2º `ProductPriceSlot` — no app original essa
   informação ERA capturada pelo servidor mas nunca exibida na tela
   (só preço normal). Decisão: mostrar (opção recomendada escolhida).
   `lblPrecoDC` (sem o `:` final) vira o rótulo do slot;
   `lblPrecoDC2` (regex extrai o número de dentro de "3,33 cada") vira
   o valor; `fromValue` é o preço normal riscado dentro do próprio box
   (mesmo padrão visual já usado pelo TCServer).
2. **Fala por extenso usando o motor de TTS já existente do MPlayer**
   (Azure com cache + fallback nativo), em vez de replicar o
   comportamento do app antigo (que lia "R$ 3,79" cru pelo TTS nativo
   puro do Android). Decisão: usar o novo (opção recomendada escolhida).
3. **IP do servidor configurado na tela de Configurações do MPlayer**,
   exatamente como já funciona pro TCServer — não um campo novo em
   outro lugar. Decisão: Configurações (opção recomendada escolhida).

## Arquivos alterados/criados

- `app/src/main/java/com/mupa/player/enterprise/managers/SettingsManager.kt`
  — chave `unidasulServerAddress` + `get/setUnidasulServerAddress`,
  espelhando `tcServerAddress`.
- `app/src/main/java/com/mupa/player/enterprise/price/PriceQueryEngine.kt`
  — dispatch pra `config.integration == "integra-unidasul"`;
  `queryUnidasul` (orquestra cache/offline/fetch/parse, igual ao
  template dos outros integradores); `fetchUnidasulPrice` (GET puro);
  `parseUnidasulElementosJson` (função **pura**, fora da classe, sem
  `Context` — só recebe `ean`/`JSONObject`/`hostPort` — extraída de
  propósito pra dar pra testar sem Android/Robolectric);
  `parseUnidasulResponse` (casca fina que chama a função pura e só
  resolve o cache de imagem local, que depende de `Context`).
- `app/src/main/res/layout/activity_settings.xml` — card "UnidaSul
  (Consulta Preço via servidor da loja)", estrutura idêntica ao card do
  TCServer (campo de IP/porta + botão Salvar + botão Testar).
- `app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt`
  — carrega/salva o campo; botão de teste faz
  `GET /get_price?num_filial=000&ean=0000000000000` e confirma que a
  resposta é JSON com `"success"` ou `"error"` (não existe endpoint
  `/health` nesse servidor, diferente do TCServer — confirmado: o único
  endpoint visto em qualquer captura do `.aia` é `/get_price`).
- `app/build.gradle.kts` — novo flavor `unidasul` (dimension `api`,
  minSdk 24) + `unidasulImplementation(project(":engage-ui"))` (mesma
  necessidade do `komprao`, por usarem o mesmo layout
  `activity_camera_test.xml`/`GestureOverlayView`).
- `app/src/test/java/com/mupa/player/enterprise/price/PriceQueryEngineUnidasulTest.kt`
  — 10 testes unitários (ver abaixo).

## Testes

**Sem servidor real da UnidaSul disponível neste ambiente** pra um
teste ponta a ponta de verdade (diferente de outras features desta
sessão, como TTS Azure ou Policy NETWORK, que foram validadas em
hardware real). Os dois níveis de validação feitos:

1. **Testes unitários com as respostas REAIS capturadas no `.aia`**
   (`PriceQueryEngineUnidasulTest.kt`, 10 casos, todos passando):
   produto com oferta por quantidade (amostra "CHOC SNICKERS"); produto
   simples com imagem relativa (amostra "FILTRO PAPEL"); imagem
   absoluta passada direto (amostra "LASANHA"); `imagem_status`
   indisponível nunca gera URL mesmo com `imagem_url` preenchido
   (amostra "MASSA ORQUIDEA"); erro de conexão com a filial retorna
   null (amostra real do erro da filial 086); produto não encontrado
   (`lblPreco` literal `"null"`) retorna null; `success: false` sem
   `error` retorna null; `elementos` ausente não crasha; `lblPreco`
   em branco retorna null; `lblPrecoDC2` sem número válido não gera
   2º slot. Rodar com:
   ```bash
   ./gradlew.bat :app:testUnidasulDebugUnitTest --tests "com.mupa.player.enterprise.price.PriceQueryEngineUnidasulTest"
   ```
2. **Build real do flavor**: `assembleUnidasulDebug` gera
   `app-unidasul-debug.apk` sem erros (compila e linka todo o resto do
   MPlayer — kiosk, TTS, scanners etc. — junto com o código novo).

### O que NÃO foi testado (comunicado explicitamente, por instrução do
usuário de "não deixar nenhuma falha")

- **Nenhum round-trip real device→servidor UnidaSul.** A lógica de
  parsing foi validada contra dados reais capturados em produção, mas
  nunca contra um servidor UnidaSul vivo rodando agora. Antes de
  liberar pra uma loja de verdade: instalar o APK, configurar o IP:porta
  do servidor da filial na tela de Configurações, usar o botão
  "Testar" (confirma que o servidor responde no formato esperado) e
  então ler um EAN real na tela de consulta.
  Instalação: `adb install -r app/build/outputs/apk/unidasul/debug/app-unidasul-debug.apk`.
- **UI do app rodando no device** (fluxo de digitar/escanear EAN →
  tela de preço) não foi aberta numa tela real nesta rodada — só o
  parsing de dados e o build foram verificados.
- O `num_filial` pode precisar de ajuste manual na primeira
  configuração real — a normalização pra 3 dígitos é defensiva, baseada
  em só 2 exemplos capturados (`"086"`, `"035"`); se alguma filial usar
  um formato diferente (ex. mais de 3 dígitos, ou alfanumérico), a
  normalização não altera o valor (passa como veio), então é seguro,
  mas vale confirmar com a loja real qual código ela usa.
- Upload do novo APK pro Catálogo de Software **não foi feito** (não
  pedido nesta rodada) — se for pra distribuir via OTA, falta subir a
  versão lá.

## Como configurar num device

1. Abrir **Configurações** no MPlayer (mesmo fluxo de acesso restrito
   das demais configurações).
2. Card "UnidaSul (Consulta Preço via servidor da loja)" → digitar
   `IP` ou `IP:Porta` do servidor local daquela filial (ex.
   `192.168.0.10` ou `192.168.0.10:5000`).
3. Botão **Testar** → confirma que o servidor respondeu no formato
   esperado (JSON com `success`/`error`).
4. Botão **Salvar**.
5. A Policy/config do device precisa apontar `integration:
   "integra-unidasul"` (igual já existe hoje pra `integra-tcserver`
   etc.) pro `PriceQueryEngine` entrar nesse caminho.

## Próximos passos sugeridos (não executados, aguardando pedido)

- Decidir se o APK `unidasul` entra no Catálogo de Software (canal
  beta primeiro, como de costume).

## Update 2026-10-14 — bug real: integração nunca era ativada

Pedido do usuário: "não está consultando, estou com o dispositivo
conectado, na rede do cliente... debuga pra nós" — primeiro teste ao
vivo de verdade, com device físico na rede da loja e o servidor
UnidaSul real (`10.80.2.1:5000`, filial 080).

**Diagnóstico**: `adb logcat` mostrou, na leitura real de um EAN,
`barcode_captured ean=78931329 hasCfg=false integration=null` — o
`priceConfig` nunca chegava a ser preenchido, então `PriceQueryEngine`
nunca tentava a integração UnidaSul (nem logava erro nenhum — por
isso parecia simplesmente "não fazer nada").

**Causa raiz**: o passo 5 de "Como configurar num device" (acima)
estava descrevendo um mecanismo que nunca foi implementado de
verdade. Todas as outras integrações têm uma função em
`PlayerActivity.kt` que ativa `priceConfig` de verdade quando a
condição bate — `ensureAssaiDefaultPriceConfigIfNeeded()`/
`ensureAmericanasDefaultPriceConfigIfNeeded()`/
`ensureZaffariDefaultPriceConfigIfNeeded()`/
`ensureKompraoDefaultPriceConfigIfNeeded()` (por `companyId`) e
`ensureTcServerDefaultPriceConfigIfNeeded()` (por endereço configurado
em Configurações, exatamente o mesmo padrão do UnidaSul). A UnidaSul
era a ÚNICA integração sem esse "force" — `queryUnidasul()` no
`PriceQueryEngine` e os 10 testes unitários estavam certos e prontos,
mas nada em `PlayerActivity` jamais setava
`priceConfig.integration = "integra-unidasul"`. O botão "Testar" de
Configurações dava falsa confiança porque ele bate direto no servidor
via `HttpURLConnection`, sem passar pelo `priceConfig` — por isso
"testar conexão" sempre funcionou enquanto a consulta de preço real
nunca funcionou.

**Fix** (mesmo padrão exato do TCServer, só duplicado pro UnidaSul):
- `PlayerActivity.kt`: `unidasulServerAddress`/
  `refreshUnidasulServerAddress()`/`shouldForceUnidasulIntegration()`/
  `ensureUnidasulDefaultPriceConfigIfNeeded()` — novos, espelham
  exatamente o bloco já existente do TCServer. Chamados nos 2 mesmos
  pontos que o TCServer já era chamado (`startLoop()` e o refresh
  periódico que roda a cada retomada da tela), incluindo a lógica de
  "endereço removido nas configurações → volta pra config do
  manifesto".
  `applyPriceConfigFromManifestJson()`: checa
  `shouldForceUnidasulIntegration()` logo depois do TCServer, antes de
  cair no parser genérico.
- `PriceConfigParser.kt`: `"integra-unidasul"` adicionado em
  `allowEmptySteps` (defesa extra — sem isso, se algum dia um
  `price_config` vindo do manifesto tivesse
  `integration:"integra-unidasul"` com `steps` vazio, o parser
  descartava a config inteira retornando `null`).

**Testado ao vivo, ponta a ponta, pela primeira vez** (device físico
na rede do cliente, servidor real `10.80.2.1:5000`, filial 080):
1. Antes do fix: `hasCfg=false integration=null` — confirmado o bug
   via logcat, reproduzido exatamente como o usuário relatou.
2. Depois do fix (`assembleUnidasulDebug` + `adb install -r`):
   `price_config_default_unidasul_applied address=10.80.2.1` no boot,
   depois `hasCfg=true integration=integra-unidasul` na leitura do
   mesmo EAN (78931329) → `unidasul_resp` com produto real
   ("CIGARRO LM BLUE LABEL KS RCB 20 UN", R$ 13,75) →
   `schedule_return reason=tts_done` (TTS falou o preço, fluxo
   completo até o fim).
3. Confirmado também, direto por `curl` no device via `adb shell`
   (fora do app), que o servidor da loja responde certinho no formato
   documentado — rede e servidor nunca foram o problema, só a ativação
   da integração no app.

Testado só com ESTE device/filial; outras filiais da UnidaSul usam o
mesmo mecanismo (config local por device em Configurações), nenhuma
mudança adicional deveria ser necessária pra elas.
