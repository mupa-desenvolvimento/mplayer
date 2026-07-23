# [OPEN] Device Orientation Layout

## Sessao
- sessionId: `device-orientation-layout`
- objetivo: validar no equipamento se o layout muda corretamente entre portrait e landscape
- dispositivo: `24067524701479`
- projeto: `c:\src\mplayer`

## Sintoma
- O usuario quer que o app identifique corretamente quando o dispositivo esta em `Portrait` e `Landscape`
- O comportamento visual esperado foi enviado em duas imagens de referencia

## Hipoteses
1. O app nao esta reagindo a mudanca de configuracao/orientacao em runtime.
2. O app detecta a orientacao, mas escolhe o layout XML errado para portrait vs landscape.
3. O dispositivo esta travando a orientacao da `Activity` por configuracao/manifesta.
4. O problema esta na renderizacao dos blocos de imagem/texto, e nao na deteccao da orientacao em si.
5. O equipamento Zebra envia dimensoes/configuracao diferentes do esperado e isso quebra a regra atual.

## Plano
1. Confirmar conexao `adb` com o serial informado.
2. Identificar o pacote instalado e a activity principal.
3. Coletar evidencias de orientacao atual e comportamento visual em portrait/landscape.
4. Validar no codigo como a orientacao e decidida.
5. Se houver divergencia, instrumentar logs minimos e propor correcao.

## Evidencias
- `adb devices -l` confirmou o dispositivo `24067524701479` conectado como `ET45`.
- `adb shell wm size` retornou `1200x1920`.
- `dumpsys input` confirmou `SurfaceOrientation: 0` em retrato.
- Captura em retrato mostrou o app em composicao vertical.
- Captura em paisagem mostrou o app em composicao horizontal.
- O codigo em `PlayerActivity.applyPriceOverlayLayout()` usa `Configuration.ORIENTATION_PORTRAIT` para empilhar imagem em cima e informacoes embaixo, e em `landscape` coloca informacoes a esquerda e imagem a direita.
- A instrumentacao da sessao registrou que o bind principal do overlay completou sem excecao:
  - `B`: render job iniciou
  - `C`: imagem/layout preparados
  - `D`: entrada no bind principal
  - `F`: bind principal concluido
  - `G`: job finalizado sem cancelamento nem throwable
- Ao tentar abrir a build instrumentada pelo launcher do ET45, o equipamento exibiu: `Nao e possivel abrir este app`.
- `adb shell am start -n com.mupa.player.enterprise/.ui.PlayerActivity` falhou com `SecurityException` porque a activity nao e exportada.
- `adb shell am start -n com.mupa.player.enterprise/.ui.SplashActivity` nao trouxe o app para frente de forma utilizavel para o teste final.
- `dumpsys package com.mupa.player.enterprise` mostrou `suspended=true`, `enabled=1` e `stopped=true` para o usuario 0.
- `dumpsys device_policy` identificou o `Device Owner` como `com.mupa.agent.argos/.mdm.ArgosDeviceAdminReceiver`.
- Nova rodada validada no ET45 com o comando `cmd window set-user-rotation lock`.
- Em paisagem:
  - `dumpsys input` retornou `SurfaceOrientation: 1`, `1920x1200`.
  - A instrumentacao registrou `orientation: 2` e `layoutType: multi_price`.
  - A captura mostrou informacoes a esquerda e imagem a direita, como esperado.
- Em retrato antes da correcao:
  - `dumpsys input` retornou `SurfaceOrientation: 0`, `1200x1920`.
  - A instrumentacao registrou `orientation: 1`.
  - A captura mostrou layout ainda em duas colunas, o que confirmou falha de adaptacao em portrait.
- Correcao aplicada em `applyPriceOverlayLayout()`:
  - ajuste explicito da orientacao do guideline via `ConstraintLayout.LayoutParams`
  - `requestLayout()` apos `set.applyTo(root)`
- Em retrato depois da correcao:
  - a instrumentacao continuou registrando `orientation: 1`
  - a captura deixou de ficar em duas colunas, mas ainda nao bate com o esperado: apareceu apenas a area da imagem, sem as informacoes empilhadas abaixo
  - isso indica que a deteccao de orientacao esta correta, mas a montagem do layout portrait ainda esta incompleta/incorreta

## Resultado
- Diagnostico atualizado.
- Confirmado: o ET45 muda corretamente entre `portrait` e `landscape`.
- Confirmado: `landscape` esta funcional no app e segue a regra de informacoes a esquerda e imagem a direita.
- Confirmado: `portrait` ainda esta incorreto no app.
- Estado atual do bug:
  - a activity detecta `portrait`
  - a renderizacao nao produz o layout esperado de imagem em cima e informacoes embaixo
- Proximo passo tecnico: refinar a montagem do `multi_price` em portrait, provavelmente removendo a dependencia de troca dinamica de orientacao do mesmo guideline ou usando uma estrutura dedicada para portrait.
