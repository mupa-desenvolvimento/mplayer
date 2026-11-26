# TCServer direto (sem ponte) + Conversão de Moeda Estrangeira

**Data:** 2026-10-21
**Flavor:** komprao
**Testado ao vivo:** SK100 (`4001442606004419`), TCServer Gertec real rodando localmente

## 1. Integração TCServer — correção do protocolo

A integração `integra-tcserver` existia desde antes (comentário no código descrevendo um
protocolo TCP na porta 6500), mas **nunca tinha sido testada contra o TCServer de verdade**. O
código real fazia `GET /consulta?ean=` esperando JSON — um contrato que nunca existiu em
lugar nenhum (nem no TCServer, nem em ponte nenhuma).

Rodamos o `.jar` oficial da Gertec (`tc-server.jar-2.7.1.jar`, requer **JDK 8** — quebra em
JDK 9+ por causa de um bug no próprio `.jar`: ele chama `Class.class.getResourceAsStream(...)`
em vez de `getClass().getResourceAsStream(...)`, o que só funcionava no classloader de antes do
sistema de módulos) e descobrimos:

- **Porta TCP 6500**: protocolo real dos terminais físicos Gertec (handshake `#ok` →
  identificação → `#alwayslive`/`#alwayslive_ok` → `#<ean>` → `#<DESCRIÇÃO>|<PREÇO>`/`#nfound`).
  **Essa build trava o listener inteiro** (para de aceitar qualquer conexão nova, de qualquer
  terminal) ao receber uma identificação que não reconhece, sem logar exceção. Risco demais pra
  produção — **não usamos**.
- **Porta HTTP (ex. 8080), endpoint nativo `GET /barcode?param=<ean>`**: o mesmo que a página
  web de consulta do próprio TCServer usa (`webroot/index.jsp`/`product.jsp`/`notfound.jsp`).
  Retorna HTML, testado extensivamente sem travar nada. **Esse é o que usamos.**
- Produtos vêm de um arquivo texto (`PATH_FILE_PRODUCT` em `config.properties`, formato
  `codbar|descricao|preco1|preco2|`, recarregado a cada 10min) — não é preciso banco H2 nem
  nada além do TCServer já saber ler esse arquivo.

### Mudanças de código
- `PriceQueryEngine.kt` — `queryTcServer`/`fetchTcServerBarcodeHtml`/`parseTcServerHtml`:
  trocado de `GET /consulta?ean=` (JSON, nunca existiu) para `GET /barcode?param=` (HTML real),
  parse via regex dos campos `Descricao:`/`Label1:`/`Preco1:`/`Label2:`/`Preco2:`.
- `SettingsActivity.kt` — botão "Testar Conexão" do campo TCServer Mupa: trocado de `GET /health`
  (JSON, nunca existiu) pra `GET /barcode?param=0000000000000`, sucesso = contém
  `"Consulta de Barcode"`.
- Endereço em Configurações (`tc_server_address`) agora é `ip:porta` **da porta HTTP** do
  TCServer (ex. `192.168.6.234:8080`), não da porta TCP 6500.

### Achado colateral: imagem de produto
A busca de imagem (`produtos-imgs`/srv-mupa, `{{price_host}}` independe da integração) funciona
normalmente pro fluxo TCServer — mas na PRIMEIRA consulta de um EAN, o download em background às
vezes não termina a tempo do auto-retorno de 8s, então a imagem baixa e fica em cache mas só
aparece a partir da 2ª consulta daquele EAN. Comportamento pré-existente, não é bug desta sessão.

## 2. Conversão de Moeda Estrangeira (Dólar/Peso)

Permite que o preço do produto seja convertido e exibido também em dólar e/ou peso, usando uma
cotação cadastrada como "produto" especial na mesma tabela do TCServer.

### Convenção
Duas linhas com barcode **literal** `moeda_dolar`/`moeda_peso` na `PRICETAB.txt` do cliente,
onde `Preco1` = quantos reais vale 1 unidade daquela moeda:
```
moeda_dolar|Cotacao Dolar|5,34|
moeda_peso|Cotacao Peso|0,0061|
```
Conversão: `preco_em_estrangeira = preco_em_reais / cotacao`.

