## Mupa Player Enterprise — Instruções do Agent MDM (comandos remotos)

Este documento descreve como a plataforma Mupa deve enviar comandos para o APK **Mupa Player Enterprise** (agent MDM) e quais comandos o app aceita atualmente.

### Identidade do dispositivo (device_id)

O agent usa como chave principal (**device_id**) o identificador persistente do Android (ANDROID_ID) armazenado localmente como `persistent_device_id`.

- O mesmo `device_id` é usado para:
  - escutar comandos no Firebase Realtime
  - escrever ACK/logs em `device_logs`

### Canal de comandos (Firebase Realtime Database)

O app escuta continuamente (listener ativo) os seguintes caminhos:

- `commands/{device_id}`
- `dispositivos/{device_id}`

Compatibilidade:
- O app suporta **dois formatos de envio**:
  - **Objeto direto** (set/update no próprio `commands/{device_id}`)
  - **Fila por filhos** (push em `commands/{device_id}/{pushId}`), porque também escuta `addChildEventListener`

### Formato do comando (JSON)

Campos aceitos hoje:

```json
{
  "comando": "string",
  "timestamp": 1732000000000,
  "pacote": "string opcional",
  "codbar": "string opcional",
  "ip_server": "string opcional",
  "url": "string opcional"
}
```

- `comando` é obrigatório
- `timestamp` é fortemente recomendado (ver regra anti-repetição)

### Regra anti-repetição (igual Kodular)

O app só executa o comando se:
- `timestamp` recebido for **maior** que o último `timestamp` executado

Fallback:
- se `timestamp` vier ausente ou `0`, o app evita repetição usando um hash estável do payload.

Recomendação:
- envie `timestamp` como **epoch em milissegundos** (`Date.now()`), sempre crescente.

### ACK / Logs de execução

Após executar um comando recebido do Firebase, o app grava um ACK em:

- `device_logs/{device_id}/{push_id}`

Formato:

```json
{
  "device_id": "SEU_DEVICE_ID",
  "status": "success|error",
  "comando": "consulta_ean",
  "executado_em": 1732000000123,
  "detalhe": "mensagem opcional"
}
```

---

## Comandos suportados (plataforma → agent)

### 1) Bloquear dispositivo (Kiosk agressivo)

Path:
- `commands/{device_id}`

Payload:
```json
{
  "comando": "lock_device",
  "timestamp": 1732000000001
}
```

Efeito:
- ativa o modo kiosk agressivo (tenta LockTask quando possível + reforça fullscreen).

Observação:
- para bloquear Home/Recentes de forma “MDM total”, o device precisa estar provisionado como **Device Owner**. Sem isso, o Android limita o bloqueio.

### 2) Desbloquear dispositivo (sair do Kiosk)

```json
{
  "comando": "unlock_device",
  "timestamp": 1732000000002
}
```

### 3) Forçar fullscreen (reaplicar barras ocultas)

```json
{
  "comando": "fullscreen",
  "timestamp": 1732000000003
}
```

### 4) Abrir aplicativo Android por pacote

```json
{
  "comando": "abrir_app",
  "pacote": "com.netflix.mediaclient",
  "timestamp": 1732000000004
}
```

Efeito:
- abre o app informado pelo pacote.
- fallback: tenta `com.mupa.apptc` caso o pacote informado não seja enviado.

### 5) Consulta EAN (enviar evento para WebView)

```json
{
  "comando": "consulta_ean",
  "codbar": "7891035000140",
  "timestamp": 1732000000005
}
```

Efeito na WebView:
- dispara:
  - `window.dispatchEvent(new CustomEvent('consultaEAN', { detail: { ean: '...' } }))`
- e, se existir:
  - `window.consultarProduto('...')`

### 6) Reset do app (reload da WebView)

```json
{
  "comando": "reset_app",
  "timestamp": 1732000000006
}
```

Efeito:
- `webView.reload()`

### 7) Limpar cache da WebView

```json
{
  "comando": "clear_cache",
  "timestamp": 1732000000007
}
```

Efeito:
- `webView.clearCache(true)`
- `webView.clearHistory()`
- `webView.reload()`

### 8) Abrir URL na WebView

```json
{
  "comando": "abrir_url",
  "url": "https://midias.mupa.app/player-consulta/SEU_SERIAL",
  "timestamp": 1732000000008
}
```

Efeito:
- `webView.loadUrl(url)`

### 9) Definir IP do servidor (compatibilidade)

```json
{
  "comando": "ip_server",
  "ip_server": "192.168.1.10",
  "timestamp": 1732000000009
}
```

Efeito:
- salva `ip_server` localmente como `tcServer` nas configurações do app (persistente).

### 10) Apagar imagem por EAN (Downloads)

```json
{
  "comando": "img_delete",
  "codbar": "7891035000140",
  "timestamp": 1732000000010
}
```

Efeito:
- tenta deletar o arquivo:
  - `Download/{codbar}.png`

Observação:
- em Android mais novos, acesso a `Download/` pode depender de permissões/restrições de armazenamento do sistema.

### 11) Fechar app

```json
{
  "comando": "fecha_app",
  "timestamp": 1732000000011
}
```

Efeito:
- `finishAffinity()`

### 12) Reiniciar app

```json
{
  "comando": "reiniciar",
  "timestamp": 1732000000012
}
```

Efeito:
- reinicia o app e encerra o processo.

### 13) DEV: alternar modo dev

```json
{
  "comando": "toggle_dev",
  "timestamp": 1732000000013
}
```

Efeito:
- alterna `devMode` (on/off).

### 14) DEV: ativar modo dev (forçar ON)

```json
{
  "comando": "dev_mode",
  "timestamp": 1732000000014
}
```

Efeito:
- ativa `devMode=true` e exibe overlay DEV.

---

## API local interna (opcional)

O app expõe uma API local (localhost) para automação interna:

- Host/porta: `http://127.0.0.1:8989`
- Endpoints:
  - `GET /status`
  - `GET /device`
  - `POST /command` (envia JSON do comando)
  - `POST /lock` (equivale a `lock_device`)
  - `POST /unlock` (equivale a `unlock_device`)
  - `POST /reload` (equivale a `reset_app`)
  - `POST /kiosk` (equivale a `fullscreen` se body vazio)

