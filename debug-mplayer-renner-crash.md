[OPEN]

Session: mplayer-renner-crash
Data: 2026-06-06

## Sintoma
- APK do mplayer_renner instalado em dispositivo Android (Zebra ET45 / Android Enterprise) está crashando.

## Objetivo
- Capturar evidência de runtime (stacktrace + marcos de inicialização) sem depender de logcat e sem coletar fotos/vídeos/imagens.

## Hipóteses (falsificáveis)
- A) Crash ocorre antes/na criação do Application/Activity (ex.: exception no onCreate/attachBaseContext).
- B) Crash por falha de recursos/tema/manifest merge (ResourceNotFound/InflateException).
- C) Crash por SecurityException/Settings/Device Admin/DO/kiosk ao aplicar flags/ajustes (ex.: Settings.System).
- D) Crash por inicialização de rede/registro/sync (ex.: NPE/IllegalState na validação/cached state).
- E) Crash por tentativa de iniciar Mupa Engage (ClassNotFound/ActivityNotFound/NoClassDefFoundError).

## Instrumentação planejada
- Registrar eventos HTTP (NDJSON) em Debug Server:
  - app_start (Application.onCreate)
  - splash_onCreate + decisão de rota (online/offline/cached)
  - player_onCreate
  - engage_launch (se acionado)
  - uncaught_exception (stacktrace)

## Como reproduzir (checklist)
- Iniciar Debug Server em modo --remote
- Configurar o app com a URL do Debug Server (arquivo debug-server-url.txt em externalFilesDir)
- Reinstalar APK debug e abrir o app
- Coletar /logs e analisar hipótese confirmada

