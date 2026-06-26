# API, Protocol & Command Specification (mPlayer Client)

This document details the remote commands, WebSocket/Realtime payloads, Argos integration endpoints, and local control APIs handled by the **mPlayer** Android client application.

---

## 1. Remote Command Schema (mPlayer Ingestion)

* **Listen Path**: `commands/{device_id}` and `dispositivos/{device_id}`
* **Format**: JSON Object

### Command JSON Payload Structure
```json
{
  "comando": "string",
  "timestamp": 1732000000000,
  "pacote": "string (opcional)",
  "codbar": "string (opcional)",
  "ip_server": "string (opcional)",
  "url": "string (opcional)"
}
```

### Command Reference Table

| command | Parameters | Action |
|---|---|---|
| `lock_device` | None | Enables Android LockTask kiosk mode. |
| `unlock_device` | None | Disables Kiosk mode, exposing Android OS launcher. |
| `fullscreen` | None | Re-applies fullscreen flags to hide status & navigation bars. |
| `abrir_app` | `pacote` (e.g., `"com.netflix.mediaclient"`) | Launches specific Android app by package name. |
| `consulta_ean` | `codbar` (e.g., `"7891035000140"`) | Dispatches `consultaEAN` CustomEvent into player WebView. |
| `reset_app` | None | Triggers `webView.reload()`. |
| `clear_cache` | None | Clears web cache, cookies, history, and reloads. |
| `abrir_url` | `url` (e.g., `"https://google.com"`) | Forces WebView to load specified URL. |
| `ip_server` | `ip_server` (e.g., `"192.168.1.1"`) | Sets local variable `tcServer` for companion communication. |
| `img_delete` | `codbar` (e.g., `"7891035000140"`) | Removes downloaded barcode asset `Download/{codbar}.png`. |
| `fecha_app` | None | Closes all activities using `finishAffinity()`. |
| `reiniciar` | None | Shuts down application process, initiating a clean reboot. |
| `dev_mode` | None | Activates debug overlays and opens logs. |
| `toggle_dev` | None | Alternates dev mode flag. |

---

## 2. Argos Platform Agent Protocol

Argos utilizes an HTTP poll-and-acknowledge workflow. MPlayer acts as the client, polling the Argos server API regularly.

### Command Polling (GET)
* **Endpoint**: `GET <ARGOS_BASE_URL>/devices/{device_serial}/commands/pending`
* **Headers**:
  * `Authorization: Bearer <SUPABASE_TOKEN>`
  * `Content-Type: application/json`
* **Response (200 OK)**:
  ```json
  {
    "id": "uuid-here",
    "comando": "abrir_url",
    "url": "https://mupa.app/campanha/1",
    "timestamp": 1732000000000
  }
  ```

### Command Execution ACK (POST)
* **Endpoint**: `POST <ARGOS_BASE_URL>/devices/{device_serial}/commands/ack`
* **Headers**:
  * `Authorization: Bearer <SUPABASE_TOKEN>`
  * `Content-Type: application/json`
* **Payload**:
  ```json
  {
    "command_id": "uuid-here",
    "status": "success",
    "executed_at": 1732000000123,
    "detail": "URL loaded successfully in WebView"
  }
  ```

---

## 3. Local Control API (Localhost Daemon)

MPlayer hosts an internal HTTP server (NanoHTTPD or equivalent) to accept local triggers.

* **Base URL**: `http://127.0.0.1:8989`

### Endpoints

* `GET /status` — returns active kiosk status, load configurations, and license metadata.
* `GET /device` — returns ANDROID_ID, device properties, and cached serial number.
* `POST /command` — processes a raw remote command JSON in the request body.
* `POST /lock` — triggers immediate `lock_device` lock sequence.
* `POST /unlock` — triggers immediate `unlock_device` unlock sequence.
* `POST /reload` — reloads the active WebView window.
* `POST /kiosk` — reapplies system fullscreen overlays.
