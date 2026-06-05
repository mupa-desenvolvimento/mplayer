# Argos Web → Agent Argos (Firebase Realtime) — Protocolo Completo

Este documento define, de ponta a ponta, como a plataforma **Argos Web** deve escrever dados no **Firebase Realtime Database** para que o **Agent Argos** (Android, pacote `com.mupa.agent.argos`) execute ações no dispositivo e devolva status/result.

## 1) Banco Realtime: base `m_argos`

No Firebase Realtime Database, crie (ou use) a raiz:

```text
m_argos
```

Tudo do Argos fica dentro dessa chave.

### Estrutura recomendada

```text
m_argos/
  devices/
    {device_id}/
      id_device: string
      updated_at: number (ms)
      device_config: object
      commands: object
      command_result: object
  commands/
    {device_id}/
      (mesmo payload aceito em devices/{device_id})
  dispositivos/
    {device_id}/
      (formato legado Kodular)
```

O Agent escuta (listener + polling):
- `m_argos/devices/{device_id}`
- `m_argos/commands/{device_id}`
- `m_argos/dispositivos/{device_id}` (legado)

## 2) Identidade do dispositivo (`device_id`)

O Agent calcula um **ID canônico** persistente local (`persistent_device_id`) e também aceita IDs alternativos:
- Serial de hardware (quando disponível)
- ANDROID_ID

Regra prática para o backend/web:
- Use como chave do nó o mesmo `device_id` que você cadastrou/armazenou para aquele dispositivo.
- Sempre envie também `id_device` dentro do payload.

## 3) Regras para “funcionar sempre” (anti-repetição)

Para o Agent executar um comando com consistência:
- `commands.executed` deve estar `false`
- `commands.command_id` deve ser **novo** a cada envio (único por comando)
- `updated_at` deve ser **novo** (epoch ms), preferencialmente igual ao timestamp do envio

Se você repetir `command_id` ou repetir `timestamp`, o Agent pode deduplicar e ignorar.

Regras específicas:
- `reboot_device` tem cooldown (~120s) para evitar loop de reboot.

## 4) Payload “novo” (recomendado): `devices/{device_id}`

Escreva em:

```text
m_argos/devices/{device_id}
```

### Modelo base

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000000000,
  "device_config": {},
  "commands": {
    "command_id": "cmd-1760000000000-001",
    "command": "open_app",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

Campos:
- `id_device` (string): identificador esperado do dispositivo.
- `updated_at` (number): epoch em ms.
- `device_config` (object): políticas do device (opcional).
- `commands` (object): comando “single slot” executável.
  - `command_id` (string): id único (use timestamp + sufixo).
  - `command` (string): nome do comando.
  - `executed` (boolean): deve iniciar como `false`.
  - `retry` (number): contador (a plataforma pode usar para controlar reenvios).
  - `params` (object): parâmetros do comando.

## 5) Retorno do Agent (ACK / resultado)

Após processar, o Agent escreve de volta no mesmo nó, em:

```text
command_result/
  command_id
  status
  executed_at
  message
```

E também atualiza:
- `commands/executed` → `true` em caso de sucesso
- `commands/retry` → incrementa em caso de falha

Exemplo de retorno:

```json
{
  "command_result": {
    "command_id": "cmd-1760000000000-001",
    "status": "success",
    "executed_at": 1760000000123,
    "message": "opened"
  },
  "commands": {
    "executed": true
  }
}
```

## 6) Comandos suportados (implementados hoje no Agent)

### 6.1 `open_app`

Abre um app por packageName.

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001000,
  "commands": {
    "command_id": "cmd-1760000001000-open",
    "command": "open_app",
    "executed": false,
    "retry": 0,
    "params": { "package": "com.mupa.player.enterprise" }
  }
}
```

### 6.2 `close_app`

Solicita “fechar” via políticas (exige Device Owner para suspender).

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001001,
  "commands": {
    "command_id": "cmd-1760000001001-close",
    "command": "close_app",
    "executed": false,
    "retry": 0,
    "params": { "package": "com.mupa.player.enterprise" }
  }
}
```

