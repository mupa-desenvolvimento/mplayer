# Changelog — 2026-10-19 — Confirmação do uso do GerSDK, teste de modo teclado e remoção definitiva

Escrito por: Claude (sessão Claude Code, originada em `argos-remote`,
aplicada em `mplayer`).

## 1. Pedido do usuário

A Gertec (suporte, contato "Adriano") pediu o APK do MPlayer pro time de
engenharia deles, e perguntou se um teste feito presencialmente (trocar
o leitor de GerSDK pra modo teclado) resolvia o "parar de ler" que o
cliente reportou. O usuário então pediu garantia de que o MPlayer **não**
usa GerSDK — a resposta real foi o oposto: usa, de forma direta e
independente do agente ARGOS Remote (que também usa GerSDK, mas noutro
app, noutra classe, sem compartilhar código).

## 2. Como a leitura funcionava até aqui (confirmado por código)

Em terminal detectado como Gertec/SK100 (`GertecScannerManager.isGertecDevice()`:
`Build.MANUFACTURER`/`MODEL`/`DEVICE` contendo "SK", "gertec", "urovo" ou
igual a "i9100" — o módulo de leitura do SK100 é um UROVO tsg820
embarcado, por isso o SK100 se reporta como UROVO/i9100), o GerSDK era
**forçado ligado incondicionalmente** em `PlayerActivity.onResume()`:

```kotlin
val gertecEnabled = GertecScannerManager.isGertecDevice() || getGertecScannerEnabled()
```

Isso ignorava deliberadamente o valor salvo em Configurações (decisão de
produto de 2026-10-04: "o leitor não pode nunca ficar desligado nesses
terminais" — ver `CHANGELOG_SESSAO_2026-10-04_SCANNER_SK100_SEMPRE_LIGADO.md`).
O toggle "Leitor integrado Gertec (SK100)" ficava visível mas **travado**
(`isEnabled = false`). Enquanto o GerSDK estava ativo, `dispatchKeyEvent`
pulava a captura por teclado/HID por completo, pra não duplicar leitura.

**Conclusão prática**: qualquer tentativa de usar o toggle do app pra
trocar pra modo teclado num SK100 real nunca surtia efeito — o código
revertia pra GerSDK sozinho no próximo `onResume()`.

## 3. Feature temporária: override "Forçar modo teclado"

Pra permitir testar o modo teclado sugerido pela Gertec sem reescrever
nada ainda, adicionei um switch novo e independente em Configurações
("Forçar modo teclado (ignora GerSDK)"), que — ao contrário do switch
antigo — nunca ficava travado, nem em terminal Gertec. Quando ligado,
`gertecEnabled` virava `false` incondicionalmente, parando o GerSDK e
liberando o `dispatchKeyEvent` existente.

## 4. Teste ao vivo no SK100 (`sk100londres4609`, serial `4001442606004609`)

Conectei via Wireless ADB (pareamento por código), capturei `logcat`
filtrado (`MPlayerScan:V`) e pedi pro usuário ler a mesma sequência de
códigos em 3 cenários:

**a) GerSDK normal (baseline)** — 8/8 leituras via `gertec_sdk_scan` →
`barcode_captured`, sem falhas, ~1,2–3,6s entre leituras.

**b) "Forçar modo teclado" ligado, só isso** — **não leu nada**. Causa:
o override para o GerSDK, mas o leitor físico só emite eventos de
teclado se o **firmware do terminal** (não o app) estiver configurado
pra isso — uma tela própria em Configurações do Android
(`com.android.settings`), fora do controle do app, com dois switches:
- **"Scanner como teclado"** — chave mestra: faz o módulo físico emitir
  HID em vez de falar só com o SDK proprietário.
- **"Enter no teclado de entrada"** — envia uma tecla Enter ao final da
  leitura.

Ambos estavam **desligados** por padrão.

