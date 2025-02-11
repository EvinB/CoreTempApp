# CORE & HR Sensor Integration App

This Kotlin/Android app connects to a **CORE body temperature sensor** and a **Polar Verity Sense HR monitor** via Bluetooth, log their data (1 Hz)

---

## 1) Overview

- **Purpose**:  
  Capture Heart Rate, Core Temperature, Skin Temperature(body & arm) during trials 

- **Sensors**:
    1. **CORE Temperature Sensor x2** 
    2. **Polar Verity Sense** 

- **Platform**:
    - **Android** (Kotlin).

- **Sampling Rate**:
    - ~1 sample per second from each sensor (temps + HR).

---

## 2) Project Structure

Below is an overview of the main files: 

1. **MainActivity.kt**
    - Sets up the Compose UI.
    - Contains the main buttons: “Connect CORE,” “Pair HRM,” “Enable Skin Temp,” “Start/Stop CSV Logging,”
    - Displays connection status and current readings (UI is still being updated)

2. **BluetoothManagerCore.kt**
    - The central manager for scanning, connecting, and controlling the CORE sensors.
    - Also handles CSV logging of sensor data.

3. **GattCallbacks.kt**
    - The `BluetoothGattCallback` for the **first/ Primary** CORE sensor.
    - Handles service/characteristic discovery, notifications, and data parsing (core temp, skin temp, HR, etc.).
    - Updates Compose states and calls methods in **BluetoothManagerCore** for logging.

4. **BluetoothGattSecond.kt**
    - A second callback if you connect a **second** CORE sensor.
    - Similar to `GattCallbacks`, but for a separate device.

5. **PermissionUtils.kt**
    - Checks and requests Bluetooth permissions (Android 12+ requires BLUETOOTH_CONNECT, BLUETOOTH_SCAN, etc.).

## 4) Usage Instructions

1. **Launch the app** on your Android device.
2. **Connect to CORE**
    - Tap “Connect to CORE” to scan for the CORE sensor.
    - Once discovered, it will connect automatically and begin receiving data.
3. **Pair HRM** (Only needed if HRM does not connect automatically after pairing core device)
    - Tap “Pair HRM” if you also want to add a Polar HR monitor connection via the CORE sensor’s control point.
    - The app writes opcodes to scan for HRMs, then logs the data.
4. **Enable Skin Temp** (optional will be used to connect to the sensor placed on users arm)
5. **Enter a filename** in the CSV text field (e.g., `session1`).
6. **Press "Start Recording CSV"** to begin logging.
    - The CSV file is saved to `Android/data/com.example.coreskintemp/files/<filename>.csv` on the device.
7. **Stop Recording**
    - Press “Stop Recording CSV” to finalize and close the file.  


## 5) General Notes
The following is currently being worked on: 
- **Refactoring** some code into separate modules for clarity.
- **Adding** more error handling for BLE state changes, timeouts, and retries.
- **Updating** UI to display readings for both sensors and save data as two CSV files.
