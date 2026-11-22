# Ecovacs GOAT A3000 Mower for Hubitat Elevation

A standalone Hubitat Elevation driver that connects directly to a local **Home Assistant** instance over WebSocket (`subscribe_entities`) to monitor and control an **Ecovacs GOAT A3000** robotic lawnmower in real time—with zero polling overhead.

---

## Architecture

Home Assistant handles Ecovacs cloud authentication and MQTT communication via its official `ecovacs` integration, while Hubitat maintains a persistent local LAN WebSocket connection for instant state updates and command dispatch.

```mermaid
%%{init: {'flowchart': {'nodeSpacing': 50, 'rankSpacing': 75}}}%%
flowchart TB
    HE["Hubitat Elevation<br/>Ecovacs GOAT A3000 Mower WS Driver"]
    HA["Home Assistant (Local LAN)<br/>WebSocket API /api/websocket"]
    ECO["Ecovacs Integration<br/>deebot-client (REST + MQTT)"]
    MOWER["Ecovacs GOAT A3000<br/>Robotic Lawnmower"]

    HE <-->|"Local WebSocket<br/>subscribe_entities & call_service"| HA
    HA <-->|"Entity State & Services"| ECO
    ECO <-->|"Cloud API / MQTT"| MOWER
```

---

## Features

* **Real-Time Push Updates:** Uses Home Assistant's `subscribe_entities` WebSocket API to receive only deltas for your configured mower entities.
* **Resilient Connection:** Built-in `Initialize` capability for hub reboots, 30-second WebSocket keepalive pings, and exponential backoff reconnection.
* **Core Controls:** `startMowing`, `pause`, `stop`, `dock`, `setCutDirection`, and standard `Switch` (`on` = start mowing, `off` = return to dock) for Rule Machine and Dashboards.
* **Extended Sensors & Diagnostics:** Optional real-time tracking for Battery, Error Code/Description (`hasError`), Rain Sensor (`rainStatus`), Blade Lifespan (`bladeLife`), Area Mowed, Mowing Duration, Wi-Fi RSSI, and Last Job Stop Reason.
* **Per-Zone Mowing (3 Modes):** Trigger specific lawn zones via Home Assistant Scripts, a `select.*` entity, or a custom HA service call.
* **Auto-Shutoff Debug Logging:** Debug logs automatically disable after 30 minutes to keep Hubitat logs clean.

---

## Prerequisites

1. An Ecovacs GOAT A3000 configured in Home Assistant using the [Ecovacs integration](https://www.home-assistant.io/integrations/ecovacs).
2. A Home Assistant **Long-Lived Access Token** (HA Profile → **Security** → **Long-Lived Access Tokens** → **Create Token**).
3. *(Optional)* In Home Assistant under **Settings → Devices & services → Ecovacs → [Your Mower]**, enable any diagnostic entities that are disabled by default (such as **Error**, **Cut direction**, or **Wi-Fi RSSI**).

---

## Installation

### Option A: Hubitat Package Manager (Recommended)
1. Open **Hubitat Package Manager (HPM)** in Hubitat.
2. Select **Install → From a URL**.
3. Paste the manifest URL:
   ```text
   [https://raw.githubusercontent.com/kurtsanders/Hubitat-Ecovacs-GOAT-Mower/main/packageManifest.json](https://raw.githubusercontent.com/kurtsanders/Hubitat-Ecovacs-GOAT-Mower/main/packageManifest.json)
   ```
4. Follow the prompts to complete installation.

### Option B: Manual Installation
1. In Hubitat, go to **Drivers Code → + New Driver**.
2. Click **Import**, paste the raw Groovy URL below, and click **Import** → **Save**:
   ```text
   [https://raw.githubusercontent.com/kurtsanders/Hubitat-Ecovacs-GOAT-Mower/main/drivers/Ecovacs_GOAT_A3000_Mower_WS.groovy](https://raw.githubusercontent.com/kurtsanders/Hubitat-Ecovacs-GOAT-Mower/main/drivers/Ecovacs_GOAT_A3000_Mower_WS.groovy)
   ```

---

## Device Setup

1. Go to **Devices → Add Device → Virtual**.
2. Enter a device name (e.g., `Ecovacs GOAT A3000`) and set **Type** to **Ecovacs GOAT A3000 Mower WS**.
3. Click **Save Device**, then configure the **Preferences**:
   * **Home Assistant IP / Port / Token:** Your local HA server details.
   * **Lawn Mower Entity ID:** Required (e.g., `lawn_mower.billie`).
   * **Optional Entity IDs:** Add any additional HA entities you want mirrored (battery, error, rain sensor, blade lifespan, area mowed, duration, RSSI, cut direction, last job).
4. Click **Save Preferences**. `connectionStatus` should immediately transition to `connected` and populate **Current States**.

---

## Commands & Attributes

### Commands
| Command | Parameters | Description |
| :--- | :--- | :--- |
| `startMowing` / `on` | None | Calls `lawn_mower.start_mowing` |
| `dock` / `off` | None | Calls `lawn_mower.dock` |
| `pause` | None | Calls `lawn_mower.pause` |
| `stop` | None | Calls `lawn_mower.stop` |
| `mowZone` | `zoneName` (String) | Dispatches mapped zone via configured Zone Mode |
| `setCutDirection` | `degrees` (0–180) | Sets HA `number.*` cut direction entity |
| `refresh` | None | Re-subscribes to pull a fresh state snapshot |
| `connect` / `disconnect` | None | Manually open or close the HA WebSocket |

### Key Attributes
| Attribute | Values / Unit | Notes |
| :--- | :--- | :--- |
| `mowerState` | `mowing`, `docked`, `paused`, `returning`, `error` | Mirrors HA `lawn_mower` state |
| `switch` | `on` / `off` | `on` when actively mowing, `off` otherwise |
| `battery` | `%` | From battery sensor or mower attribute |
| `hasError` | `true` / `false` | Rule Machine trigger flag for non-zero errors |
| `errorCode` / `errorDescription` | String | Numeric code and human-readable HA description |
| `rainStatus` | `wet` / `dry` / `unknown` | Normalized rain sensor state (does not trigger HSM leak alerts) |
| `bladeLife` | `%` | Remaining blade lifespan |
| `areaMowed` / `mowingDuration` | Number | Uses unit reported by Home Assistant |
| `rssi` | `dBm` | Mower Wi-Fi signal strength |
| `lastJobReason` | String | Stop reason from HA `event.*` entity |
| `connectionStatus` | `connected`, `connecting`, `disconnected`, `auth_failed` | WebSocket link status |

---

## Per-Zone Mowing Configuration

Choose one of three modes in **Preferences**:

1. **HA Script Mode (Default):** Map friendly zone names to Home Assistant scripts in **Zone Map**, e.g.:
   ```text
   Front=script.mow_front_yard, Back=script.mow_back_yard
   ```
2. **HA Select Entity + Start Mowing:** Specify your HA `select.*` entity and map zone names to option labels:
   ```text
   Front=Front Yard, Back=Back Yard
   ```
3. **Custom HA Service Call:** Specify any HA `domain.service` and parameter key (e.g., `zone_id`), and map zone names to IDs.

In **Rule Machine**, invoke zone mowing via **Run Custom Action → Actuator → `mowZone`** with a string parameter (e.g., `Front`).

---

## License

Apache License 2.0 — Copyright © 2026 Kurt Sanders (SanderSoft).