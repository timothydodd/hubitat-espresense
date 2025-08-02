/*
 *  ESPresense Device Tracker MQTT Driver
 *  Device Driver for Hubitat Elevation hub
 *  Tracks device proximity using ESPresense MQTT data
 *  2025-03-14
 */
metadata {
    definition(name: 'ESPresense Device Tracker MQTT Driver', namespace: 'dodd', author: 'Tim Dodd') {
        capability 'Initialize'
        capability 'Sensor'
        capability 'PresenceSensor'
        
        attribute 'closestRoom', 'string'
        attribute 'previousRoom', 'string'
        attribute 'roomChangedDate', 'date'
        attribute 'closestDistance', 'number'
        
        command 'refresh'
    }
}

preferences {
    section('MQTT Configuration') {
        input 'mqttBroker', 'string', title: 'MQTT Broker Address:Port', required: true
        input 'mqttTopic', 'string', title: 'MQTT Topic Base', description: '(e.g. espresense/devices)', required: true
        input 'deviceId', 'string', title: 'Device ID', description: 'Unique ID of the device (e.g. phone:timsiphone)', required: true
        input name: 'dataTimeout', type: 'number', title: 'Data timeout (seconds)', defaultValue: 15, description: 'How long before room data is considered stale', range: '5..120'
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
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
    
    // Initialize state
    state.roomDistances = [:]
    state.roomTimestamps = [:]
    state.delay = 0
    
    connect()
    
    // Schedule periodic cleanup
    schedule('0/15 * * * * ?', cleanupStaleRoomData)
}

def connect() {
    if (!settings.mqttBroker || !settings.mqttTopic || !settings.deviceId) {
        log.error "Missing required MQTT configuration"
        return
    }
    
    try {
        def broker = settings.mqttBroker.startsWith('tcp://') ? settings.mqttBroker : "tcp://${settings.mqttBroker}"
        def clientId = "hubitat_espresense_${device.id}"
        
        log.info "Connecting to MQTT Broker: ${broker}"
        interfaces.mqtt.connect(broker, clientId, null, null)
    } catch (e) {
        log.error "Error connecting to MQTT: ${e.message}"
        delayedConnect()
    }
}
def fullTopic() {
    return "${settings.mqttTopic}/${settings.deviceId}/#"
}

def refresh() {
    logDebug "Refresh requested"
    // Re-evaluate current state
    if (state.roomDistances) {
        calculateClosestRoom()
    }
}
def disconnect() {
    try {
        log.info 'Disconnecting from MQTT Broker'
        unschedule(cleanupStaleRoomData)
        interfaces.mqtt.unsubscribe(fullTopic())
        interfaces.mqtt.disconnect()
    } catch (e) {
        logDebug "Error during disconnect: ${e.message}"
    }
}

def delayedConnect() {
    def delay = Math.min((state.delay ?: 5) * 2, 300) // Exponential backoff, max 5 minutes
    state.delay = delay
    logDebug "Reconnecting in ${delay} seconds"
    runIn(delay, connect)
}

def subscribe() {
    def topic = fullTopic()
    interfaces.mqtt.subscribe(topic, 1) // QoS 1 for at least once delivery
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
        if (!message?.topic || !message?.payload) {
            logDebug "Invalid MQTT message: missing topic or payload"
            return
        }

        // Extract room name from topic more efficiently
        def room = message.topic.tokenize('/')[-1]
        if (room.contains('=')) {
            room = room.substring(0, room.indexOf('=')).trim()
        }

        logDebug "Processing message for room: ${room}, payload: ${message.payload}"

        // Parse distance from payload
        def distance = parseDistance(message.payload)
        if (distance == null || distance < 0) {
            logDebug "Invalid distance value for room ${room}"
            return
        }

        // Update room data
        updateRoomData(room, distance)
    } catch (e) {
        log.error "Error parsing MQTT message: ${e.message}"
    }
}

/**
 * Parse distance from various payload formats
 */
