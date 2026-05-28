# MPlayer (Mupa Player Enterprise)

Aplicativo Android para reprodução de mídia e consulta de produtos/preços, com suporte a dispositivos legados (ex.: Android 6.0.1) e integração com serviços internos da Mupa via Supabase.

## Módulos do repositório

- `app/`: aplicativo principal (`applicationId`: `com.mupa.player.enterprise`)
- `agent/`: agente/launcher (quando aplicável ao ecossistema Argos/MDM)

## Requisitos

- Android Studio (recomendado) ou Gradle
- JDK 17
- Android SDK instalado (projeto compila com `compileSdk=34`)

## Configuração (tokens/URLs)

O projeto lê configurações via `gradle.properties`, `local.properties` ou variáveis de ambiente.

- `SUPABASE_TOKEN`: token usado por chamadas internas (não commitar em arquivo no repositório)
- Assinatura de release (opcional):
  - `RELEASE_STORE_FILE`
  - `RELEASE_STORE_PASSWORD`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_KEY_PASSWORD`

## Build

O `app` possui dois flavors:

- `legacy` (`minSdk=21`): recomendado para devices antigos (inclui Android 6.0.1)
- `modern` (`minSdk=24`)

Comandos:

```powershell
cd c:\src\mupa_player_enterprise
.\gradlew :app:assembleLegacyDebug
```

Saída do APK (legacy debug):

`app\build\outputs\apk\legacy\debug\app-legacy-debug.apk`

## Instalação no device (ADB)

Em alguns devices/ADB mais instáveis, a instalação direta pode falhar. O método mais resiliente é `push` + `pm install`:

```powershell
adb push "c:\src\mupa_player_enterprise\app\build\outputs\apk\legacy\debug\app-legacy-debug.apk" /data/local/tmp/mplayer-legacy-debug.apk
adb shell pm install -r -d /data/local/tmp/mplayer-legacy-debug.apk
```

Se o device ficar offline:

```powershell
adb kill-server
adb start-server
```

## Fluxo do app (alto nível)

- `SplashActivity`: inicialização, validação e encaminhamento
- `DeviceRegistrationActivity`: registro/validação do dispositivo quando necessário
- `PlayerActivity`: reprodução (Media3/ExoPlayer), sincronização de mídia/manifesto e overlay de consulta de produto/preço

## Consulta de produto/preço

O overlay de preço é acionado ao capturar um código (scanner/teclado). Existem 2 rotas principais:

1. **Modo normal**: usa a integração configurada (ex.: `integra-assai`) via `PriceQueryEngine`
2. **DevMode (DEMO)**: consulta a API interna de produtos (Supabase Function) e renderiza como `DemoProduct`

### DevMode (manual no device)

DevMode pode ser alternado sem MDM/Argos de duas formas:

1) Long-press no watermark (ID do device ou versão do APK) na tela do Player.

2) Comando pelo scanner/entrada de código:

- Liga: `devon`, `devmode=true`, `devmode=1`, `demoon`
- Desliga: `devoff`, `devmode=false`, `devmode=0`, `demooff`

Quando ligado, o watermark mostra `• DEMO`.

### DevMode via Broadcast (MDM/integração assinada)

Existe um receiver protegido por permissão de assinatura:

- Action: `com.mupa.player.enterprise.ACTION_SET_DEV_MODE`
- Extra: `enabled` (boolean)
- Permissão: `com.mupa.player.enterprise.permission.SET_DEV_MODE` (signature)

### Endpoint de produtos (DevMode)

Quando `devMode=true`, o app consulta:

`https://vsocztidewsdlzcongkz.supabase.co/functions/v1/api-produtos?ean=<EAN>`

Exemplo:

```bash
curl --location "https://vsocztidewsdlzcongkz.supabase.co/functions/v1/api-produtos?ean=7894900027013"
```

## Compatibilidade Android 6 (TLS e estabilidade)

Dispositivos com Android antigo podem falhar em HTTPS por cadeia de certificados desatualizada (“Trust anchor not found”). O app utiliza um cliente HTTP compatível para reduzir esse tipo de falha em chamadas de manifesto/mídia e integrações de preço.

## Audience / Reconhecimento facial (CameraX)

O módulo de audiência é inicializado somente quando o device suporta (versão mínima e presença de câmera frontal utilizável). Em devices sem câmera (ou Android abaixo do requisito), o app não tenta iniciar o fluxo de câmera.

## Troubleshooting rápido

- App cai ao abrir overlay de preço em device antigo:
  - Verificar se o build é `legacy`.
- Problemas de instalação:
  - Preferir `adb push` + `pm install -r -d`.
- Falhas de rede:
  - Confirmar conectividade do device e token/configuração quando aplicável.
