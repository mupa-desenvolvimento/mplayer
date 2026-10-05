[OPEN] Debug Session: engage-first-question-exit

## Symptom
- No fluxo das perguntas, fica na primeira pergunta e volta (encerra/retorna ao player) logo em seguida.

## Expected
- Após responder a primeira pergunta (👍/👎), deveria exibir feedback/agradecimento e só então finalizar o Engage (retornar ao player).

## Environment
- App: mplayer_renner (ModernDebug)
- Device: Android (Zebra), via ADB

## Hypotheses (falsifiable)
1) **Timeout/gesture never stabilizes**: `waitForGesture()` retorna `0` (timeout) por não estabilizar 👍/👎 por 800ms, então o fluxo segue e finaliza rápido.
2) **Recognizer start/stop race**: `g.start()` falha/interrompe (retorna false) ou para cedo, gerando decisão `0` e saída precoce.
3) **Activity lifecycle interrupt**: `EngageActivity` recebe `onStop/onDestroy` (ex.: perda de câmera/preview, overlay, launcher) e cancela o `stageJob`, terminando antes do esperado.
4) **Result propagation issue**: `finishOk/finishCanceled` é chamado por caminho inesperado (ex.: permissão, accept==0 interpretado como recusa), retornando ao player sem completar.
5) **Crash silencioso**: exceção em thread/coroutine (cancelamento) interrompe o fluxo, e o app volta ao player/launcher sem UI de erro.

## Evidence plan
- Instrumentar EngageActivity para registrar: entrada/saída de states, `accept`, `answer`, tempos, `g.start` ok/fail, e eventos de lifecycle (onStart/onStop/onDestroy).
- Coletar logs via Debug Server (HTTP) + log local NDJSON.
- Reproduzir no device com ADB com a mesma build stamp.

## Notes
- Não alterar a lógica do fluxo antes de capturar evidência (somente instrumentação).

