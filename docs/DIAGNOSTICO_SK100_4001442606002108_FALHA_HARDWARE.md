# SK100 4001442606002108: leitor parou de ler — falha de hardware (2026-10-07)

Pedido do usuário: "o leitor não está lendo, simplesmente parou" —
"estava lendo antes normal, deixei parado e parece que desativou
sozinho". Investigação ao vivo via `adb logcat` + `adb shell`.

## Dois problemas distintos encontrados

### 1. Gap real no código (corrigido)

`GertecScannerManager.start()` só é chamado por
`PlayerActivity.onResume()` — que nunca mais dispara num kiosk ligado
parado o dia inteiro na mesma tela. Se o SDK Gertec cancelar a sessão
sozinho DEPOIS de já ter armado com sucesso (callback `cancelled()`),
`started` virava `false` e nada percebia: o loop de retry daquela
tentativa de arme já tinha terminado. O item "nunca desiste" do
cabeçalho do arquivo só cobria retries DENTRO de uma sequência de arme —
não esse caso de "armou, funcionou, morreu sozinho depois".

**Fix**: `cancelled()` agora guarda o `Context` da última chamada de
`start()` e se re-arma sozinho, sem depender da Activity notar. Ver
comentário no cabeçalho do arquivo e no próprio `cancelled()`.

Este fix é real e vale a pena manter — mas **não é a causa do problema
atual neste aparelho específico** (ver abaixo).

### 2. Falha de hardware neste aparelho específico (NÃO é bug de software)

Depois de aplicar o fix acima, reinstalar e testar ao vivo, o leitor
continuou falhando — mas de um jeito diferente do que o fix acima
resolve: `"Failed to initialize scanner"` em TODA tentativa, incluindo
logo após reiniciar o aparelho do zero. Isso não é "adormeceu depois de
um tempo" — é "não consegue nem começar".

Diagnóstico, em ordem:

1. `adb shell ls /dev/ttyACM*` → `No such file or directory`. O próprio
   SDK da Gertec tenta abrir `/dev/ttyACM0` até `/dev/ttyACM9` (log tag
   `u7`) — **todos** retornam "file not exists".
2. `adb reboot` → mesmo resultado depois do aparelho voltar.
3. Testado também com o cabo de debug TOTALMENTE desconectado
   (confirmado pelo usuário) → mesmo resultado. Descarta a hipótese de
   conflito entre o cabo de debug (adb) e o barramento USB interno do
   leitor.
4. Hipótese testada: `com.urovo.windowscannerservice` (driver
   concorrente da UROVO, já documentado em sessão anterior como "não é o
   caminho certo" — ver `feedback_urovo_windowscanner_keyboard_mode_falha`
   na memória do projeto) estaria disputando o recurso USB antes do
   driver CDC da Gertec conseguir criar os nós `/dev/ttyACM*`. Desativado
   via `pm disable-user` + `force-stop` — **mesmo resultado**. Hipótese
   descartada, serviço reativado ao estado original
   (`pm enable`) ao final do teste.
5. `adb shell ls /dev/bus/usb/001/` e `/sys/bus/usb/devices/` → só o hub
   raiz (`usb1`) aparece. **Nenhum dispositivo USB enumera no barramento
   interno**, nem no nível de kernel, antes de qualquer driver (Gertec ou
   UROVO) entrar em cena.

## Conclusão

O módulo físico do leitor deste aparelho específico não está sendo
detectado pelo barramento USB interno em nível de kernel — nem reboot,
nem eliminar concorrência de software mudam isso. Esse é o tipo de
sintoma de falha física (conector interno solto, módulo de leitura
danificado) que nenhum fix de app resolve. Recomendação: inspeção física
do aparelho / acionar suporte Gertec-UROVO para esse SN específico
(RMA/reparo), não um caminho de debug de software.

## O que NÃO foi a causa

- Não foi o cabo de debug (testado sem cabo, mesmo resultado).
- Não foi o `com.urovo.windowscannerservice` disputando o recurso
  (desativado, mesmo resultado).
- Não foi falta de reboot (testado, mesmo resultado).
- Não é o mesmo bug do item 1 acima (aquele é "parou depois de um
  tempo"; este é "não liga desde o boot").

## Versão

`GertecScannerManager.kt` — fix do self-heal (item 1) commitado e
publicado em `mupa-desenvolvimento/mplayer`. Nenhuma mudança de versão
specific pra este diagnóstico — é um achado de hardware, não um fix de
código.
