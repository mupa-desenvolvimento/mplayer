# Leitura de código de barras lenta no MC45 — diagnóstico e fix (2026-10-08)

Pedido do usuário: "o Mplayer pode impactar no desempenho da leitura de
código de barras, leitor está difícil de ler os códigos, lê, mas tem
muita dificuldade pra realizar a leitura" — depois: "fico posicionando o
produto, parece que ele não foca, só dispara a leitura depois que pega
um foco."

Dispositivo: MEFERI MC45, `DER4BT125115002178`, empresa "Komprão".

## Auditoria: o MPlayer não é a causa

Investigação completa (CPU, memória, térmica, alimentação) antes de
mexer em qualquer configuração:

- **CPU/memória**: sem contenção visível no momento do teste (idle
  majoritário, ~4,4% RAM pro MPlayer).
- **Térmica**: CPU/GPU/SOC em ~49°C, bem abaixo do limite de throttling
  (70°C) — `dumpsys thermalservice` confirmou status normal.
- **Alimentação**: AC estável (`dumpsys battery`), sem dependência de
  bateria.
- **Mecanismo de entrega do código**: o MC45 usa broadcast Intent
  (`android.intent.action.MEF_ACTION`), não o modo teclado
  caractere-por-caractere (esse sim vulnerável a timing/jank — usado só
  pelo leitor Gertec/SK100). Confirmado via log: cada leitura bem-sucedida
  chega como string completa e é processada pelo MPlayer em
  **30-40ms**, de forma extremamente confiável.

**Conclusão**: o software (MPlayer, Android, CPU) não é o gargalo. A
demora acontece inteiramente DENTRO do processo nativo do scanner
(`com.meferi.scanner`, driver `Se4070Scanner`), antes de qualquer dado
chegar no app.

## Causa raiz encontrada: motor Zebra SE4070 mal configurado

O motor físico (`scannerName=SE4070-V3.0.64`) é um **Zebra SE4070** —
motor *imager* 2D (câmera com foco automático), não laser simples. Isso
explica o sintoma ("parece que não foca"): câmeras precisam de
exposição/foco adequados antes de conseguir decodificar, diferente de
um laser de varredura simples.

Medindo o tempo real do driver nativo (log `Se4070Scanner`, do início de
uma tentativa `keyScan` até a decodificação `isSkipHandleData`), ANTES
de qualquer ajuste:

| Leitura | Tempo até decodificar |
|---|---|
| 1 | 6,16s |
| 2 | 3,82s |

Dois problemas de configuração identificados, via app nativo de teste
da MEFERI (`com.meferi.mewedge`, aba Symbologies/Settings):

1. **Todos os tipos de código de barras habilitados ao mesmo tempo**
   (14 symbologies ativos: EAN-13, EAN-8, UPC-A, UPC-E, Code 128,
   GS1-128, GS1 DataBar — os realmente usados — mas TAMBÉM Aztec, Code
   11, Code 39, Code 93, Data Matrix, Interleaved 2 of 5/ITF, PDF417,
   QRCode, Standard 25/IATA 25, nenhum deles usado em código de produto
   de varejo). Cada tentativa de decodificação testa a imagem capturada
   contra TODOS os formatos habilitados — quanto mais formatos, mais
   tempo o motor gasta por tentativa.
2. **Iluminação em modo "liga só ao ler"** (`Illumination enable:
   Illuminates when reading`) em vez de sempre ligada — câmeras 2D
   precisam de um instante pra "assentar" a exposição depois que a luz
   acende; com a luz ligando só no momento exato da tentativa, esse
   ajuste acontece TODA vez, em vez de uma vez só.

## Fix aplicado (configuração do motor, não código)

Via app nativo MeWedge, aba **Symbologies** — desabilitados os 9
formatos não usados em código de produto de varejo: Aztec, Code 11,
Code 39, Code 93, Data Matrix, Interleaved 2 of 5/ITF/Cross 25 Code,
PDF417, QRCode, Standard 25/IATA 25. Mantidos: EAN-13, EAN-8, UPC-A,
UPC-E, Code 128, GS1-128 (UCC/EAN-128), GS1 DataBar(RSS) — os dois
últimos usados em produtos de peso variável (frutas, hortifruti,
frios).

Via app nativo MeWedge, aba **Settings → Reader params → Exposure
Settings** — `Illumination enable` trocado de "Illuminates when
reading" pra **"Always on"**.

## Resultado medido

Mesmo teste (tempo do `keyScan` até `isSkipHandleData`), DEPOIS do
ajuste — 15 leituras consecutivas no app nativo de teste:

- Tempo médio: **~1,07s** (contra 4,99s antes) — melhoria de **~5x**.
- Várias leituras caíram pra **200-250ms** (praticamente instantâneas).

Confirmado de novo no fluxo REAL do MPlayer (não só no app de teste) —
5 leituras consecutivas, tempo médio **~1,13s**, cada uma processada
pelo app em 10-15ms depois da decodificação. Consistente com o teste
isolado.

## Pendência: essa configuração é LOCAL a este aparelho

O ajuste foi feito manualmente, device por device, via UI do app nativo
— não existe (até onde investigado) um mecanismo de propagação via MDM/
Policy do ARGOS Remote pra essas configurações do motor SE4070 (são
internas ao MeWedge/firmware, fora do escopo do ARGOS). Se outros MC45
da frota tiverem o mesmo sintoma, a mesma configuração (Symbologies +
Illumination) precisa ser aplicada manualmente em cada um, ou
investigar se o MeWedge suporta exportar/importar um profile de
configuração (visto na tela inicial do app: "Default" como nome de
profile, sugere suporte a múltiplos profiles — não testado se há
export/import).

## Versão

Nenhuma mudança de código nesta sessão — só configuração do motor via
app nativo do fabricante. O fix do `GertecScannerManager` (loop de
retry inútil nesse device, achado na mesma auditoria) está em
`project_sk100_scanner_selfheal_e_falha_hardware` — **não publicado
ainda**, aguardando aprovação do usuário.
