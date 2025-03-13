# ESPresense Device Tracker MQTT Driver for Hubitat

This Hubitat driver integrates with [ESPresense](https://github.com/ESPresense/ESPresense) to track device locations within your home based on Bluetooth Low Energy (BLE) signal strength reported by ESPresense nodes.

## Overview

The ESPresense Device Tracker MQTT Driver connects to your MQTT broker to monitor device proximity data published by ESPresense nodes. It determines which room your device is closest to based on signal strength/distance measurements and provides this information as attributes for use in Hubitat automations.

## Features

- Tracks the closest room for a specified device
- Maintains history of previous room
- Records timestamp of room changes
- Implements 15-second timeout for room data to ensure accuracy
- Configurable settings for MQTT broker and tracking parameters

## Requirements

- [Hubitat Elevation Hub](https://hubitat.com/)
- ESPresense nodes configured and installed in your home
- MQTT broker (like Mosquitto, HiveMQ, etc.)
- Devices with Bluetooth for tracking (phones, watches, tags, etc.)

## Installation

### Option 1: Hubitat Package Manager (HPM)

1. Install the [Hubitat Package Manager](https://github.com/dcmeglio/hubitat-packagemanager) if you haven't already
2. Click on "Package Manager" in your Hubitat Apps
3. Click "Install" and select "From a URL"
4. Enter the following URL:
   ```
   https://raw.githubusercontent.com/timothydodd/hubitat-espresense-mqtt/main/packageManifest.json
   ```
5. Click "Next" and follow the installation instructions

### Option 2: Manual Installation

1. Go to your Hubitat hub's admin page
2. Go to "Developer Tools" → "Drivers Code"
3. Click "New Driver"
4. Paste the contents of the `espresense-mqtt-driver.groovy` file
5. Click "Save"

## Setup and Configuration

1. Create a new virtual device:
   - Go to "Devices" on your Hubitat
   - Click "Add Virtual Device"
   - Give it a name (e.g., "Tim's Phone Location")
   - Select "ESPresense Device Tracker MQTT Driver" as the Type
   - Click "Save Device"

2. Configure the device:
   - Click on the newly created device
   - Enter your MQTT Broker details (e.g., `192.168.1.10:1883`)
   - Set the MQTT Topic Base (typically `espresense/devices`)
   - Enter the Device ID to track (e.g., `phone:timsiphone`)
   - Optionally adjust the data timeout (default: 15 seconds)
   - Click "Save Preferences"

## Device Attributes

The driver provides the following attributes:

- **closestRoom**: The room the device is currently closest to
- **previousRoom**: The last room the device was in before the current one
- **roomChangedDate**: Timestamp when the room last changed

## How It Works

1. The driver subscribes to MQTT topics matching the pattern `espresense/devices/[device-id]/#`
2. ESPresense nodes publish distance measurements to these topics
3. The driver tracks the distance from each room's ESPresense node
4. The room with the smallest distance value is considered the "closest room"
5. Room data older than 15 seconds is considered stale and removed from calculations
6. When all data is stale, the closest room is set to "none"

## Using in Automations

You can use the device's attributes in Hubitat Rules, Rule Machine, or other apps:

- Turn on lights when entering a room
- Set thermostat based on room occupancy
- Play music in the room you're in
- Trigger notifications when entering or leaving specific areas

Example Rule: "When closestRoom changes to Kitchen, turn on kitchen lights"

## Troubleshooting

- **No room data appears**: Verify your MQTT broker connection and topic settings
- **Inaccurate room detection**: Adjust placement of ESPresense nodes for better coverage
- **Frequent room changes**: Increase the timeout value to reduce sensitivity to temporary fluctuations

## Version History

- **1.1** (2025-03-13): Added 15-second timeout for room data to improve tracking reliability
- **1.0** (2025-01-18): Initial release

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Credits

- Developed by Tim Dodd
- Inspired by and designed to work with [ESPresense](https://github.com/ESPresense/ESPresense)