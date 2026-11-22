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
 *  v1.0.1 - 2026-11-22 - Format areaMowed as integer sq ft (auto-converting m² if needed) and
 *                        mowingDuration as integer hours & minutes (e.g., "452h 13m").
 *  v1.0.2 - 2026-11-22 - Fix HA WebSocket id_reuse error using thread-safe @Field AtomicInteger counter
 *                        and cleanly unsubscribe previous entity subscription on refresh.
 *  v1.1.0 - 2026-11-22 - Add automatic HA device entity discovery via /api/template; eliminate manual
 *                        sensor entity entry and surface any extra discovered entities dynamically.
 *  v1.1.1 - 2026-11-22 - Add Imperial/Metric unitSystem preference with bi-directional conversion for
 *                        areaMowed and auto-discovered area, length, and temperature entities.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Field static ConcurrentHashMap<String, AtomicInteger> wsIdCounters = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<String, Integer> activeSubIds = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<String, Map> entityMetaCache = new ConcurrentHashMap<>()

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
        attribute "discoveredEntityCount", "number"
        attribute "lastUpdated", "string"

        // Auto-Mapped Ecovacs Sensor Attributes
        attribute "hasError", "enum", ["true", "false"]
        attribute "errorCode", "string"
        attribute "errorDescription", "string"
        attribute "rainStatus", "enum", ["wet", "dry", "unknown"]
        attribute "bladeLife", "number"
        attribute "areaMowed", "number"
        attribute "mowingDuration", "string"
        attribute "rssi", "number"
        attribute "lastJobReason", "string"
    }

    preferences {
        // Home Assistant Connection
        input name: "haHost", type: "text", title: "Home Assistant IP or Hostname (e.g., 10.0.0.87)", required: true
        input name: "haPort", type: "number", title: "Home Assistant Port", defaultValue: 8123, required: true
        input name: "useTls", type: "bool", title: "Use Secure Connection (wss:// and https://)", defaultValue: false
        input name: "haToken", type: "password", title: "HA Long-Lived Access Token", required: true

        // Mower Entity & Discovery
        input name: "mowerEntityId", type: "text", title: "Lawn Mower Entity ID (e.g., lawn_mower.outside_billie_goat_3000)", required: true
        input name: "autoDiscover", type: "bool", title: "Auto-discover all enabled HA entities for this mower", defaultValue: true
        input name: "extraEntityIds", type: "text", title: "Additional HA Entity IDs (optional, comma-separated)", required: false

        // Display Units
        input name: "unitSystem", type: "enum", title: "Measurement Units", options: [
            "imperial": "Imperial (sq ft, in, ft, °F)",
            "metric": "Metric (m², cm, m, °C)"
        ], defaultValue: "imperial", required: true

        // Per-Zone Mowing
        input name: "zoneMode", type: "enum", title: "Per-Zone Mowing Mode", options: [
            "script": "HA Script (Map: ZoneName=script.entity_id)",
            "select": "HA Select Entity + Start Mowing (Map: ZoneName=OptionName)",
            "service": "Custom HA Service Call (Map: ZoneName=zone_id)"
        ], defaultValue: "script", required: true
        input name: "zoneSelectEntityId", type: "text", title: "[Select Mode] HA Select Entity ID (optional if auto-discovered)", required: false
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

    String devKey = device.id.toString()
    wsIdCounters.put(devKey, new AtomicInteger(0))
    activeSubIds.remove(devKey)
    entityMetaCache.put(devKey, new ConcurrentHashMap<>())

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
    activeSubIds.remove(device.id.toString())
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
        activeSubIds.remove(device.id.toString())
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
            discoverAndSubscribe()
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

// --- Auto-Discovery via HA /api/template ---

private void discoverAndSubscribe() {
    if (autoDiscover == false) {
        subscribeToEntities(buildEntityList([]))
        return
    }

    String mowerId = mowerEntityId.trim().replace("'", "")
    String httpScheme = useTls ? "https" : "http"
    String tpl = "{% set did = device_id('${mowerId}') %}{{ (device_entities(did) if did else []) | tojson }}"

    Map params = [
        uri: "${httpScheme}://${haHost.trim()}:${haPort ?: 8123}/api/template",
        headers: [
            "Authorization": "Bearer ${haToken}",
            "Content-Type": "application/json"
        ],
        body: JsonOutput.toJson([template: tpl]),
        ignoreSSLIssues: true,
        timeout: 15
    ]

    logDebug "Querying Home Assistant for all entities on device owning '${mowerId}'"
    asynchttpPost("handleDiscoveryResponse", params)
}

def handleDiscoveryResponse(resp, data) {
    List<String> discovered = []
    if (resp.hasError()) {
        log.warn "${device.displayName}: Auto-discovery failed (HTTP ${resp.status} - ${resp.errorMessage}); falling back to mower entity only."
    } else {
        try {
            def parsed = new JsonSlurper().parseText(resp.data?.toString() ?: "[]")
            if (parsed instanceof List) {
                List<String> allowedDomains = ["lawn_mower", "sensor", "binary_sensor", "number", "select", "switch", "event"]
                discovered = parsed.collect { it?.toString()?.trim() }.findAll { String eid ->
                    eid && allowedDomains.any { eid.startsWith("${it}.") }
                }
                logInfo "Auto-discovered ${discovered.size()} Home Assistant entities"
                logDebug "Auto-discovered entities: ${discovered}"
            }
        } catch (Exception e) {
            log.warn "${device.displayName}: Could not parse discovery response (${e.message}); falling back to mower entity only."
        }
    }

    List<String> finalEntities = buildEntityList(discovered)
    state.discoveredEntities = finalEntities
    sendEvent(name: "discoveredEntityCount", value: finalEntities.size())
    subscribeToEntities(finalEntities)
}

private List<String> buildEntityList(List<String> discovered) {
    List<String> combined = [mowerEntityId.trim()]
    if (discovered) combined.addAll(discovered)
    if (extraEntityIds?.trim()) {
        extraEntityIds.split(",").each { String eid ->
            if (eid?.trim()) combined << eid.trim()
        }
    }
    return combined.unique()
}

private void subscribeToEntities(List<String> entities) {
    String devKey = device.id.toString()
    Integer oldSubId = activeSubIds.remove(devKey)
    if (oldSubId != null) {
        sendWsMessage([
            id: nextWsId(),
            type: "unsubscribe_events",
            subscription: oldSubId
        ])
    }

    Integer subId = nextWsId()
    activeSubIds.put(devKey, subId)
    sendWsMessage([
        id: subId,
        type: "subscribe_entities",
        entity_ids: entities
    ])
    logDebug "Subscribed to ${entities.size()} HA entities: ${entities}"
}

// --- Entity Event Handling & Smart Routing ---

private void handleEntityEvent(Map ev) {
    if (!ev) return

    // Initial snapshot ("a" = added)
    if (ev.a instanceof Map) {
        ev.a.each { String entityId, Map data ->
            processEntityUpdate(entityId, data?.s?.toString(), (data?.a instanceof Map) ? data.a : [:], true)
        }
    }

    // Incremental delta ("c" = changed, "+" = added/modified fields)
    if (ev.c instanceof Map) {
        ev.c.each { String entityId, Map diff ->
            Map plus = (diff?."+" instanceof Map) ? diff."+" : null
            if (plus) {
                String newState = plus.containsKey("s") ? plus.s?.toString() : null
                Map newAttrs = (plus.a instanceof Map) ? plus.a : [:]
                processEntityUpdate(entityId, newState, newAttrs, false)
            }
        }
    }
}

private Map getEntityMeta(String entityId) {
    String devKey = device.id.toString()
    Map devCache = entityMetaCache.get(devKey)
    if (devCache == null) {
        Map fresh = new ConcurrentHashMap<>()
        Map existing = entityMetaCache.putIfAbsent(devKey, fresh)
        devCache = (existing != null) ? existing : fresh
    }
    Map meta = devCache.get(entityId)
    if (meta == null) {
        meta = [:]
        devCache.put(entityId, meta)
    }
    return meta
}

private String classifyEntity(String entityId, Map attrs, Map meta) {
    if (attrs?.device_class) meta.deviceClass = attrs.device_class.toString().toLowerCase()
    if (attrs?.unit_of_measurement) meta.unit = attrs.unit_of_measurement.toString()
    if (attrs?.friendly_name) meta.friendlyName = attrs.friendly_name.toString()

    if (meta.role) return meta.role

    String eid = entityId.toLowerCase()
    String dc = meta.deviceClass ?: ""
    String role

    if (entityId == mowerEntityId?.trim()) {
        role = "mower"
    } else if (dc == "battery" || eid.endsWith("_battery")) {
        role = "battery"
    } else if (eid.startsWith("number.") && (eid.contains("cut_direction") || eid.contains("cutting_direction"))) {
        role = "cutDirection"
        state.discoveredCutDirEntity = entityId
    } else if (eid.startsWith("sensor.") && eid.endsWith("_error")) {
        role = "error"
    } else if (eid.contains("rain")) {
        role = "rain"
    } else if (eid.contains("blade")) {
        role = "blade"
    } else if (eid.contains("total_mowed_area") || eid.contains("area_mowed") || (dc == "area" && !eid.contains("current") && !eid.contains("job"))) {
        role = "area"
    } else if (eid.contains("total_mowing_time") || eid.contains("mowing_duration") || (dc == "duration" && !eid.contains("current") && !eid.contains("job"))) {
        role = "duration"
    } else if (dc == "signal_strength" || eid.contains("rssi") || eid.contains("wi_fi_signal") || eid.contains("wifi_signal")) {
        role = "rssi"
    } else if (eid.startsWith("event.") && eid.contains("job")) {
        role = "lastJob"
    } else {
        role = "dynamic"
        if (eid.startsWith("select.") && eid.contains("zone")) {
            state.discoveredZoneSelectEntity = entityId
        }
    }

    meta.role = role
    return role
}

private boolean isMetric() {
    return (unitSystem ?: "imperial") == "metric"
}

private Map convertUnitValue(BigDecimal val, String srcUnitRaw) {
    String srcUnit = (srcUnitRaw ?: "").trim()
    String lower = srcUnit.toLowerCase()

    if (isMetric()) {
        if (lower in ["ft²", "ft2", "sq ft", "sqft"]) {
            Integer m2 = (val / 10.7639104167).setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
            return [value: m2, unit: "m²"]
        } else if (lower in ["m²", "m2", "sqm", "sq m"]) {
            return [value: val.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger(), unit: "m²"]
        } else if (lower in ["in", "inch", "inches"]) {
            return [value: (val * 2.54).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "cm"]
        } else if (lower in ["ft", "foot", "feet"]) {
            return [value: (val / 3.280839895).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "m"]
        } else if (lower in ["°f", "f"]) {
            return [value: ((val - 32) * 5 / 9).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "°C"]
        }
    } else {
        if (lower in ["m²", "m2", "sqm", "sq m"]) {
            Integer sqFt = (val * 10.7639104167).setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
            return [value: sqFt, unit: "sq ft"]
        } else if (lower in ["ft²", "ft2", "sq ft", "sqft"]) {
            return [value: val.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger(), unit: "sq ft"]
        } else if (lower == "mm") {
            return [value: (val / 25.4).setScale(2, BigDecimal.ROUND_HALF_UP), unit: "in"]
        } else if (lower == "cm") {
            return [value: (val / 2.54).setScale(2, BigDecimal.ROUND_HALF_UP), unit: "in"]
        } else if (lower == "m") {
            return [value: (val * 3.280839895).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "ft"]
        } else if (lower in ["°c", "c"]) {
            return [value: ((val * 9 / 5) + 32).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "°F"]
        }
    }

    return [value: val, unit: srcUnit ?: null]
}

private void processEntityUpdate(String entityId, String newState, Map attrs, boolean isSnapshot) {
    Map meta = getEntityMeta(entityId)
    String role = classifyEntity(entityId, attrs, meta)
    boolean updatedAny = false

    switch (role) {
        case "mower":
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
            break

        case "battery":
            if (isValidNumeric(newState)) {
                updateBattery(newState.toBigDecimal().toInteger())
                updatedAny = true
            }
            break

        case "cutDirection":
            if (isValidNumeric(newState)) {
                Integer deg = newState.toBigDecimal().toInteger()
                if (device.currentValue("cutDirection") != deg) {
                    sendEvent(name: "cutDirection", value: deg, unit: "°", descriptionText: "${device.displayName} cut direction is ${deg}°")
                    logInfo "Cut direction is ${deg}°"
                }
                updatedAny = true
            }
            break

        case "error":
            handleErrorUpdate(newState, attrs)
            updatedAny = true
            break

        case "rain":
            handleRainUpdate(newState)
            updatedAny = true
            break

        case "blade":
            if (isValidNumeric(newState)) {
                Integer pct = newState.toBigDecimal().toInteger()
                if (device.currentValue("bladeLife") != pct) {
                    sendEvent(name: "bladeLife", value: pct, unit: "%", descriptionText: "${device.displayName} blade life is ${pct}%")
                    logInfo "Blade life is ${pct}%"
                }
                updatedAny = true
            }
            break

        case "area":
            if (isValidNumeric(newState)) {
                String srcUnit = meta.unit ?: (isMetric() ? "m²" : "ft²")
                Map converted = convertUnitValue(newState.toBigDecimal(), srcUnit)
                Integer areaInt = (converted.value instanceof Number) ? converted.value.toBigDecimal().setScale(0, BigDecimal.ROUND_HALF_UP).toInteger() : newState.toBigDecimal().setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
                String targetUnit = converted.unit ?: (isMetric() ? "m²" : "sq ft")
                if (device.currentValue("areaMowed")?.toString() != areaInt.toString() || state.lastAreaUnit != targetUnit) {
                    state.lastAreaUnit = targetUnit
                    sendEvent(name: "areaMowed", value: areaInt, unit: targetUnit, descriptionText: "${device.displayName} area mowed is ${areaInt} ${targetUnit}")
                    logInfo "Area mowed is ${areaInt} ${targetUnit}"
                }
                updatedAny = true
            }
            break

        case "duration":
            if (isValidNumeric(newState)) {
                BigDecimal rawVal = newState.toBigDecimal()
                String srcUnit = (meta.unit ?: "h").trim().toLowerCase()
                Integer totalMinutes
                if (srcUnit in ["h", "hr", "hrs", "hour", "hours"]) {
                    totalMinutes = (rawVal * 60).setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
                } else if (srcUnit in ["s", "sec", "secs", "second", "seconds"]) {
                    totalMinutes = (rawVal / 60).setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
                } else {
                    totalMinutes = rawVal.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
                }
                Integer hours = totalMinutes.intdiv(60) as Integer
                Integer mins = totalMinutes % 60
                String formatted = "${hours}h ${mins}m"
                if (device.currentValue("mowingDuration")?.toString() != formatted) {
                    sendEvent(name: "mowingDuration", value: formatted, descriptionText: "${device.displayName} mowing duration is ${formatted}")
                    logInfo "Mowing duration is ${formatted}"
                }
                updatedAny = true
            }
            break

        case "rssi":
            if (isValidNumeric(newState)) {
                Integer dbm = newState.toBigDecimal().toInteger()
                if (device.currentValue("rssi") != dbm) {
                    sendEvent(name: "rssi", value: dbm, unit: "dBm", descriptionText: "${device.displayName} Wi-Fi RSSI is ${dbm} dBm")
                }
                updatedAny = true
            }
            break

        case "lastJob":
            String reason = attrs?.event_type?.toString() ?: ((newState && !(newState.toLowerCase() in ["unknown", "unavailable"])) ? newState : null)
            if (reason && device.currentValue("lastJobReason") != reason) {
                sendEvent(name: "lastJobReason", value: reason, descriptionText: "${device.displayName} last job reason is ${reason}")
                logInfo "Last job reason is ${reason}"
                updatedAny = true
            }
            break

        default:
            // Any other discovered entity becomes a clean camelCase attribute in Current States
            if (newState != null && !(newState.toLowerCase() in ["unknown", "unavailable"])) {
                String attrName = buildDynamicAttrName(entityId)
                def val = newState
                String outUnit = meta.unit
                if (isValidNumeric(newState)) {
                    Map converted = convertUnitValue(newState.toBigDecimal(), meta.unit)
                    val = converted.value
                    outUnit = converted.unit
                }
                if (device.currentValue(attrName)?.toString() != val.toString()) {
                    Map evMap = [name: attrName, value: val]
                    if (outUnit) evMap.unit = outUnit
                    sendEvent(evMap)
                    logDebug "Dynamic entity ${entityId} -> ${attrName} = ${val}${outUnit ? ' ' + outUnit : ''}"
                }
                updatedAny = true
            }
            break
    }

    if (updatedAny) {
        touchLastUpdated()
    }
}

private String buildDynamicAttrName(String entityId) {
    String obj = entityId.contains(".") ? entityId.split("\\.", 2)[1] : entityId
    String mowerObj = mowerEntityId?.trim()?.contains(".") ? mowerEntityId.trim().split("\\.", 2)[1] : ""
    if (mowerObj && obj.startsWith("${mowerObj}_")) {
        obj = obj.substring(mowerObj.length() + 1)
    }
    List<String> parts = obj.split("_").findAll { it }
    if (!parts) return "haEntity"
    StringBuilder sb = new StringBuilder(parts[0].toLowerCase())
    for (int i = 1; i < parts.size(); i++) {
        String p = parts[i].toLowerCase()
        sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1))
    }
    return sb.toString()
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
        discoverAndSubscribe()
    } else {
        connect()
    }
}

