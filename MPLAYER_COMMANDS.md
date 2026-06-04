# MPlayer — Lista de Comandos

Este arquivo é a fonte de verdade dos comandos suportados pelo **MPlayer** (aplicativo no dispositivo).

Regra do projeto: sempre que um novo comando for adicionado/alterado no MPlayer, este arquivo deve ser atualizado junto.

## 1) Canal principal (Argos → API → MPlayer)

O Argos é o canal principal. O MPlayer busca comandos pendentes e envia o resultado para a API.

### 1.0 Exemplos completos (recomendado copiar/colar)

#### Exemplo de resposta do endpoint de pendências (array direto)

```json
[
  {
    "commandId": "cmd-1710000000000-001",
    "command": "KIOSK_ON",
    "priority": 10,
    "timestamp": 1710000000000,
    "params": {}
  },
  {
    "commandId": "cmd-1710000000000-002",
    "command": "SET_WHITELIST",
    "priority": 5,
    "timestamp": 1710000000001,
    "params": {
      "packages": [
        "com.mupa.player.enterprise",
        "com.android.settings",
        "com.anydesk.anydeskandroid"
      ]
    }
  }
]
```

#### Exemplo de resposta do endpoint de pendências (envelope)

```json
{
  "commands": [
    {
      "commandId": "cmd-1710000000000-003",
      "command": "AUTOSTART_SET",
      "priority": 5,
      "timestamp": 1710000000002,
      "params": { "package": "com.mupa.player.enterprise" }
    }
  ]
}
```

#### Exemplo de ACK para o endpoint de resultado

```json
{
  "commandId": "cmd-1710000000000-002",
  "status": "success",
  "message": "allowed_updated",
  "executedAt": 1710000000555
}
```

### 1.1 Endpoint de pull (pendências)

`GET /api/device/{deviceId}/pending-commands`

O MPlayer aceita resposta em:
- Array direto: `[{...}, {...}]`
- Envelope: `{ "commands": [ ... ] }` ou `{ "data": [ ... ] }`

Formato esperado por item:

```json
{
  "commandId": "123",
  "command": "KIOSK_ON",
  "priority": 10,
  "timestamp": 1710000000000,
  "params": {}
}
```

### 1.2 Endpoint de ACK (resultado)

`POST /api/device/{deviceId}/command-result`

Body:

```json
{
  "commandId": "123",
  "status": "success|failed|timeout|processing|pending",
  "message": "Executado",
  "executedAt": 1710000000000
}
```

### 1.3 Status (ACK)

Estados usados pelo MPlayer na fila local:
- `pending`
- `processing`
- `success`
- `failed`
- `timeout`

### 1.4 Comandos Argos (MVP)

| Command | Params | Efeito |
|---|---|---|
| `KIOSK_ON` / `LOCK_TASK_ON` / `LOCK_DEVICE` | `{}` | Ativa kiosk/lock-task e aplica políticas (se DO). |
| `KIOSK_OFF` / `LOCK_TASK_OFF` / `UNLOCK_DEVICE` | `{}` | Desativa kiosk/lock-task e restaura políticas (se DO). |
| `SET_ALLOWED_APPS` / `SET_KIOSK_APPS` / `SET_WHITELIST` | `{ "packages": ["com.mupa.player.enterprise", "com.android.settings"] }` | Atualiza whitelist/allowed packages (sempre mantém o próprio MPlayer). |
| `AUTOSTART_SET` / `SET_AUTOSTART` | `{ "package": "com.anydesk.anydeskandroid" }` | Define o app que deve ser reaberto automaticamente quando o task for removido (persistência). |
| `AUTOSTART_CLEAR` / `CLEAR_AUTOSTART` | `{}` | Limpa o app de autostart/persist. |
| `REBOOT_DEVICE` / `REINICIAR_DISPOSITIVO` | `{}` | Reinicia o dispositivo (requer Device Owner). |
| `UPDATE_APP` | `{ "package": "...", "apkUrl": "...", "sha256": "..." }` | Ainda não implementado no MPlayer (planejado: PackageInstaller/DO). |

Observação: `command` é case-insensitive no executor (normaliza `uppercase()`).

### 1.5 Exemplos por comando (Argos → API → MPlayer)

#### `KIOSK_ON` / `LOCK_TASK_ON` / `LOCK_DEVICE`

```json
{
  "commandId": "cmd-1710000000100-kiosk-on",
  "command": "KIOSK_ON",
  "priority": 10,
  "timestamp": 1710000000100,
  "params": {}
}
```

#### `KIOSK_OFF` / `LOCK_TASK_OFF` / `UNLOCK_DEVICE`

```json
{
  "commandId": "cmd-1710000000101-kiosk-off",
  "command": "KIOSK_OFF",
  "priority": 10,
  "timestamp": 1710000000101,
  "params": {}
}
```

#### `SET_ALLOWED_APPS` / `SET_KIOSK_APPS` / `SET_WHITELIST`

```json
{
  "commandId": "cmd-1710000000102-whitelist",
  "command": "SET_WHITELIST",
  "priority": 5,
  "timestamp": 1710000000102,
  "params": {
    "packages": [
      "com.mupa.player.enterprise",
      "com.android.settings",
      "com.anydesk.anydeskandroid"
    ]
  }
}
```

#### `AUTOSTART_SET` / `SET_AUTOSTART`

```json
{
  "commandId": "cmd-1710000000103-autostart",
  "command": "AUTOSTART_SET",
  "priority": 5,
  "timestamp": 1710000000103,
  "params": { "package": "com.mupa.player.enterprise" }
}
```