### Configurações (novo card "Moeda Estrangeira")
- **Habilitar Moeda Estrangeira** — chave mestra (default: desligado).
- **Mostrar Dólar (US$)** / **Mostrar Peso ($)** — cada um controla se aquela conversão
  específica aparece (default: ligado, mas só tem efeito com a chave mestra ligada).

### Implementação
- `SettingsManager.kt` — `foreign_currency_enabled`/`show_usd_currency`/`show_ars_currency`
  (DataStore simples, sem legado — mesmo padrão de `tc_server_address`).
- `PriceModels.kt` — `ProductPriceSlot` ganhou `currencySymbol: String = "R$"` (antes o símbolo
  "R$" era fixo no XML, nunca sobrescrito por slot).
- `PriceQueryEngine.kt` — `appendForeignCurrencySlots`/`fetchCurrencyQuote`: busca as cotações
  (SEM cache — ver bug #3 abaixo) e anexa como `ProductPriceSlot` extras
  (`field = "price_usd"/"price_ars"`) no produto já resolvido.
- `PlayerActivity.kt`:
  - `populatePriceSlots` — usa o sistema de slots **já existente** (o mesmo que mostra
    Normal/Promoção/Clube), que já lida com 3+ caixas (`compact` reduz espaçamento/fonte). Slots
    de moeda ficam de fora do cálculo de "melhor preço" (`isCurrencyConversionSlot`) pra não
    serem destacados como se fossem uma oferta melhor — são só a mesma coisa em outra unidade.
    `slotCurrency.text = slot.currencySymbol` agora é setado por slot (antes sempre "R$").
  - Caminho secundário (imagem "arte" de fundo, `rows.forEachIndexed`) também ajustado pra
    respeitar o símbolo por linha (`buildPriceSpannable`/`formatCurrency` ganharam parâmetro de
    símbolo). **Limitação conhecida**: esse caminho só mostra até 3 linhas (`take(3)`); se o
    produto já tiver 3 slots de preço (normal+promo+clube) as conversões de moeda não cabem
    nessa renderização específica (cabem normalmente na tela padrão sem "arte").
  - `speakPriceIfPossible` — ganhou o mesmo tratamento de moeda, mas é **código morto** nesse
    fluxo (ver bug #1 abaixo): quem realmente fala é `speakSlotsIfPossible`.
  - `OfferIntelligence.buildSpokenPrice` ganhou parâmetros de unidade (default "real"/"reais",
    sem quebrar quem já chamava sem eles).

### Testado ao vivo (SK100, v1.1.66)
EAN `0000000000068` (ABS Carefree, R$ 15,99) com cotação dólar=5,34 e peso=0,0061 cadastradas:
tela mostrou 3 caixas — R$ 15,99 / US$ 2,99 / $ 2.621,31 — matemática conferida
(15,99 / 5,34 = 2,99; 15,99 / 0,0061 = 2621,31). Sem erros no log, sem crash, scan seguinte
(outro EAN) seguiu funcionando normalmente.

## 3. Bugs encontrados e corrigidos após o teste inicial

Três problemas reais, achados em teste ao vivo logo depois do v1.1.66 funcionar visualmente —
cada um corrigido e reinstalado no SK100 na hora.

### Bug #1 — TTS sempre dizia "reais" pro dólar e pro peso (v1.1.67)
`PlayerActivity.showPriceOverlayProduct` chama `speakSlotsIfPossible(ean, slots)` sempre que o
produto tem `priceSlots` não-vazio — que é **sempre** o caso aqui (base + conversões). Essa
função é **separada** de `speakPriceIfPossible` (que eu tinha editado primeiro — ficou código
morto pra esse fluxo). `speakSlotsIfPossible` chamava `buildSpokenPrice(slot.value)` sem
parâmetro de unidade pra TODO slot, incluindo os de moeda estrangeira — o padrão (`"real"`/
`"reais"`) é fixo, então falava "dois reais e noventa e nove centavos" pro valor que já estava
em dólar. Corrigido: slots com `field == "price_usd"/"price_ars"` agora usam
`OfferIntelligence.buildSpokenPrice(valor, "dólar", "dólares")` (e peso/pesos). Log de
confirmação (`tts_slots_text`) adicionado nessa função pra facilitar debug futuro.

### Bug #2 — Caixa de dólar/peso com fonte pequena (v1.1.68)
Em `populatePriceSlots`, só o slot `isBest` (melhor preço) recebe a fonte grande em
`emphasizePriceSlot` — os demais (pensado originalmente pra "preço fora de promoção", um alerta
secundário de verdade) recebem ~60% do tamanho. Como os slots de moeda são sempre
`isBest = false` (de propósito — não competem por "melhor preço", é só a mesma coisa em outra
unidade), caíam nesse tratamento "secundário" sem motivo. Corrigido: `emphasizePriceSlot` ganhou
parâmetro `isCurrencyConversion`, que usa a MESMA fonte grande do preço principal (sem herdar a
cor de destaque, só o tamanho).

### Bug #3 — Cotação trocada na `PRICETAB.txt` não refletia (v1.1.69)
`fetchCurrencyQuote` tinha cache próprio de 10 minutos (`ConcurrentHashMap` em memória, por
instância de `PriceQueryEngine`, que vive a sessão toda do app) — motivado por "câmbio não muda
a cada consulta", mas isso contraria o padrão do resto da integração TCServer, que é
propositalmente **sem cache nenhum** (`cacheMinutes = 0`, comentário em `queryTcServer`: "o
TCServer é local/rápido e o preço pode mudar a qualquer momento"). Resultado: usuário editava
`moeda_dolar`/`moeda_peso` na `PRICETAB.txt` e a conversão na tela continuava com o valor velho
por até 10 minutos. Corrigido: cache removido, `fetchCurrencyQuote` busca fresco a cada consulta
— a chamada é local/rápida, não há ganho real em cachear. (Separado disso: o próprio TCServer só
recarrega a `PRICETAB.txt` do disco a cada 10min, `TXT_DB_RELOAD_INTERVAL_MIN` em
`config.properties` — isso é do TCServer, fora do nosso controle; reiniciar o processo força o
reload na hora.)

### Bug #4 — Produto em oferta (Preco2): ordem, duplicata e TTS (v1.1.70/v1.1.71)
Testado ao vivo com produto real (EAN `7891010980061`, Johnson's Baby, Preco1=22,49/
Preco2=19,32 — `Label2` do TCServer é literal "Custom price", config de loja, não texto de
marketing). Três problemas pedidos pelo usuário, todos na mesma raiz (slots de oferta tratados
igual a qualquer outro slot, sem relação especial com o slot "preço normal" que ela substitui):

1. **Rótulo da oferta** — `parseTcServerHtml` usava o `Label2` literal do TCServer ("Custom
   price") como rótulo do box. Trocado pra `"EM OFERTA"` fixo, ignorando o texto de Label2.
2. **Ordem na tela e na fala** — produto em oferta tem prioridade: `populatePriceSlots` e
   `speakSlotsIfPossible` agora ordenam com `.sortedBy { if (it.isPromo) 0 else 1 }`, oferta
   sempre primeiro.
3. **Box duplicado** — o preço cheio ("De: X" riscado) já aparece DENTRO do box de oferta; antes
   ele também aparecia como um box "PREÇO NORMAL" separado, redundante e ocupando espaço. Os
   dois lugares (`populatePriceSlots` e o caminho secundário com imagem "arte" de fundo, que
   usa `take(3)` e por isso cortava silenciosamente a conversão de peso quando a oferta ocupava
   uma das 3 vagas) agora removem esse box duplicado antes de renderizar.
4. **TTS "de X por Y"** — `speakSlotsIfPossible` ganhou um caso específico pra slot de oferta:
   `"Produto em oferta! De ${buildSpokenPrice(fromValue)} por ${buildSpokenPrice(valor)}."` em
   vez de só falar o rótulo + valor. Confirmado via log (`tts_slots_text`): *"Produto em oferta!
   De 22 reais e 49 centavos por 19 reais e 32 centavos. 3 dólares e 79 centavos. 2760 pesos."*
5. **Conversão de moeda usa o preço de oferta** — `appendForeignCurrencySlots` agora converte
   `activePrice` (preço de oferta quando existe, senão o preço normal), não sempre o preço
   cheio — é o valor que o cliente realmente paga.
