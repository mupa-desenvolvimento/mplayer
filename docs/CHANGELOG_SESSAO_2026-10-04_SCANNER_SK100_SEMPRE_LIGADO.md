# Changelog — 2026-10-04 — Leitor do SK100 sempre ligado + investigação do modo "Enter Keyboard"

Escrito por: Claude (sessão Claude Code, originada em `argos-remote`,
aplicada em `mplayer`).

## 1. Pedido do usuário

"Preciso que me garanta também, que, quando o agent reconhecer que o
Dispositivo é um UROVO SK100 deve ativar o leitor... O Leitor não pode
deligar nunca... sempre ligado e com o recurso de leitura com ENTER
KEYBOARD."

## 2. "Sempre ligado" — implementado

Hoje o leitor Gertec só liga se o toggle "Leitor integrado Gertec
(SK100)" em Configurações estiver marcado manualmente — por padrão vem
**desligado** (`SettingsManager.getGertecScannerEnabled()` default
`false`). Isso contradiz "nunca desligar".

- `PlayerActivity.onResume()`: `gertecEnabled` agora é
  `GertecScannerManager.isGertecDevice() || <valor salvo>` — em
  qualquer SK100/terminal Gertec (detectado por manufacturer/model, ver
  `isGertecDevice()`) o leitor ** sempre** inicia, ignorando o valor
  salvo. O branch que chamava `gertecScanner.stop()` fica inalcançável
  nesses aparelhos.
- `SettingsActivity`: o toggle correspondente agora aparece **marcado e
  travado** (`isEnabled = false`) nesses terminais, pra não sugerir que
  dá pra desligar por ali. Em qualquer outro terminal (G-BOT, X96,
  ST-103 — sem esse leitor embarcado) nada muda.
- Não mexi em `GertecScannerManager.scheduleNextCycle()` — já havia uma
  alteração não commitada (de outra sessão/dev) removendo o teto de 20
  ciclos de rearme, que já cobre a parte "nunca desiste de tentar ler".
  Build compilado localmente confirma que as duas mudanças coexistem sem
  conflito.

## 3. "Enter Keyboard" — investigado, NÃO implementado (incompatibilidade real confirmada)

A API pra isso existe de verdade dentro do `GerSDK_v104.aar` já incluso
no projeto: `com.urovo.scansdk.WindowScanner` →
`output().setInterfaceType(OutputConfig.INTERFACE_TYPE_KEYBOARD)` +
`PrefixSuffixConfig` pra configurar o terminador (Enter) e
`KeyboardConfig` pro layout BR. Achei essa API só de vasculhar o AAR —
não estava sendo usada em lugar nenhum do app.

Antes de implementar, achei um comentário em `MupaApplication.kt`
dizendo que essa MESMA API já tinha lançado `ScannerException` nesse
modelo (i9100) antes, derrubando o app (por isso existe um crash guard
global lá). Em vez de confiar nesse comentário antigo, **testei ao
vivo, agora**: criei um módulo Gradle isolado (`scanner-probe/`,
applicationId `com.mupa.scannerprobe`, mesmo AAR, zero dependência do
app principal) que só chama `WindowScanner.getInstance().initialize()`
e loga o resultado. Instalei ao lado do MPlayer real (sem tocar nele) no
SK100 de teste, tive que sair do Kiosk temporariamente
(`kiosk_unpin`/`kiosk_pin` via painel, porque LockTask bloqueia abrir
qualquer outro app) e rodei.

**Resultado, reproduzido agora mesmo:**

```
ScannerException{errorCode=1002, message='Failed to initialize scanner'}
  at com.urovo.scansdk.WindowScanner.initialize(WindowScanner.java:148)
```

Ou seja: a incompatibilidade é real, atual, e não é falta de
configuração — `com.urovo.scansdk.WindowScanner` espera uma porta
serial/UART dedicada que não está acessível da forma que esse SK100
expõe o módulo tsg820 (o `CodeScanner` do GerSDK em modo CDC, que já
funciona, fala com ele por outro transporte). Não implementei o modo
Enter Keyboard porque ele quebraria o app de verdade neste hardware.

**O que já existe e cobre o efeito prático**: o callback do
`GertecScannerManager` (modo CDC) já entrega o código lido direto pra
`onBarcodeCaptured()` — ou seja, scan → ação imediata, sem precisar de
nenhum botão, igual ao resultado que um "Enter Keyboard" real daria,
só que por outro caminho (callback do SDK em vez de eventos de teclado
reais). Pra qualquer uso que dependa especificamente de eventos de
teclado reais (ex.: alguma integração externa que só escuta
KeyEvent/HID), isso NÃO está coberto — se for o caso, essa é uma
conversa separada pra ter com o usuário.

## 4. Estado do repositório

Mudanças feitas (não commitadas, igual ao resto do trabalho em
andamento neste repo nesta sessão):
- `app/.../ui/PlayerActivity.kt` — força `gertecEnabled` em SK100.
- `app/.../ui/SettingsActivity.kt` — toggle travado/marcado em SK100.
- `scanner-probe/` (módulo novo, não referenciado pelo app principal) —
  ferramenta de diagnóstico reutilizável pra testar hipóteses do SDK
  Urovo sem arriscar o app de produção. Pode ficar no repo (isolado) ou
  ser removido — decisão do usuário.
- `settings.gradle.kts` — inclui o módulo novo.

Nenhum commit foi feito. Nenhuma mudança foi deployada pro app de
produção do SK100 (o build instalado nesse aparelho é assinado com uma
chave diferente da debug local — reinstalar exigiria o pipeline de
release normal, não um `adb install -r` ad-hoc).
