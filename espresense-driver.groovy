/*
 *  ESPresense Device Tracker MQTT Driver
 *  Device Driver for Hubitat Elevation hub
 *  Tracks device proximity using ESPresense MQTT data
 *  2025-03-13
 */
metadata {
    definition(name: 'ESPresense Device Tracker MQTT Driver', namespace: 'dodd', author: 'Tim Dodd') {
        capability 'Initialize'
        capability 'Sensor'
        attribute 'closestRoom', 'string'
        attribute 'previousRoom', 'string'
        attribute 'roomChangedDate', 'date'
    }
}

preferences {
    section('MQTT Configuration') {
        input 'mqttBroker', 'string', title: 'MQTT Broker Address:Port', required: true
        input 'mqttTopic', 'string', title: 'MQTT Topic Base', description: '(e.g. espresense/devices)', required: true
        input 'deviceId', 'string', title: 'Device ID', description: 'Unique ID of the device (e.g. phone:timsiphone)', required: true
        input name: 'dataTimeout', type: 'number', title: 'Data timeout (seconds)', defaultValue: 15, description: 'How long before room data is considered stale'
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
    }
}

/**
 * Lifecycle Methods
 */
def installed() {
    logDebug 'Driver installed'
}

def updated() {
    logDebug 'Driver updated'
    initialize()
}

def uninstalled() {
    logDebug 'Driver uninstalled'
    disconnect()
}

/**
 * MQTT Connection Management
 */
def initialize() {
    logDebug 'Initializing MQTT connection'
    disconnect()
    connect()
    // Initialize state data structure for room timestamps
    state.roomDistances = state.roomDistances ?: [:]
    state.roomTimestamps = state.roomTimestamps ?: [:]

    // Schedule periodic cleanup of stale room data
    schedule('0/15 * * * * ?', cleanupStaleRoomData) // Run every 15 seconds
}

def connect() {
    try {
        log.info "Connecting to MQTT Broker: ${settings.mqttBroker}"
        interfaces.mqtt.connect("tcp://${settings.mqttBroker}", "hubitat_espresense_${device.id}", null, null)
    } catch (e) {
        log.error "Error connecting to MQTT: ${e.message}"
        delayedConnect()
    }
}
def fullTopic() {
    return "${settings.mqttTopic}/${settings.deviceId}/#"
}
def disconnect() {
    log.info 'Disconnecting from MQTT Broker'
    unschedule(cleanupStaleRoomData)
    interfaces.mqtt.unsubscribe(fullTopic())
    interfaces.mqtt.disconnect()
}

def delayedConnect() {
    def delay = state.delay ?: 5
    delay = Math.min(delay + 5, 3600) // Max delay of 1 hour
    state.delay = delay
    logDebug "Reconnecting in ${delay} seconds"
    runIn(delay, connect)
}

def subscribe() {
    def topic = fullTopic()
    interfaces.mqtt.subscribe(topic)
    logDebug "Subscribed to topic: ${topic}"
}

def mqttClientStatus(String status) {
    def parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn "MQTT Error: ${status}"
            delayedConnect()
            break
        case 'Status':
            log.info "MQTT Status: ${status}"
            if (parts[1] == 'Connection succeeded') {
                state.delay = 0
                runIn(1, subscribe)
            }
            break
    }
}

/**
 * MQTT Message Handling
 */
def parse(String description) {
    try {
        // Parse the MQTT message
        def message = interfaces.mqtt.parseMessage(description)

        def topic = message.topic

        // Extract the room name from the topic
        def topicParts = topic.split('/')
        def room = topicParts[-1] // Get the last part of the topic

        // Fix for key=value format in topic
        if (room.contains('=')) {
            def roomParts = room.split('=')
            room = roomParts[0].trim()
        // If we have a direct distance in the topic, we could use it
        // But we'll prefer the JSON payload data since it's more reliable
        }

        logDebug "Received MQTT message payload: ${message.payload}"

        // Try to parse as JSON first
        def distance = null
        try {
            def payload = new groovy.json.JsonSlurper().parseText(message.payload)
            logDebug "Parsed JSON payload: ${payload}"
            distance = payload.distance
        } catch (Exception e) {
            // If JSON parsing fails, try to handle other formats
            logDebug "JSON parsing failed, trying alternative parsing: ${e.message}"

            // Check if it's a simple key=value format
            if (message.payload.contains('=')) {
                try {
                    def parts = message.payload.split('=')
                    if (parts.size() >= 2) {
                        distance = parts[1].trim().toFloat()
                        logDebug "Parsed distance from key=value format: ${distance}"
                    }
                } catch (Exception e2) {
                    log.error "Failed to parse key=value format: ${e2.message}"
                }
            } else {
                // Try to parse as a simple number
                try {
                    distance = message.payload.trim().toFloat()
                    logDebug "Parsed distance as numeric value: ${distance}"
                } catch (Exception e3) {
                    log.error "Failed to parse as numeric value: ${e3.message}"
                }
            }
        }

        // Store room distance and timestamp
        state.roomDistances = state.roomDistances ?: [:]
        state.roomTimestamps = state.roomTimestamps ?: [:]
        state.roomDistances[room] = distance
        state.roomTimestamps[room] = now()

        // Recalculate closest room with fresh data
        calculateClosestRoom()
    } catch (e) {
        log.error "Error parsing MQTT message: ${e.message}"
    }
}
def cleanupStaleRoomData() {
    def timeout = (settings.dataTimeout ?: 15) * 1000 // Convert to milliseconds
    def currentTime = now()
    def dataChanged = false

    // Initialize state maps if they don't exist
    state.roomDistances = state.roomDistances ?: [:]
    state.roomTimestamps = state.roomTimestamps ?: [:]

    // First, remove entries from roomDistances that don't have a timestamp
    def roomsToRemove = []
    state.roomDistances.each { room, distance ->
        if (!state.roomTimestamps.containsKey(room)) {
            roomsToRemove << room
            dataChanged = true
            logDebug "Room ${room} has no timestamp and will be removed"
        }
    }

    // Remove the identified rooms from roomDistances
    roomsToRemove.each { room ->
        state.roomDistances.remove(room)
    }

    // Now handle stale timestamps
    def freshTimestamps = [:]
    state.roomTimestamps.each { room, timestamp ->
        if ((currentTime - timestamp) <= timeout) {
            // Keep fresh entries
            freshTimestamps[room] = timestamp
        } else {
            // Remove stale entries
            dataChanged = true
            logDebug "Room ${room} data is stale (${(currentTime - timestamp)/1000}s old) and will be removed"
            state.roomDistances.remove(room)
        }
    }

    // Update timestamps with only the fresh ones
    state.roomTimestamps = freshTimestamps

    logDebug "After cleanup: ${state.roomDistances.size()} rooms with distances, ${state.roomTimestamps.size()} rooms with timestamps"

    // If data changed, recalculate closest room
    if (dataChanged) {
        calculateClosestRoom()
    }
}
/**
 * Calculate the closest room based on the current valid data
 */
