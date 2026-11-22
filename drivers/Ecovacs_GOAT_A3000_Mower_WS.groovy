/**
 *  Ecovacs GOAT A3000 Mower WS
 *
 *  Copyright 2026 Kurt Sanders (SanderSoft)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *  v1.0.0 - 2026-11-22 - Initial public release: Home Assistant WebSocket (subscribe_entities),
 *                        real-time state push, extended diagnostic/maintenance sensors, and per-zone mowing.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(
        name: "Ecovacs GOAT A3000 Mower WS",
        namespace: "kurtsanders",
        author: "Kurt Sanders",
        importUrl: "https://raw.githubusercontent.com/kurtsanders/Hubitat-Ecovacs-GOAT-Mower/main/drivers/Ecovacs_GOAT_A3000_Mower_WS.groovy"
    ) {
        capability "Initialize"
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Battery"
        capability "Refresh"

        command "startMowing"
        command "pause"
        command "stop"
        command "dock"
        command "mowZone", [[name: "zoneName*", type: "STRING", description: "Zone name from Zone Map (or raw entity/zone ID)"]]
        command "setCutDirection", [[name: "degrees*", type: "NUMBER", description: "Cutting angle 0 to 180"]]
        command "connect"
        command "disconnect"

        // Core & Connection Attributes
        attribute "mowerState", "string"
        attribute "connectionStatus", "string"
        attribute "activeZone", "string"
        attribute "cutDirection", "number"
        attribute "lastUpdated", "string"

        // Extended Ecovacs Sensor Attributes
        attribute "hasError", "enum", ["true", "false"]
        attribute "errorCode", "string"
        attribute "errorDescription", "string"
        attribute "rainStatus", "enum", ["wet", "dry", "unknown"]
        attribute "bladeLife", "number"
        attribute "areaMowed", "number"
        attribute "mowingDuration", "number"
        attribute "rssi", "number"
        attribute "lastJobReason", "string"
    }

    preferences {
        // Home Assistant Connection
        input name: "haHost", type: "text", title: "Home Assistant IP or Hostname (e.g., 10.0.0.87)", required: true
        input name: "haPort", type: "number", title: "Home Assistant Port", defaultValue: 8123, required: true
        input name: "useTls", type: "bool", title: "Use Secure WebSocket (wss://)", defaultValue: false
        input name: "haToken", type: "password", title: "HA Long-Lived Access Token", required: true

        // Core Entities
        input name: "mowerEntityId", type: "text", title: "Lawn Mower Entity ID (e.g., lawn_mower.billie)", required: true
        input name: "batteryEntityId", type: "text", title: "Battery Sensor Entity ID (optional, e.g., sensor.billie_battery)", required: false
        input name: "cutDirEntityId", type: "text", title: "Cut Direction Number Entity ID (optional, e.g., number.billie_cut_direction)", required: false

        // Extended Entities (all optional)
        input name: "errorEntityId", type: "text", title: "Error Sensor Entity ID (optional, e.g., sensor.billie_error)", required: false
        input name: "rainEntityId", type: "text", title: "Rain Sensor Entity ID (optional, e.g., binary_sensor.billie_rain_sensor)", required: false
        input name: "bladeEntityId", type: "text", title: "Blade Lifespan Sensor Entity ID (optional, e.g., sensor.billie_blade_lifespan)", required: false
        input name: "areaEntityId", type: "text", title: "Area Mowed Sensor Entity ID (optional, e.g., sensor.billie_area_mowed)", required: false
        input name: "durationEntityId", type: "text", title: "Mowing Duration Sensor Entity ID (optional, e.g., sensor.billie_mowing_duration)", required: false
        input name: "rssiEntityId", type: "text", title: "Wi-Fi RSSI Sensor Entity ID (optional, e.g., sensor.billie_wi_fi_rssi)", required: false
        input name: "lastJobEntityId", type: "text", title: "Last Job Event Entity ID (optional, e.g., event.billie_last_job)", required: false

        // Per-Zone Mowing
        input name: "zoneMode", type: "enum", title: "Per-Zone Mowing Mode", options: [
            "script": "HA Script (Map: ZoneName=script.entity_id)",
            "select": "HA Select Entity + Start Mowing (Map: ZoneName=OptionName)",
            "service": "Custom HA Service Call (Map: ZoneName=zone_id)"
        ], defaultValue: "script", required: true
        input name: "zoneSelectEntityId", type: "text", title: "[Select Mode] HA Select Entity ID (e.g., select.billie_zone)", required: false
        input name: "customZoneService", type: "text", title: "[Custom Service Mode] HA Service (e.g., ecovacs.mow_zone)", required: false
        input name: "customZoneParamKey", type: "text", title: "[Custom Service Mode] Zone Parameter Key (e.g., zone_id or area)", defaultValue: "zone_id", required: false
        input name: "zoneMap", type: "text", title: "Zone Map (comma-separated, e.g., Front=script.mow_front, Back=script.mow_back)", required: false

        // Logging
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging (auto-off in 30 min)", defaultValue: false
    }
}

def installed() {
    log.info "${device.displayName} installed"
    state.wsId = 1
    state.reconnectDelay = 2
}

def updated() {
    log.info "${device.displayName} updated"
    unschedule()
    if (debugLogging) {
        runIn(1800, "logsOff")
    }
    initialize()
}

def uninstalled() {
    disconnect()
}

def logsOff() {
    log.warn "${device.displayName}: Debug logging automatically disabled"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

def initialize() {
    unschedule("reconnectWebSocket")
    state.intentionalClose = false
    state.reconnectDelay = 2
    connect()
}

def connect() {
    if (!haHost || !haToken || !mowerEntityId) {
        log.warn "${device.displayName}: Configure HA Host, Token, and Mower Entity ID first."
        sendEvent(name: "connectionStatus", value: "unconfigured")
        return
    }

    try {
        interfaces.webSocket.close()
    } catch (Exception ignored) {}

    String scheme = useTls ? "wss" : "ws"
    String url = "${scheme}://${haHost.trim()}:${haPort ?: 8123}/api/websocket"
    logDebug "Connecting to Home Assistant WebSocket: ${url}"
    sendEvent(name: "connectionStatus", value: "connecting")

    try {
        interfaces.webSocket.connect([pingInterval: 30, ignoreSSLIssues: true], url)
    } catch (Exception e) {
        log.error "${device.displayName}: WebSocket connect failed: ${e.message}"
        sendEvent(name: "connectionStatus", value: "error")
        scheduleReconnect()
    }
}

def disconnect() {
    state.intentionalClose = true
    unschedule("reconnectWebSocket")
    try {
        interfaces.webSocket.close()
    } catch (Exception ignored) {}
    sendEvent(name: "connectionStatus", value: "disconnected")
    logInfo "Disconnected from Home Assistant WebSocket"
}

def webSocketStatus(String status) {
    logDebug "webSocketStatus: ${status}"
    if (status?.startsWith("status: open")) {
        state.reconnectDelay = 2
        sendEvent(name: "connectionStatus", value: "authenticating")
    } else if (status?.startsWith("status: closing") || status?.startsWith("failure:")) {
        sendEvent(name: "connectionStatus", value: "disconnected")
        if (!state.intentionalClose) {
            log.warn "${device.displayName}: WebSocket dropped (${status}); scheduling reconnect"
            scheduleReconnect()
        }
    }
}

private void scheduleReconnect() {
    Integer delay = (state.reconnectDelay ?: 2) as Integer
    runIn(delay, "reconnectWebSocket")
    state.reconnectDelay = Math.min(delay * 2, 300)
}

def reconnectWebSocket() {
    if (!state.intentionalClose) {
        connect()
    }
}

// Inbound Home Assistant WebSocket messages
def parse(String description) {
    logDebug "WS RX: ${description}"
    def msg
    try {
        msg = new JsonSlurper().parseText(description)
    } catch (Exception e) {
        log.error "${device.displayName}: Failed to parse WS JSON: ${e.message}"
        return
    }

    switch (msg?.type) {
        case "auth_required":
            sendWsMessage([type: "auth", access_token: haToken])
            break

        case "auth_ok":
            sendEvent(name: "connectionStatus", value: "connected")
            logInfo "Connected and authenticated to Home Assistant"
            subscribeToEntities()
            break

        case "auth_invalid":
            log.error "${device.displayName}: Home Assistant authentication failed: ${msg.message}"
            sendEvent(name: "connectionStatus", value: "auth_failed")
            state.intentionalClose = true
            interfaces.webSocket.close()
            break

        case "event":
            handleEntityEvent(msg.event)
            break

        case "result":
            if (msg.success == false) {
                log.error "${device.displayName}: HA command (id=${msg.id}) failed: ${msg.error?.message ?: msg.error}"
            } else {
                logDebug "HA command (id=${msg.id}) succeeded"
            }
            break
    }
}

private List<String> configuredEntities() {
    List<String> raw = [
        mowerEntityId,
        batteryEntityId,
        cutDirEntityId,
        errorEntityId,
        rainEntityId,
        bladeEntityId,
        areaEntityId,
        durationEntityId,
        rssiEntityId,
        lastJobEntityId
    ]
    return raw.collect { it?.trim() }.findAll { it }.unique()
}

private void subscribeToEntities() {
    List<String> entities = configuredEntities()
    Integer subId = nextWsId()
    state.entitySubId = subId
    sendWsMessage([
        id: subId,
        type: "subscribe_entities",
        entity_ids: entities
    ])
    logDebug "Subscribed to HA entities: ${entities}"
}

private void handleEntityEvent(Map ev) {
    if (!ev) return

    // Initial snapshot ("a" = added)
    if (ev.a instanceof Map) {
        ev.a.each { String entityId, Map data ->
            processEntityUpdate(entityId, data?.s?.toString(), (data?.a instanceof Map) ? data.a : [:])
        }
    }

    // Incremental delta ("c" = changed, "+" = added/modified fields)
    if (ev.c instanceof Map) {
        ev.c.each { String entityId, Map diff ->
            Map plus = (diff?."+" instanceof Map) ? diff."+" : null
            if (plus) {
                String newState = plus.containsKey("s") ? plus.s?.toString() : null
                Map newAttrs = (plus.a instanceof Map) ? plus.a : [:]
                processEntityUpdate(entityId, newState, newAttrs)
            }
        }
    }
}

private void processEntityUpdate(String entityId, String newState, Map attrs) {
    boolean updatedAny = false

    if (matchesEntity(entityId, mowerEntityId)) {
        if (newState != null) {
            String normalized = newState.toLowerCase()
            if (device.currentValue("mowerState") != normalized) {
                sendEvent(name: "mowerState", value: normalized, descriptionText: "${device.displayName} is ${normalized}")
                logInfo "Mower state is ${normalized}"
            }

            String switchVal = (normalized == "mowing") ? "on" : "off"
            if (device.currentValue("switch") != switchVal) {
                sendEvent(name: "switch", value: switchVal, descriptionText: "${device.displayName} switch is ${switchVal}")
            }

            if (normalized in ["docked", "idle", "error"] && device.currentValue("activeZone") != "none") {
                sendEvent(name: "activeZone", value: "none")
            }
            updatedAny = true
        }
        if (attrs?.battery_level != null && attrs.battery_level.toString().isNumber()) {
            updateBattery(attrs.battery_level.toString().toBigDecimal().toInteger())
            updatedAny = true
        }

    } else if (matchesEntity(entityId, batteryEntityId)) {
        if (isValidNumeric(newState)) {
            updateBattery(newState.toBigDecimal().toInteger())
            updatedAny = true
        }

    } else if (matchesEntity(entityId, cutDirEntityId)) {
        if (isValidNumeric(newState)) {
            Integer deg = newState.toBigDecimal().toInteger()
            if (device.currentValue("cutDirection") != deg) {
                sendEvent(name: "cutDirection", value: deg, unit: "°", descriptionText: "${device.displayName} cut direction is ${deg}°")
                logInfo "Cut direction is ${deg}°"
            }
            updatedAny = true
        }

    } else if (matchesEntity(entityId, errorEntityId)) {
        handleErrorUpdate(newState, attrs)
        updatedAny = true

    } else if (matchesEntity(entityId, rainEntityId)) {
        handleRainUpdate(newState)
        updatedAny = true

    } else if (matchesEntity(entityId, bladeEntityId)) {
        if (isValidNumeric(newState)) {
            Integer pct = newState.toBigDecimal().toInteger()
            if (device.currentValue("bladeLife") != pct) {
                sendEvent(name: "bladeLife", value: pct, unit: "%", descriptionText: "${device.displayName} blade life is ${pct}%")
                logInfo "Blade life is ${pct}%"
            }
            updatedAny = true
        }

    } else if (matchesEntity(entityId, areaEntityId)) {
        if (isValidNumeric(newState)) {
            BigDecimal val = newState.toBigDecimal()
            String unit = attrs?.unit_of_measurement?.toString() ?: "m²"
            if (device.currentValue("areaMowed") != val) {
                sendEvent(name: "areaMowed", value: val, unit: unit, descriptionText: "${device.displayName} area mowed is ${val} ${unit}")
                logInfo "Area mowed is ${val} ${unit}"
            }
            updatedAny = true
        }

    } else if (matchesEntity(entityId, durationEntityId)) {
        if (isValidNumeric(newState)) {
            BigDecimal val = newState.toBigDecimal()
            String unit = attrs?.unit_of_measurement?.toString() ?: "min"
            if (device.currentValue("mowingDuration") != val) {
                sendEvent(name: "mowingDuration", value: val, unit: unit, descriptionText: "${device.displayName} mowing duration is ${val} ${unit}")
                logInfo "Mowing duration is ${val} ${unit}"
            }
            updatedAny = true
        }

    } else if (matchesEntity(entityId, rssiEntityId)) {
        if (isValidNumeric(newState)) {
            Integer dbm = newState.toBigDecimal().toInteger()
            if (device.currentValue("rssi") != dbm) {
                sendEvent(name: "rssi", value: dbm, unit: "dBm", descriptionText: "${device.displayName} Wi-Fi RSSI is ${dbm} dBm")
            }
            updatedAny = true
        }

    } else if (matchesEntity(entityId, lastJobEntityId)) {
        // HA event.* entities put the stop reason in attrs.event_type
        String reason = attrs?.event_type?.toString() ?: ((newState && !(newState.toLowerCase() in ["unknown", "unavailable"])) ? newState : null)
        if (reason && device.currentValue("lastJobReason") != reason) {
            sendEvent(name: "lastJobReason", value: reason, descriptionText: "${device.displayName} last job reason is ${reason}")
            logInfo "Last job reason is ${reason}"
            updatedAny = true
        }
    }

    if (updatedAny) {
        touchLastUpdated()
    }
}

private void handleErrorUpdate(String newState, Map attrs) {
    if (newState == null && !attrs?.containsKey("description")) return

    String code = newState != null ? newState.trim() : (device.currentValue("errorCode")?.toString() ?: "0")
    String lower = code.toLowerCase()

    if (lower in ["unknown", "unavailable"]) return

    boolean noErr = (lower in ["0", "none", "no_error", "no error", "ok", ""])
    String hasErrVal = noErr ? "false" : "true"
    String desc = attrs?.description?.toString()?.trim()
    if (!desc) {
        desc = noErr ? "None" : "Error ${code}"
    }

    if (device.currentValue("errorCode")?.toString() != code) {
        sendEvent(name: "errorCode", value: code)
    }
    if (device.currentValue("errorDescription") != desc) {
        sendEvent(name: "errorDescription", value: desc, descriptionText: "${device.displayName} error: ${desc}")
    }
    if (device.currentValue("hasError") != hasErrVal) {
        sendEvent(name: "hasError", value: hasErrVal, descriptionText: "${device.displayName} hasError is ${hasErrVal}")
        logInfo(noErr ? "Error cleared (0)" : "Error detected: ${code} - ${desc}")
    }
}

private void handleRainUpdate(String newState) {
    if (newState == null) return
    String lower = newState.trim().toLowerCase()
    if (lower in ["unknown", "unavailable"]) return

    String normalized
    if (lower in ["on", "wet", "true", "raining", "rain", "detected", "1"]) {
        normalized = "wet"
    } else if (lower in ["off", "dry", "false", "clear", "none", "0"]) {
        normalized = "dry"
    } else {
        normalized = "unknown"
    }

    if (device.currentValue("rainStatus") != normalized) {
        sendEvent(name: "rainStatus", value: normalized, descriptionText: "${device.displayName} rain status is ${normalized}")
        logInfo "Rain status is ${normalized}"
    }
}

private boolean matchesEntity(String incoming, String configured) {
    return configured?.trim() && incoming == configured.trim()
}

private boolean isValidNumeric(String val) {
    return val != null && val.trim().isNumber()
}

private void updateBattery(Integer level) {
    if (level != null && device.currentValue("battery") != level) {
        sendEvent(name: "battery", value: level, unit: "%", descriptionText: "${device.displayName} battery is ${level}%")
        logInfo "Battery is ${level}%"
    }
}

private void touchLastUpdated() {
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// --- Commands ---

def on() {
    startMowing()
}

def off() {
    dock()
}

def startMowing() {
    logInfo "Sending Start Mowing command"
    callHaService("lawn_mower", "start_mowing", [:], [entity_id: mowerEntityId.trim()])
}

def pause() {
    logInfo "Sending Pause command"
    callHaService("lawn_mower", "pause", [:], [entity_id: mowerEntityId.trim()])
}

def stop() {
    logInfo "Sending Stop command"
    callHaService("lawn_mower", "stop", [:], [entity_id: mowerEntityId.trim()])
}

def dock() {
    logInfo "Sending Return to Dock command"
    callHaService("lawn_mower", "dock", [:], [entity_id: mowerEntityId.trim()])
}

def refresh() {
    if (device.currentValue("connectionStatus") == "connected") {
        subscribeToEntities()
    } else {
        connect()
    }
}

def setCutDirection(BigDecimal degrees) {
    if (!cutDirEntityId?.trim()) {
        log.warn "${device.displayName}: Cut Direction Entity ID is not configured."
        return
    }
    Integer clamped = Math.max(0, Math.min(180, degrees.toInteger()))
    logInfo "Setting cut direction to ${clamped}°"
    callHaService("number", "set_value", [value: clamped], [entity_id: cutDirEntityId.trim()])
}

def mowZone(String zoneName) {
    if (!zoneName?.trim()) {
        log.warn "${device.displayName}: mowZone requires a zone name or ID."
        return
    }

    Map<String, String> zones = parseZoneMap()
    String lookupKey = zoneName.trim()
    String mappedValue = zones.find { k, v -> k.equalsIgnoreCase(lookupKey) }?.value ?: lookupKey

    sendEvent(name: "activeZone", value: lookupKey, descriptionText: "${device.displayName} mowing zone ${lookupKey}")

    switch (zoneMode ?: "script") {
        case "script":
            String scriptEntity = mappedValue.startsWith("script.") ? mappedValue : "script.${mappedValue}"
            logInfo "Starting zone '${lookupKey}' via HA script ${scriptEntity}"
            callHaService("script", "turn_on", [:], [entity_id: scriptEntity])
            break

        case "select":
            if (!zoneSelectEntityId?.trim()) {
                log.error "${device.displayName}: Zone Select Entity ID is required for Select mode."
                return
            }
            logInfo "Selecting zone '${mappedValue}' on ${zoneSelectEntityId} and starting mower"
            callHaService("select", "select_option", [option: mappedValue], [entity_id: zoneSelectEntityId.trim()])
            runIn(2, "startMowing")
            break

        case "service":
            if (!customZoneService?.contains(".")) {
                log.error "${device.displayName}: Custom HA Service must be in domain.service format."
                return
            }
            String[] parts = customZoneService.trim().split("\\.", 2)
            String paramKey = customZoneParamKey?.trim() ?: "zone_id"
            logInfo "Calling ${parts[0]}.${parts[1]} with ${paramKey}=${mappedValue}"
            callHaService(parts[0], parts[1], [(paramKey): mappedValue], [entity_id: mowerEntityId.trim()])
            break
    }
}

private Map<String, String> parseZoneMap() {
    Map<String, String> result = [:]
    if (!zoneMap?.trim()) return result
    zoneMap.split(",").each { String entry ->
        String[] kv = entry.split("=", 2)
        if (kv.length == 2 && kv[0].trim() && kv[1].trim()) {
            result[kv[0].trim()] = kv[1].trim()
        }
    }
    return result
}

private void callHaService(String domain, String service, Map serviceData = [:], Map target = [:]) {
    if (device.currentValue("connectionStatus") != "connected") {
        log.warn "${device.displayName}: WebSocket not connected; attempting reconnect before service call."
        connect()
        return
    }

    Map payload = [
        id: nextWsId(),
        type: "call_service",
        domain: domain,
        service: service
    ]
    if (serviceData) payload.service_data = serviceData
    if (target) payload.target = target

    sendWsMessage(payload)
}

private Integer nextWsId() {
    Integer next = ((state.wsId ?: 0) as Integer) + 1
    if (next > 1000000) next = 1
    state.wsId = next
    return next
}

private void sendWsMessage(Map payload) {
    String json = JsonOutput.toJson(payload)
    logDebug "WS TX: ${payload.type == 'auth' ? '{"type":"auth","access_token":"***"}' : json}"
    interfaces.webSocket.sendMessage(json)
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}

private void logDebug(String msg) {
    if (debugLogging) log.debug "${device.displayName}: ${msg}"
}
