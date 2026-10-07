# Changelog — 2026-10-06 — Leitor "ligado mas não lê": race condition real no ciclo de arme

Escrito por: Claude (sessão Claude Code, originada em `argos-remote`,
aplicada em `mplayer`).

## Origem

Investigando um relato de "linha bloqueando a tela pra deslizar" num
UROVO SK100 (sessão `argos-remote`), o usuário trouxe um diagnóstico do
time da Gertec sobre o leitor do cliente Koch "ligado mas não lê",
baseado em análise real do log `MPlayerScan: gertec_sdk_arm ciclo=N/20`,
mais uma nova versão do SDK (`GerSDK_v1.0.6_261006_release.aar`).

## Causa raiz (diagnóstico da Gertec, não suposição)

O ciclo de re-arm antigo (`GertecScannerManager`, re-arma a cada ~5,2s —
`REARM_INTERVAL_MS` 4s + `ARM_GAP_MS` 1,2s) **matava cada tentativa no
meio da negociação com o scanner**:

- Dos ~4s disponíveis antes do próximo `stopService()`, ~1,5-1,7s já eram
  só a enumeração USB do `/dev/ttyACM0`.
- A troca de dados real (~55 frames por ciclo) nunca terminava a tempo —
  a sessão era sempre derrubada no meio da inicialização.
- Sem cancelar/esperar a tentativa anterior antes de iniciar a próxima: o
  log mostrou **duas inicializações simultâneas na mesma porta serial**,
  em threads diferentes, disputando a UART.
- Coroutines de ciclos antigos continuavam vivas depois do "release" —
  ex.: `saveConfig` falhando com `1001 "Scanner not initialized"` ~2s
  depois do fechamento, de uma tentativa que o app já achava morta.

O contador de ciclos no log (`ciclo=21/20`) não é um bug de condição de
parada de verdade — é só um artefato de log: `ARM_MAX_CYCLES` (a antiga
constante "20") continuava sendo usado SÓ no texto do log, mas o teto de
verdade já tinha sido removido numa sessão anterior (ver
`CHANGELOG_SESSAO_2026-10-04_SCANNER_SK100_SEMPRE_LIGADO.md`) — o loop já
rodava sem parar, só exibindo "/20" de propósito desatualizado.

## Fix

`GertecScannerManager.kt` reescrito seguindo a arquitetura sugerida pela
Gertec, adaptada ao estilo do arquivo:

1. **Uma tentativa de arme por vez** — `armJob` nunca é substituído
   enquanto `isActive`; `start()` vira no-op se já há um em curso.
2. **Timeout de verdade por tentativa** (`INIT_TIMEOUT_MS` = 10s, também
   passado pro próprio `ScanConfig.timeout` do SDK) — confirmação via
   `codeScanner.isRunning()`, reportado como confiável a partir do
   GerSDK v1.0.6 (a versão antiga "mentia", por isso o código anterior
   evitava confiar nele e só confirmava via leitura real).
3. **Backoff exponencial** (2s → 4s → 8s... teto de 30s) entre tentativas
   que falham, em vez do intervalo fixo de 5,2s — evita ligar/desligar o
   módulo físico do scanner ~12x/minuto.
4. **Nunca desiste** (pedido original do usuário, 04/10: "o leitor não
   pode desligar nunca") — preservado, sem teto de tentativas.

## SDK

Trocado `libs/GerSDK_v104.aar` → `libs/GerSDK_v106.aar`
(`GerSDK_v1.0.6_261006_release.aar`, enviado pelo usuário). API pública
idêntica entre as duas versões (confirmado via `javap` nas duas —
`CodeScanner`/`ScanConfig`/`ScannerCallback` com as mesmas assinaturas),
troca direta sem mudar nenhum call site fora do próprio
`GertecScannerManager`. v104.aar mantido no repo só por precaução de
rollback, sem referência no `build.gradle.kts`.

## Bug real encontrado em teste ao vivo (achado NESTA sessão, não da Gertec)

A primeira versão da reescrita usava a sobrecarga nova
`scanCode(Context, ScanConfig, String)` com `CodeScanner.ALL_CODE_TYPES`
(seguindo o exemplo literal do time da Gertec). Testado ao vivo no SK100
real (`4001442606002108`, via cabo USB + `adb logcat`, autorização de
depuração USB), essa chamada lança uma `NullPointerException` **interna
do SDK, instantaneamente** (0-3ms, antes de qualquer tentativa de ligar o
hardware):

```
W/MPlayerScan: gertec_sdk_scan_failed ciclo=1 err=NullPointerException:
  Parameter specified as non-null is null: method
  br.com.gertec.retailnexus.internal.k7.a, parameter type
```

O leitor nem chegava a acender a luz — batia exatamente com o sintoma
reportado ("não ativou o leitor"). O código original NUNCA usava essa
sobrecarga (só `scanCode(context)`, sem `ScanConfig`) — provavelmente um
caminho menos testado no SDK v1.0.6. Fix: voltar pra `scanCode(context)`,
mantendo as outras 3 mudanças (single-flight, timeout via
`withTimeoutOrNull` do lado do app, backoff exponencial). Reportado de
volta pro time da Gertec (ver
`FEEDBACK_GERTEC_SDK_v106_scanCode_ScanConfig_NPE.md`).

## Confirmado ao vivo (2026-10-06)

Depois do fix acima, reinstalado via `adb install -r` direto no SK100 de
teste. Primeiro teste (com o cabo USB ainda conectado, pra log) falhou
com `ScannerException{errorCode=1002}` — mas esperado: o log mostrou o
SDK procurando `/dev/ttyACM0`-`ttyACM9` e nenhum existia, confirmando o
achado já documentado em
`feedback_scanner_sk100_identifica_como_teclado_usb` (memória): o leitor
interno e o cabo USB externo disputam o MESMO barramento — com o cabo
plugado, o módulo do leitor não enumera. Com o cabo desconectado, teste
físico do usuário confirmado: **leitor funcionando normal**.

## Versão

MPlayer Enterprise: versionCode 71 / 1.1.54 (a 70/1.1.53 tinha o bug do
ScanConfig acima — não usar em produção, pular direto pra 71).
