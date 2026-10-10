# Fix: preço falado mas não exibido (só descrição + imagem)

Data: 2026-10-09

## Relato

"Fala o preço mas não mostra valores, somente a descrição e a imagem do
produto" — EAN 7891010256746, loja 100 (Komprão).

## Causa raiz

`parseKompraoResponse` (`PriceQueryEngine.kt`) classifica cada item do
array `prices` da API como "normal" ou "secundário" (promo/clube)
comparando a `description` com o literal `"PRECO_NORMAL"`. Qualquer
outra descrição (`PRECO_PDV`, `PRECO_PDV_ATACADO`, etc.) vira slot
`isPromo=true`.

Em `populatePriceSlots` (`PlayerActivity.kt`), uma deduplicação remove
slots "secundários" cujo valor seja igual ao preço base
(`basePriceForDeduplication`) — pensada pra não repetir o mesmo preço
em duas caixas. Mas quando a API não traz nenhum item `"PRECO_NORMAL"`
de verdade (só `PRECO_PDV`/`PRECO_PDV_ATACADO`, como aconteceu ao vivo
na loja 100 pra esse produto), o preço "base" acaba sendo o próprio
valor desses slots marcados como secundários — e a dedup removia **o
único preço que existia**, deixando `validSlots` vazia. A tela ficava
só com descrição + imagem.

O TTS (`speakSlotsIfPossible`) não passa por essa deduplicação — fala
qualquer slot com valor > 0 direto de `product.priceSlots`. Por isso
falava o preço normalmente enquanto a tela ficava muda.

## Fix

`PlayerActivity.kt` (`populatePriceSlots`): a deduplicação nunca pode
zerar a lista. Se depois de remover os "duplicados" não sobrar nada,
usa a lista original (todos os slots com valor > 0, sem dedup) — preço
real nunca é escondido, mesmo que a classificação normal/secundário da
API esteja "errada" pro caso daquele produto.

```kotlin
val positiveSlots = productSlots.filter { it.value > 0.0 }
val dedupedSlots = positiveSlots.filter { slot -> /* mesma regra de antes */ }
val validSlots = dedupedSlots.ifEmpty { positiveSlots }
```

Também aumentei o log `komprao_price_resp` de 400 pra 2000 caracteres
(estava cortando o array `prices` inteiro, dificultando diagnóstico).

## Teste ao vivo (2026-10-09)

MC45 `DER4BT125115002178`, agora de fato servindo a loja 100
(confirmado pelo campo `"store":"100"` na resposta da API, visto no
logcat). Resposta real pra esse EAN incluía `PRECO_PDV: 16.49` e
`PRECO_PDV_ATACADO: 16.49` (mesmo valor) — exatamente o cenário que
disparava a dedup-pra-vazio. Antes do fix, nenhuma caixa de preço
apareceria. Depois do fix: caixa "OFERTA R$ 16,49" aparece
normalmente, TTS fala o preço.

## Fix bônus: fala de centavos

Pedido junto: "ajustar o TTS falar R$ 0,89 'oitenta e nove centavos' e
não 'zero reais e oitenta e nove centavos'".

`OfferIntelligence.buildSpokenPrice` (usada por todo TTS de preço,
inclusive slots): quando a parte de reais é zero, fala só os centavos,
sem o "0 reais e" na frente.
