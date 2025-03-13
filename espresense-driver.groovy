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

        def payload = new groovy.json.JsonSlurper().parseText(message.payload)
        logDebug "Received MQTT message: ${payload}"
        def distance = payload.distance

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

    // Check if we have any timestamps to process
    if (!state.roomTimestamps) {
        return
    }

    // Remove stale entries
    def staleFree = state.roomTimestamps.findAll { room, timestamp ->
        if ((currentTime - timestamp) <= timeout) {
            return true // Keep this entry
        } else {
            dataChanged = true
            logDebug "Room ${room} data is stale and will be removed"
            state.roomDistances.remove(room)
            return false // Remove this entry
        }
    }

    // Update timestamps
    state.roomTimestamps = staleFree

    // If data changed, recalculate closest room
    if (dataChanged) {
        calculateClosestRoom()
    }
}
def calculateClosestRoom() {
    // Get the room with the minimum distance
    def roomDistances = state.roomDistances ?: [:]

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

    def closestEntry = roomDistances.min { room, distance -> distance }
    def closestRoom = closestEntry.key
    def closestDistance = closestEntry.value

    // Update if the closest room has changed
    if (closestRoom != state.closestRoom || closestDistance != state.closestDistance) {
        state.previousRoom = state.closestRoom
        state.closestRoom = closestRoom
        state.closestDistance = closestDistance
        state.roomChangedDate = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)

        logInfo "Closest room changed to: ${closestRoom}"
        logDebug "Updated closest room to: ${closestRoom}, distance: ${closestDistance}m"

        sendEvent(name: 'closestRoom', value: closestRoom, descriptionText: "Device is closest to ${closestRoom}")
        sendEvent(name: 'previousRoom', value: state.previousRoom, descriptionText: "Previously in ${state.previousRoom ?: 'none'}")
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
