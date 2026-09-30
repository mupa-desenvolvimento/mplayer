[OPEN]

Session: presence-waiting-stuck
Data: 2026-06-06

## Sintoma
- mplayer_renner não sai de "aguardando presença" e não inicia o reconhecimento (faces/presença) para acionar o fluxo de Engage.

## Hipóteses (falsificáveis)
- A) Permissão de câmera não foi concedida no fluxo do Player/Audience, então o AudienceAnalytics não inicia.
- B) A câmera frontal está em uso por outro fluxo (CameraTest/Engage/MDM) e o bind do CameraX falha, mantendo “aguardando presença”.
- C) O pipeline de detecção está rodando, mas sempre retorna 0 faces (rotação/espelhamento/format) por incompatibilidade com o dispositivo.
- D) Engage está desabilitado na configuração remota/manifest e o app fica apenas no estado “aguardando presença”.
- E) Exceção silenciosa no start/processamento (capturada e ignorada) impede evolução do estado.

## Evidência esperada
- Logs com: status de permissão, tentativa de bind CameraX, frames processados e contagem de faces, mudanças do estado de presença e decisão de launch.