def setCutDirection(BigDecimal degrees) {
    String cutEntity = state.discoveredCutDirEntity
    if (!cutEntity) {
        log.warn "${device.displayName}: Cut Direction entity was not discovered. Enable it in Home Assistant and click Refresh."
        return
    }
    Integer clamped = Math.max(0, Math.min(180, degrees.toInteger()))
    logInfo "Setting cut direction to ${clamped}° on ${cutEntity}"
    callHaService("number", "set_value", [value: clamped], [entity_id: cutEntity])
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
            String selEntity = zoneSelectEntityId?.trim() ?: state.discoveredZoneSelectEntity
            if (!selEntity) {
                log.error "${device.displayName}: Zone Select Entity ID is required for Select mode."
                return
            }
            logInfo "Selecting zone '${mappedValue}' on ${selEntity} and starting mower"
            callHaService("select", "select_option", [option: mappedValue], [entity_id: selEntity])
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
    String devKey = device.id.toString()
    AtomicInteger counter = wsIdCounters.get(devKey)
    if (counter == null) {
        AtomicInteger fresh = new AtomicInteger(0)
        AtomicInteger existing = wsIdCounters.putIfAbsent(devKey, fresh)
        counter = (existing != null) ? existing : fresh
    }
    return counter.incrementAndGet()
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