#### `AUTOSTART_CLEAR` / `CLEAR_AUTOSTART`

```json
{
  "commandId": "cmd-1710000000104-autostart-clear",
  "command": "AUTOSTART_CLEAR",
  "priority": 5,
  "timestamp": 1710000000104,
  "params": {}
}
```

#### `REBOOT_DEVICE` / `REINICIAR_DISPOSITIVO`

```json
{
  "commandId": "cmd-1710000000105-reboot",
  "command": "REBOOT_DEVICE",
  "priority": 10,
  "timestamp": 1710000000105,
  "params": {}
}
```

#### `UPDATE_APP` (planejado / ainda não implementado no MPlayer)

```json
{
  "commandId": "cmd-1710000000106-update",
  "command": "UPDATE_APP",
  "priority": 5,
  "timestamp": 1710000000106,
  "params": {
    "package": "com.mupa.player.enterprise",
    "apkUrl": "https://seu-servidor/mplayer_enterprise.apk",
    "sha256": "HEX_64_CHARS_OPCIONAL"
  }
}
```

## 2) Canal secundário (Firebase RTDB — wake-up)

Firebase não é mais canal principal de execução. É usado como notificação para “acordar” o device e disparar pull na API.

Paths legados:
- `commands/{deviceId}`
- `dispositivos/{deviceId}` (compat)

Quando chega qualquer mudança, o MPlayer dispara um sync imediato do Argos.

### 2.1 Exemplos (wake-up)

O MPlayer não usa esse payload como “fonte de verdade” de execução. O objetivo é só acordar e forçar sync.

Exemplo (qualquer valor serve, desde que mude):

```json
{
  "wake": true,
  "updated_at": 1710000000200
}
```

## 3) Comandos legados (execução local no PlayerActivity)

Estes comandos existem por compatibilidade e execução local (via WebView bridge, API local, ou mecanismos internos).

Formato legado (exemplo):

```json
{
  "comando": "abrir_url",
  "url": "https://midias.mupa.app/player-consulta/SEU_ID",
  "timestamp": 1710000000000
}
```

### 3.1 Lista

| comando | Campos usados | Efeito |
|---|---|---|
| `abrir_app` | `pacote` (opcional) | Abre um app via launch intent. |
| `consulta_ean` | `codbar` (obrigatório) | Dispara evento JS `consultaEAN` e chama `window.consultarProduto(ean)` se existir. |
| `scan_barcode` / `scan_code` | (n/a) | Mostra aviso: leitura é via teclado (wedge). |
| `reset_app` | (n/a) | Recarrega WebView. |
| `img_delete` | `codbar` (obrigatório) | Remove `Downloads/{codbar}.png`. |
| `ip_server` | `ip_server` (obrigatório) | Salva o IP/host do TC server (config). |
| `fecha_app` | (n/a) | Fecha o app (finishAffinity). |
| `lock_device` | (n/a) | Ativa kiosk/lock-task. |
| `unlock_device` | (n/a) | Desativa kiosk/lock-task. |
| `reiniciar_dispositivo` / `reboot_device` | (n/a) | Reboot (requer Device Owner). |
| `reiniciar` | (n/a) | Reinicia o app (restartApp). |
| `clear_cache` | (n/a) | Limpa cache/histórico da WebView e recarrega. |
| `abrir_url` | `url` (obrigatório) | Abre URL no WebView (bloqueia `/setup`). |
| `toggle_dev` | (n/a) | Alterna dev mode. |
| `dev_mode` | (n/a) | Ativa dev mode e mostra overlay. |
| `fullscreen` | (n/a) | Oculta system bars. |
| `record_screen_30s` | (n/a) | Captura tela (timelapse/screenrecord) por ~30s. |

### 3.2 Exemplos prontos (legado)

#### `abrir_url`

```json
{
  "comando": "abrir_url",
  "url": "https://midias.mupa.app/player-consulta/SEU_ID",
  "timestamp": 1710000000300
}
```

#### `consulta_ean`

```json
{
  "comando": "consulta_ean",
  "codbar": "7891035000140",
  "timestamp": 1710000000301
}
```

#### `reset_app`

```json
{
  "comando": "reset_app",
  "timestamp": 1710000000302
}
```

#### `clear_cache`

```json
{
  "comando": "clear_cache",
  "timestamp": 1710000000303
}
```

#### `lock_device` / `unlock_device`

```json
{
  "comando": "lock_device",
  "timestamp": 1710000000304
}
```

```json
{
  "comando": "unlock_device",
  "timestamp": 1710000000305
}
```

#### `reboot_device`

```json
{
  "comando": "reboot_device",
  "timestamp": 1710000000306
}
```

## 4) API Local (127.0.0.1:8989)

Esses endpoints chamam o “CommandCenter” interno para executar comandos legados.

- `POST /lock` → `lock_device`
- `POST /unlock` → `unlock_device`
- `POST /reload` → `reset_app`
- `POST /command` → body livre (JSON legado)

### 4.1 Exemplos (curl)

```bash
curl -X POST http://127.0.0.1:8989/lock
```

```bash
curl -X POST http://127.0.0.1:8989/unlock
```

```bash
curl -X POST http://127.0.0.1:8989/reload
```

```bash
curl -X POST http://127.0.0.1:8989/command ^
  -H "Content-Type: application/json" ^
  -d "{\"comando\":\"consulta_ean\",\"codbar\":\"7891035000140\",\"timestamp\":1710000000400}"
```
