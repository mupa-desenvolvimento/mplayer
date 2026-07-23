# Contrato: Argos abre o Settings do MPlayer remotamente + senha temporária de acesso

## Por quê

O terminal MPlayer tem uma tela de configurações internas (`SettingsActivity`) protegida por
acesso restrito. Hoje existem 3 formas de abrir essa tela:

1. Escanear o código de barras `settings_{últimos 4 dígitos do device_id}` (específico por
   aparelho, resolvido localmente pelo MPlayer — nada a fazer do lado do Argos).
2. Segurar por 3s o texto da versão no rodapé da tela → diálogo pede uma senha de 4 dígitos,
   válida por 1 minuto (TOTP — ver seção 2 abaixo).
3. **Este documento**: o Argos (`com.mupa.agent.argos`, Device Owner) manda um comando remoto
   (via Firebase, protocolo já documentado em `ARGOS_PLATFORM_AGENT_PROTOCOL.md`) e, ao
   recebê-lo, envia um broadcast local para o MPlayer abrir o Settings sozinho.

## 1) Broadcast: Argos → MPlayer (`open_settings` do MPlayer, não confundir com o `open_settings`
   do protocolo Firebase que abre o Settings do **Android**)

Quando o Argos receber (via `m_argos/devices/{device_id}/commands`) um comando que deva abrir
especificamente as configurações internas do MPlayer (sugestão de nome:
`open_mplayer_settings`, para não colidir com o `open_settings` já existente no protocolo,
que abre `android.settings.SETTINGS`), o Agent Argos deve, no dispositivo, disparar:

```kotlin
context.sendBroadcast(
    Intent("com.mupa.player.enterprise.ACTION_OPEN_SETTINGS").apply {
        setPackage("com.mupa.player.enterprise")
    }
)
```

- **Permissão exigida**: `com.mupa.player.enterprise.permission.OPEN_SETTINGS`
  (`protectionLevel="normal"` — não é `signature`, então não exige o mesmo keystore do
  MPlayer). O Argos precisa declarar no seu próprio `AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="com.mupa.player.enterprise.permission.OPEN_SETTINGS" />
  ```
  Sem essa declaração, o broadcast é silenciosamente rejeitado pelo Android (o `sendBroadcast`
  não lança erro, mas o MPlayer nunca recebe o Intent).
- Sem extras necessários no Intent — o receiver do MPlayer só olha a `action`.
- O MPlayer não devolve ACK por esse canal (broadcast local, sem confirmação); se quiserem
  confirmação de que o Settings abriu, isso teria que ser adicionado depois via
  `command_result` no protocolo Firebase já existente (fora do escopo deste contrato).

## 2) Senha de acesso temporária (4 dígitos, válida por 1 minuto)

Usada no diálogo do item 2 (hold de 3s no texto da versão). Fórmula — TOTP (RFC 6238),
truncado para 4 dígitos em vez dos 6 padrão, passo de 60 segundos:

```
step   = floor(epoch_unix_seconds / 60)
hmac   = HMAC-SHA1(key = ARGOS_OTP_SECRET, message = step como inteiro de 8 bytes big-endian)
code   = truncamento dinâmico RFC 4226 sobre hmac   (mesmo algoritmo do TOTP padrão/Google Authenticator)
senha  = (code mod 10000), formatado com zero-padding para 4 dígitos (ex: "0032", "9871")
```

Implementação de referência (Kotlin, MPlayer):
`app/src/main/java/com/mupa/player/enterprise/security/TotpPasswordGenerator.kt`.

- **Segredo compartilhado (`ARGOS_OTP_SECRET`)**: precisa ser o **mesmo valor exato** dos dois
  lados. No MPlayer, hoje configurado via `local.properties`/variável de ambiente (não commitado
  no código-fonte, mesmo padrão do `SUPABASE_TOKEN`). *(Precisamos alinhar por canal seguro
  qual é esse valor e como ele é rotacionado — não vai neste documento.)*
- **Tolerância de relógio**: o MPlayer aceita tanto a senha do minuto atual quanto a do minuto
  anterior, pra absorver pequena divergência de horário entre os sistemas. Recomendado que o
  painel/Argos faça o mesmo ao validar ou exibir a senha (mostrar a senha atual é suficiente,
  não precisa mostrar a anterior).
- **Não é por dispositivo**: a senha é global (mesma para qualquer terminal, naquele minuto) —
  se no futuro for necessário uma senha por aparelho, dá pra incluir o `device_id` na mensagem
  do HMAC, mas isso muda a fórmula nos dois lados simultaneamente.

## Validação depois de implementado

1. Broadcast: com os dois apps instalados, `adb shell am broadcast -a
   com.mupa.player.enterprise.ACTION_OPEN_SETTINGS -p com.mupa.player.enterprise` deve abrir o
   Settings do MPlayer imediatamente.
2. Senha: gerar a senha do minuto atual do lado do Argos e digitar no diálogo do MPlayer
   (hold de 3s no texto da versão) — deve abrir o Settings. Esperar a virada do minuto e
   confirmar que a senha antiga já não abre mais (exceto durante a janela de tolerância do
   minuto imediatamente anterior).
