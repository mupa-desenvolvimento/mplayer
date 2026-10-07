# Feedback pro time da Gertec — GerSDK v1.0.6

## 1. O diagnóstico de vocês estava certo — confirmado ao vivo

Aplicamos a arquitetura sugerida (uma tentativa de arme por vez, timeout
real por tentativa medido a partir da abertura da sessão, backoff
exponencial em vez do intervalo fixo) no `GertecScannerManager` do nosso
app (MPlayer Enterprise), junto com a atualização pro GerSDK v1.0.6.

**Resultado: confirmado funcionando em teste físico real num UROVO SK100**
(leitor lendo normalmente, sem a demora/falha de antes). Muito obrigado
pela análise detalhada do log — o diagnóstico (inicialização sendo morta
no meio da negociação com o scanner, duas sessões concorrentes na mesma
porta) bateu exatamente com o que encontramos.

Sobre o contador `ciclo=21/20` que vocês notaram no log antigo: não é um
bug de condição de parada — é só um valor fixo no texto do log (`"$i/20"`)
que sobrou de uma versão anterior do nosso código, que já tinha removido
o teto de verdade (pedido do usuário final: "o leitor nunca pode
desligar"). O "/20" ficou exibido por engano, mas o loop já rodava sem
limite de tentativas. Já ajustamos a mensagem de log pra não confundir
mais ninguém.

## 2. Bug novo encontrado no v1.0.6: `scanCode(Context, ScanConfig, String)` com `ALL_CODE_TYPES`

Seguindo literalmente o exemplo de código que vocês mandaram, usamos:

```kotlin
val config = ScanConfig().apply {
    scanMode = ScanMode.MODE_CONTINUE_SCAN_CODE
    timeout = 10_000L
}
scanner.scanCode(context, config, CodeScanner.ALL_CODE_TYPES)
```

Essa chamada lança uma `NullPointerException` **interna do SDK**,
**instantaneamente** (0 a 3ms — antes de qualquer tentativa real de
ligar/enumerar o hardware):

```
NullPointerException: Parameter specified as non-null is null: method
br.com.gertec.retailnexus.internal.k7.a, parameter type
```

- Acontece em TODAS as tentativas, de forma consistente e imediata.
- `CodeScanner.ALL_CODE_TYPES` é o próprio `String` constante exposto
  pela classe pública — não estamos passando nada customizado.
- Testado isoladamente no SK100 real (modelo `i9100`, Android), com log
  capturado via `adb logcat` no momento exato da falha.
- O texto da exceção ("parameter type") sugere que, internamente, algum
  parâmetro nomeado `type` está chegando nulo em
  `br.com.gertec.retailnexus.internal.k7.a` — possivelmente um problema
  de conversão/adaptação dessa sobrecarga específica (3 argumentos, com
  `ScanConfig`) nesta versão.

**Workaround que usamos**: voltamos pra sobrecarga mais simples,
`scanCode(Context)` (sem `ScanConfig`, sem lista de tipos), que funciona
normalmente nesta mesma versão do SDK. Não chegamos a testar as outras
sobrecargas intermediárias (`scanCode(Context, String)`,
`scanCode(Context, Collection<String>)`) — não precisamos delas pro nosso
caso de uso (aceitar qualquer tipo de código).

## 3. Ambiente do teste

- Hardware: UROVO SK100 (manufacturer=UROVO, model/device=i9100), módulo
  de leitura tsg820 embarcado.
- GerSDK: `GerSDK_v1.0.6_261006_release.aar` (o arquivo que vocês
  enviaram em 06/10).
- App: MPlayer Enterprise, Kotlin, `CodeScanner.getInstance(callback)` +
  modo CDC (sem wedge de teclado).

Qualquer log adicional ou um build de diagnóstico isolado (como fizemos
antes pro teste do `WindowScanner`) a gente roda rápido se for útil pra
vocês investigarem a causa da NPE na sobrecarga com `ScanConfig`.