def calculateClosestRoom() {
    // Get the room with the minimum distance
    def roomDistances = state.roomDistances ?: [:]

    logDebug "Current roomDistances: ${roomDistances}"

    if (roomDistances.isEmpty()) {
        if (state.closestRoom) {
            logInfo 'All room data is stale, no closest room available'
            state.previousRoom = state.closestRoom
            state.closestRoom = null
            state.closestDistance = null
            state.roomChangedDate = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)

            sendEvent(name: 'closestRoom', value: 'none', descriptionText: 'No valid room data available')
            sendEvent(name: 'previousRoom', value: state.previousRoom, descriptionText: "Previously in ${state.previousRoom}")
            sendEvent(name: 'roomChangedDate', value: state.roomChangedDate, descriptionText: "Room changed at ${state.roomChangedDate}")
        }
        return
    }

    // Ensure all values in the map are numeric
    def sanitizedDistances = [:]
    roomDistances.each { roomKey, distValue ->
        try {
            // Handle any non-standard room keys (clean them up)
            def cleanRoomKey = roomKey
            if (roomKey instanceof String && roomKey.contains('=')) {
                cleanRoomKey = roomKey.split('=')[0].trim()
            }

            // Handle any non-numeric distance values
            def numericDistance = 0
            if (distValue instanceof Number) {
                numericDistance = distValue as float
            } else if (distValue instanceof String) {
                numericDistance = distValue.trim().toFloat()
            } else {
                logDebug "Converting non-standard distance value: ${distValue} (${distValue.class.name})"
                numericDistance = distValue.toString().trim().toFloat()
            }

            sanitizedDistances[cleanRoomKey] = numericDistance
            logDebug "Sanitized: ${cleanRoomKey} -> ${numericDistance}"
        } catch (Exception e) {
            log.error "Error processing distance entry [${roomKey}:${distValue}]: ${e.message}"
        }
    }

    if (sanitizedDistances.isEmpty()) {
        logInfo 'No valid distance data after sanitization'
        return
    }

    // Find the minimum distance entry
    def closestEntry = null
    def closestRoom = null
    def closestDistance = Float.MAX_VALUE

    sanitizedDistances.each { room, distance ->
        if (distance < closestDistance) {
            closestRoom = room
            closestDistance = distance
        }
    }

    if (closestRoom == null) {
        logInfo 'Could not determine closest room'
        return
    }

    logDebug "Found closest room: ${closestRoom} with distance: ${closestDistance}"

    // Update if the closest room has changed
    if (closestRoom != state.closestRoom || closestDistance != state.closestDistance) {
        state.previousRoom = state.closestRoom
        state.closestRoom = closestRoom
        state.closestDistance = closestDistance
        state.roomChangedDate = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)

        logInfo "Closest room changed to: ${closestRoom}"
        logDebug "Updated closest room to: ${closestRoom}, distance: ${closestDistance}m"

        sendEvent(name: 'closestRoom', value: closestRoom, descriptionText: "Device is closest to ${closestRoom}")
        sendEvent(name: 'previousRoom', value: state.previousRoom ?: 'none', descriptionText: "Previously in ${state.previousRoom ?: 'none'}")
        sendEvent(name: 'roomChangedDate', value: state.roomChangedDate, descriptionText: "Room changed at ${state.roomChangedDate}")
    }
}

def roundToNearestHalf(double value) {
    return (Math.round(value * 5) / 5.0).toBigDecimal().setScale(1, BigDecimal.ROUND_HALF_UP)
}
/**
 * Logging Helpers
 */
def logInfo(msg) {
    if (logEnable) log.info msg
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