private Float parseDistance(String payload) {
    if (!payload?.trim()) return null
    
    try {
        // Try JSON format first (most common)
        if (payload.trim().startsWith('{')) {
            def json = new groovy.json.JsonSlurper().parseText(payload)
            return json.distance as Float
        }
        
        // Try key=value format
        if (payload.contains('=')) {
            def value = payload.substring(payload.indexOf('=') + 1).trim()
            return value.toFloat()
        }
        
        // Try direct numeric value
        return payload.trim().toFloat()
    } catch (e) {
        logDebug "Failed to parse distance from payload: ${e.message}"
        return null
    }
}

/**
 * Update room data with new distance
 */
private void updateRoomData(String room, Float distance) {
    def currentTime = now()
    
    // Initialize state maps if needed
    if (!state.roomDistances) state.roomDistances = [:]
    if (!state.roomTimestamps) state.roomTimestamps = [:]
    
    // Update data
    state.roomDistances[room] = distance
    state.roomTimestamps[room] = currentTime
    
    // Calculate closest room
    calculateClosestRoom()
}
def cleanupStaleRoomData() {
    if (!state.roomTimestamps || state.roomTimestamps.isEmpty()) return
    
    def timeout = (settings.dataTimeout ?: 15) * 1000
    def currentTime = now()
    def staleRooms = []
    
    // Find stale rooms
    state.roomTimestamps.each { room, timestamp ->
        if ((currentTime - timestamp) > timeout) {
            staleRooms << room
            logDebug "Room ${room} data is stale (${(currentTime - timestamp)/1000}s old)"
        }
    }
    
    // Remove stale data if any found
    if (staleRooms) {
        staleRooms.each { room ->
            state.roomDistances?.remove(room)
            state.roomTimestamps.remove(room)
        }
        calculateClosestRoom()
    }
}
/**
 * Calculate the closest room based on the current valid data
 */
def calculateClosestRoom() {
    if (!state.roomDistances || state.roomDistances.isEmpty()) {
        handleNoRoomData()
        return
    }

    // Find room with minimum distance
    def minEntry = state.roomDistances.min { it.value }
    if (!minEntry) {
        handleNoRoomData()
        return
    }
    
    def closestRoom = minEntry.key
    def closestDistance = minEntry.value
    
    logDebug "Closest room: ${closestRoom} at ${closestDistance}m"
    
    // Update state if room changed
    if (closestRoom != state.closestRoom) {
        updateRoomChange(closestRoom, closestDistance)
    } else if (closestDistance != state.closestDistance) {
        state.closestDistance = closestDistance
    }
}

/**
 * Handle case when no room data is available
 */
private void handleNoRoomData() {
    if (state.closestRoom) {
        logInfo 'No room data available'
        state.previousRoom = state.closestRoom
        state.closestRoom = null
        state.closestDistance = null
        state.roomChangedDate = formatDate()
        
        sendEvent(name: 'closestRoom', value: 'none', descriptionText: 'No valid room data available')
        sendEvent(name: 'previousRoom', value: state.previousRoom ?: 'none')
        sendEvent(name: 'roomChangedDate', value: state.roomChangedDate)
        sendEvent(name: 'closestDistance', value: null)
        sendEvent(name: 'presence', value: 'not present')
    }
}

/**
 * Update state when room changes
 */
private void updateRoomChange(String newRoom, Float distance) {
    def previousRoom = state.closestRoom
    state.previousRoom = previousRoom
    state.closestRoom = newRoom
    state.closestDistance = distance
    state.roomChangedDate = formatDate()
    
    logInfo "Room changed: ${previousRoom ?: 'none'} -> ${newRoom} (${distance}m)"
    
    sendEvent(name: 'closestRoom', value: newRoom, descriptionText: "Device is closest to ${newRoom}")
    sendEvent(name: 'previousRoom', value: previousRoom ?: 'none')
    sendEvent(name: 'roomChangedDate', value: state.roomChangedDate)
    sendEvent(name: 'closestDistance', value: distance, unit: 'm')
    sendEvent(name: 'presence', value: 'present')
}

/**
 * Format current date/time
 */
private String formatDate() {
    return new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)
}

/**
 * Logging Helpers
 */
private void logInfo(String msg) {
    log.info msg
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug msg
}