### 6.3 `reboot_device` (exige Device Owner)

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001002,
  "commands": {
    "command_id": "cmd-1760000001002-reboot",
    "command": "reboot_device",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

### 6.4 `kiosk_on` / `locktask_on`

O Agent só executa se o dispositivo já estiver “finalizado” (ex.: `device_locked=true` ou `kiosk_enabled=true` em `device_config`).

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001003,
  "commands": {
    "command_id": "cmd-1760000001003-kiosk-on",
    "command": "kiosk_on",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

### 6.5 `kiosk_off` / `locktask_off`

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001004,
  "commands": {
    "command_id": "cmd-1760000001004-kiosk-off",
    "command": "kiosk_off",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

### 6.6 `set_whitelist` / `set_allowed_apps`

Define apps permitidos no kiosk.

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001005,
  "commands": {
    "command_id": "cmd-1760000001005-whitelist",
    "command": "set_whitelist",
    "executed": false,
    "retry": 0,
    "params": {
      "whitelist": [
        "com.mupa.player.enterprise",
        "com.anydesk.anydeskandroid",
        "com.android.settings"
      ]
    }
  }
}
```

### 6.7 `open_settings`

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001006,
  "commands": {
    "command_id": "cmd-1760000001006-settings",
    "command": "open_settings",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

### 6.8 `volume` (0–100)

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001007,
  "commands": {
    "command_id": "cmd-1760000001007-volume",
    "command": "volume",
    "executed": false,
    "retry": 0,
    "params": { "value": 80 }
  }
}
```

### 6.9 `brightness` (0–100, exige Device Owner)

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000001008,
  "commands": {
    "command_id": "cmd-1760000001008-brightness",
    "command": "brightness",
    "executed": false,
    "retry": 0,
    "params": { "value": 100 }
  }
}
```

## 7) `device_config` (políticas do dispositivo)

Envie dentro de:

```text
m_argos/devices/{device_id}/device_config
```

Chaves relevantes suportadas hoje:
- `kiosk_enabled` (boolean)
- `device_locked` (boolean)
- `lock_task` (boolean)
- `launcher_argos` (boolean)
- `launcher_default` (boolean)
- `whitelist` (array de packages)
- `app_autostart` (array)
- `app_persist` (array)
- `favorites` (array)
- `pinned` (array)
- `anydesk_url` (string)
- `anydesk_plugins` (array) aceita:
  - string (url)
  - objeto `{ "package": "...", "url": "..." }`
- `mplayer_url` (string)

Exemplo completo (finalizar + provisioning):

```json
{
  "id_device": "SEU_DEVICE_ID",
  "updated_at": 1760000002000,
  "device_config": {
    "kiosk_enabled": true,
    "device_locked": true,
    "lock_task": true,
    "launcher_argos": true,
    "launcher_default": true,
    "whitelist": [
      "com.mupa.player.enterprise",
      "com.anydesk.anydeskandroid",
      "com.anydesk.adcontrol.ad1",
      "com.android.settings"
    ],
    "app_autostart": ["com.mupa.player.enterprise"],
    "anydesk_url": "https://download.anydesk.com/anydesk.apk",
    "anydesk_plugins": [
      { "package": "com.anydesk.adcontrol.ad1", "url": "https://seu-servidor/adcontrol_ad1.apk" }
    ],
    "mplayer_url": "https://seu-servidor/mplayer_enterprise.apk"
  }
}
```

## 8) Formato legado (Kodular): `dispositivos/{device_id}`

Escreva em:

```text
m_argos/dispositivos/{device_id}
```

Modelo:

```json
{
  "id_device": "SEU_DEVICE_ID",
  "comando": "reboot_device",
  "timestamp": 1760000003000,
  "executado": false
}
```

Regras:
- Mude sempre `timestamp` para disparar execução.
- O Agent escreve ack no mesmo nó:
  - `executado`, `executed_at`, `status`, `message`, `command_result/*`

## 9) Troubleshooting (quando “enviei e não aconteceu nada”)

Checklist:
- Você escreveu em `m_argos/...` (não no root antigo).
- O `device_id` usado no path é o mesmo que o Agent está aceitando.
- `commands.executed=false`.
- `command_id` e `updated_at` mudaram (novos).
- Para reboot/brilho/fechar app: Device Owner precisa estar ativo; caso contrário, o Agent deve responder com `failed` (se não respondeu nada, ele nem leu).

## 10) Exemplo pronto (reboot do device 778e4d4fa2528b15)

Path:

```text
m_argos/devices/778e4d4fa2528b15
```

JSON:

```json
{
  "id_device": "778e4d4fa2528b15",
  "updated_at": 1760000004000,
  "commands": {
    "command_id": "cmd-1760000004000-reboot",
    "command": "reboot_device",
    "executed": false,
    "retry": 0,
    "params": {}
  }
}
```