**c) "Forçar modo teclado" (app) + "Scanner como teclado" (firmware) +
"Enter no teclado de entrada" (firmware), os 3 ligados** — 8/9 leituras
limpas, **sem nenhuma linha `gertec_sdk_scan`** no log (confirma que o
GerSDK não participou) — tudo veio direto de `dispatchKeyEvent` →
`onBarcodeCaptured`. Sem falhas, sem crash.

**Por que o Enter importa**: `dispatchKeyEvent` só chama
`onBarcodeCaptured` ao receber `KEYCODE_ENTER` — não existe flush por
timeout. Sem a tecla Enter do firmware, os dígitos ficam acumulando no
buffer (`scanKeyBuffer`) e a leitura nunca é confirmada — exatamente o
sintoma do cenário (b), "não lê nada".

### Resultado pra passar pra Gertec

O teste presencial da Gertec ("trocar pra modo teclado") só funciona se
**as 3 chaves** estiverem ligadas ao mesmo tempo — 1 no app + 2 no
firmware do terminal. Se o teste deles só mexeu no SDK (ou só numa das
chaves de firmware), é esperado que não tenha funcionado ou tenha
"parado de ler" (nenhuma leitura chegando a lugar nenhum).

## 5. Decisão: remoção definitiva do GerSDK

Com o modo teclado validado ao vivo (8/9 leituras limpas, mesmo volume
de sucesso que o GerSDK), o usuário pediu a remoção completa do GerSDK
do MPlayer — não mais um override opcional, e sim o único caminho de
leitura em terminal Gertec/SK100 daqui pra frente (mesmo mecanismo
HID/`dispatchKeyEvent` que já existia pra terminais não-Gertec).

### O que foi removido

- `app/src/main/java/com/mupa/player/enterprise/managers/GertecScannerManager.kt`
  — arquivo inteiro deletado (wrapper do SDK, `isGertecDevice()`, lógica
  de re-arme/backoff).
- `app/build.gradle.kts` — dependência `implementation(files("libs/GerSDK_v106.aar"))`.
- `app/libs/GerSDK_v104.aar` e `GerSDK_v106.aar` — binários deletados.
- `app/src/legacy/AndroidManifest.xml` — deletado (só continha
  `<uses-sdk tools:overrideLibrary="br.com.gertec.gdk" />`).
- `MupaApplication.kt` — `installScannerCrashGuard()` removido (guard
  global de `ScannerException`/`WindowScanner`/`gertec.retailnexus` —
  sem o SDK, essas classes não existem mais no APK, guard vira código
  morto).
- `SettingsManager.kt` — chaves/getters/setters `gertecScannerEnabled` e
  `forceKeyboardScanMode` (DataStore + legacy SharedPreferences).
- `SettingsActivity.kt` + `activity_settings.xml` — os dois switches
  ("Leitor integrado Gertec (SK100)" e "Forçar modo teclado") e suas
  legendas.
- `PlayerActivity.kt` — a propriedade `gertecScanner`, o bloco de
  decisão `gertecEnabled` em `onResume()`, o gate
  `if (gertecScanner.isStarted())` em `dispatchKeyEvent` (agora sempre
  processa teclado) e a chamada `gertecScanner.stop()` em `onDestroy()`.

### Resultado

Build `assembleKompraoDebug` limpo (só warnings pré-existentes, não
relacionados). APK ~1,7MB menor sem o AAR do GerSDK embarcado. Leitura
em qualquer terminal (Gertec/SK100 incluso) passa a depender só da
configuração de firmware "Scanner como teclado" + "Enter no teclado de
entrada" — **sem dependência de nenhum SDK proprietário da Gertec**.

### Pré-requisito operacional (importante pra campo)

Terminais SK100 que ainda não tiverem as 2 chaves de firmware ligadas
**vão parar de ler código nenhum** depois dessa atualização — não há
mais fallback pro GerSDK. Antes de distribuir esse build pra qualquer
SK100 em produção, confirmar/ligar "Scanner como teclado" e "Enter no
teclado de entrada" nas Configurações do Android de cada terminal (fora
do app).
