package com.example.coreskintemp

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.coreskintemp.bluetooth.BluetoothManagerCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*


/**
 * This class handles GATT events. After the core devices connect and send data the callbacks are used
 *
 */
class GattCallbacks(
    private val bluetoothManagerCore: BluetoothManagerCore,
    private val connectionStatus: MutableState<String>,
    private val readingsList: MutableState<List<String>>,
    private val isFahrenheit: MutableState<Boolean>,
    private val onGattClosed: () -> Unit
) : BluetoothGattCallback() {

    /**
     *  store relevant UUIDs for the service and characteristics used by the CORE sensor:
     *  - serviceUuid (0x2100)
     *  - temperatureUuid (0x2101)
     *  - controlPointUuid (0x2102) used for commands like scanning for HRM, etc.
     *  - cccdUuid (0x2902) used to enable notifications or indications.
     */

    private val serviceUuid = UUID.fromString("00002100-5B1E-4347-B07C-97B514DAE121")
    private val temperatureUuid = UUID.fromString("00002101-5B1E-4347-B07C-97B514DAE121")
    private val controlPointUuid = UUID.fromString("00002102-5B1E-4347-B07C-97B514DAE121") //CCC UUID
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") //enable indicators

    /**
     * Called when the connection state to the GATT server changes.
     *  - If connected, proceed to discover services.
     *  - If disconnected, close and clean up references.
     */
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        when (newState) {
            BluetoothGatt.STATE_CONNECTED -> {
                Log.d("BluetoothGatt", "Connected to GATT server.")
                gatt?.discoverServices()
                connectionStatus.value = "Connected successfully"
            }
            BluetoothGatt.STATE_DISCONNECTED -> {
                Log.e("BluetoothGatt", "Disconnected from GATT server.")
                gatt?.close()
                onGattClosed()
                connectionStatus.value = "Disconnected"
            }
        }
    }

    /**
     * Called when service discovery completes. If successful, grab the service
     * and its characteristics (tempChar, controlPointChar) to set up notifications.
     */
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            gatt?.let { safeGatt ->
                val service = safeGatt.getService(serviceUuid)

                // get temperature characteristic
                val tempChar = service?.getCharacteristic(temperatureUuid)

                if (tempChar != null) {
                    enableTemperatureNotifications(safeGatt, tempChar)
                }

                // get the Control-Point characteristic
                val controlPointChar = service?.getCharacteristic(controlPointUuid)
                if (controlPointChar != null) {

                    // check payload
                    val props = controlPointChar.properties
                    val hasIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    val hasWrite    = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    val hasWriteRsp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    Log.d("BluetoothGatt", "Control Point props=0x${props.toString(16)} " +
                            "hasIndicate=$hasIndicate, hasWrite=$hasWrite, hasWriteNoResp=$hasWriteRsp")


                } else {
                    Log.e("BluetoothGatt", "Control‐Point characteristic not found!")
                }
            }
        } else {
            Log.e("BluetoothGatt", "Service discovery failed with status: $status")
        }
    }

    /**
     * Called whenever a characteristic notification/indication arrives from the sensor.
     * This is where the data is parsed from both the control point (0x2102) and temperature (0x2101).
     */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        if (characteristic?.uuid == controlPointUuid) {
            val data = characteristic.value

            if (data.isNotEmpty()) {
                // Typically the first byte is 0x80 => "Response OpCode"
                val responseOpCode = data[0].toInt() and 0xFF
                if (responseOpCode == 0x80) {
                    // The second byte is the Request OpCode
                    val requestOpCode = data[1].toInt() and 0xFF
                    // The third byte is the "Result Code"
                    val resultCode = data[2].toInt() and 0xFF

                    when (requestOpCode) {

                        // 1) "Get Total BLE HRMs" => 0x0E
                        0x0E -> {
                            if (resultCode == 0x01) {
                                val total = (data.getOrNull(3)?.toInt() ?: 0) and 0xFF
                                bluetoothManagerCore.onHrmCountUpdated(total)
                                Log.d("ControlPoint", "GetTotalBleHrms => total=$total")

                                // If exactly 1 HRM is foundnautomatically get the MAC at index=0
                                if (total == 1) {
                                    gatt?.let { safeGatt ->
                                        writeGetBleHrmMac(safeGatt, 0)
                                    }
                                }
                            } else {
                                Log.e("ControlPoint",
                                    "GetTotalBleHrms => resultCode=0x${resultCode.toString(16)} (Error or not supported)"
                                )
                            }
                        }

                        // 2) "Scan for BLE HRMs" => 0x0D
                        0x0D -> {
                            if (resultCode == 0x01) {
                                // Scanning started successfully
                                //manually call getTotalBleHrms one time
                                gatt?.let { safeGatt ->
                                    writeGetTotalBleHrms(safeGatt)
                                }
                            }
                        }

                        // 3) get hrm mac add => 0x10
                        0x10 -> {
                            if (resultCode == 0x01) {
                                // Must have at least 4+6 bytes to hold the MAC
                                if (data.size >= 3 + 6) {
                                    val macBytes = data.copyOfRange(3, 3+6)
                                    val macStr = macBytes.joinToString(":") { "%02X".format(it) }
                                    Log.d("ControlPoint", "Got BLE HRM MAC => $macStr")

                                    // add HRM automatically
                                    gatt?.let { safeGatt ->
                                        writeAddBleHrm(safeGatt, macBytes)
                                    }
                                } else {
                                    Log.e("ControlPoint", "Response for 0x10 is too short to contain the MAC!")
                                }
                            } else {
                                Log.e("ControlPoint",
                                    "GetBleHrmMac => resultCode=0x${resultCode.toString(16)} (Error or not supported)"
                                )
                            }
                        }

                        // 4) "Add BLE HRM" => 0x06
                        0x06 -> {
                            if (resultCode == 0x01) {
                                Log.d("ControlPoint", "Successfully added BLE HRM to the list! &&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&")
                                // The CORE sensor will internally connect to that HRM now
                            } else {
                                Log.e("ControlPoint", "Add BLE HRM => error code=0x${resultCode.toString(16)}")
                            }
                        }

                        else -> {
                            Log.d("ControlPoint", "Unhandled requestOpCode=0x${requestOpCode.toString(16)}, resultCode=0x${resultCode.toString(16)}")
                        }
                    }

                    // Log them
                    Log.d(
                        "ControlPoint",
                        "CP Indication => responseOpCode=0x${responseOpCode.toString(16)}, " +
                                "requestOpCode=0x${requestOpCode.toString(16)}, " +
                                "resultCode=0x${resultCode.toString(16)}"
                    )


                } else {
                    Log.w("ControlPoint",
                        "Unexpected responseOpCode=0x${responseOpCode.toString(16)} in CP indication.")
                }
            }
        }
        else if (characteristic?.uuid == temperatureUuid) {
            val rawData = characteristic.value
            parseCoreBodyTemp(rawData)
        }
    }



     //Enable notifications for the temperature characteristic.

    fun enableTemperatureNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccDescriptor = characteristic.getDescriptor(cccdUuid)
        cccDescriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        // Attempt the descriptor write
        gatt.writeDescriptor(cccDescriptor)
    }

    //Enable Control Point Characteristics
    private fun enableControlPointIndications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ){
        //enable at gatt level
        gatt.setCharacteristicNotification(characteristic, true)
        val cccDescriptor = characteristic.getDescriptor(cccdUuid)
        if (cccDescriptor != null) {
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

            // Log what is being written so you can see the byte format
            val cccdValueHex = cccDescriptor.value.joinToString(" ") { "%02X".format(it) }
            Log.d("BluetoothGatt", "Descriptor value = $cccdValueHex")
            Log.d("BluetoothGatt", "Writing CCC Descriptor for Control Point, value = $cccdValueHex")

            val success = gatt.writeDescriptor(cccDescriptor)
            if (success) {
                Log.d("BluetoothGatt", "Enabling indications on Control Point characteristic...")
            } else {
                Log.e("BluetoothGatt", "Failed to write CCC descriptor for Control Point!")
            }
        } else {
            Log.e("BluetoothGatt", "CCCD descriptor not found for Control Point characteristic!")
        }
    }

    private fun celsiusToFahrenheit(celsius: Double): Double {
        return celsius * 9.0 / 5.0 + 32.0
    }


    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        super.onDescriptorWrite(gatt, descriptor, status)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (descriptor.characteristic.uuid == temperatureUuid) {
                // Next, do the control point
                val ctrlChar = gatt.getService(serviceUuid)?.getCharacteristic(controlPointUuid)
                if (ctrlChar != null) {
                    enableControlPointIndications(gatt, ctrlChar)
                }
            } else if (descriptor.characteristic.uuid == controlPointUuid) {
                Log.d("GattCallbacks", "Control Point CCC enabled!")
                //  automatically start scanning for BLE HRMs
                writeScanForBleHrms(gatt)
            }
        } else {
            Log.e("GattCallbacks", "Descriptor write failed with status=$status")
        }
    }

    //scan for BT HRM
    fun writeScanForBleHrms(gatt: BluetoothGatt) {
        // 1) Get the Control Point characteristic
        val cpCharacteristic = gatt.getService(serviceUuid)?.getCharacteristic(controlPointUuid)
        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found!")
            return
        }

        // 2)
        //    0xFF -> “Start scan and invalidate old list”
        //    0xFE -> “Start scan and do proximity pairing,” etc.
        val opcode = byteArrayOf(0x0D, 0xFF.toByte())
        cpCharacteristic.value = opcode

        // 3) Write with response
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        // 4) Write to the characteristic
        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "writeCharacteristic(0x0D) returned: $success")
    }


    fun writeGetTotalBleHrms(gatt: BluetoothGatt) {
        // 1) Check if GATT is null
        if (gatt == null) {
            Log.e("ControlPoint", "Gatt is null, cannot write!")
            return
        }

        // 2) Get the characteristic
        val cpCharacteristic = gatt
            .getService(serviceUuid)
            ?.getCharacteristic(controlPointUuid)

        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found!")
            return
        }

        // 3) Write the 0x0E opcode
        val opcode = byteArrayOf(0x0E)
        cpCharacteristic.value = opcode
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "writeTotalBleHrms => returned: $success")
    }


    /** Pair the HRM HERE %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%**/


    /**
     * Writes "Get BLE HRM MAC" (Op Code 0x10) for the scanned device at `index`.
     * The device will respond with an indication that includes the 6-byte MAC in the payload.
     */
    fun writeGetBleHrmMac(gatt: BluetoothGatt, index: Int) {
        val cpCharacteristic = gatt.getService(serviceUuid)?.getCharacteristic(controlPointUuid)
        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found for GetBleHrmMac!")
            return
        }

        // e.g. 0x10, [1 byte param = index]
        val opcode = byteArrayOf(0x10, index.toByte())

        cpCharacteristic.value = opcode
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "writeGetBleHrmMac => returned: $success (index=$index)")
    }

    /**
     * Writes "Add BLE HRM to list" (Op Code 0x06) using the 6-byte MAC you retrieved.
     */
    fun writeAddBleHrm(gatt: BluetoothGatt, hrmMac: ByteArray) {
        if (hrmMac.size != 6) {
            Log.e("ControlPoint", "Invalid MAC address length: ${hrmMac.size}")
            return
        }

        val cpCharacteristic = gatt.getService(serviceUuid)?.getCharacteristic(controlPointUuid)
        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found for AddBleHrm!")
            return
        }

        // 0x06 + the 6 bytes of MAC
        val opcode = ByteArray(1 + 6)
        opcode[0] = 0x06
        System.arraycopy(hrmMac, 0, opcode, 1, 6) // copy the 6 MAC bytes after the opcode

        cpCharacteristic.value = opcode
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "writeAddBleHrm => returned: $success, MAC=${hrmMac.joinToString(":") { "%02X".format(it) }}")
    }


    /**
     * Parsing attempts below
     */

//   bit0 => CORE (2 bytes)
//   bit1 => SKIN (2 bytes)
//   bit2 => QUALITY (1 byte)
//   bit4 => HR (1 byte)
//   bit5 => HSI (1 byte)
//   bit3 => if set => measure is in Fahrenheit

    fun parseCoreBodyTemp(rawData: ByteArray) {
        if (rawData.isEmpty()) return
        val flags = rawData[0].toInt() and 0xFF
        var offset = 1

        // 1) Core Body Temp is always next 2 bytes
        val coreRaw = ByteBuffer.wrap(rawData, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short
        offset += 2
        var coreC = coreRaw / 100.0

        // 2) If bit0 => read Skin (2 bytes)
        var skinC: Double? = null
        if ((flags and 0x01) != 0) {
            val skinRaw = ByteBuffer.wrap(rawData, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short
            offset += 2
            skinC = skinRaw / 100.0
        }

        // 3) If bit1 => read “Core Reserved” (2 bytes)
        var coreReserved: Int? = null
        if ((flags and 0x02) != 0) {
            // 2 more bytes
            coreReserved = ByteBuffer.wrap(rawData, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            offset += 2
        }

        // 4) If bit2 => read Quality/State (1 byte)
        var qualityAndState: Int? = null
        if ((flags and 0x04) != 0) {
            qualityAndState = (rawData[offset].toInt() and 0xFF)
            offset += 1
        }

        // 5) If bit4 => read HeartRate (1 byte)
        var hr: Int? = null
        if ((flags and 0x10) != 0) {
            hr = rawData[offset].toInt() and 0xFF
            offset += 1
        }

        // 6) If bit5 => read Heat Strain Index (1 byte)
        var hsi: Int? = null
        if ((flags and 0x20) != 0) {
            hsi = rawData[offset].toInt() and 0xFF
            offset += 1
        }

        // 7) If bit3 => entire measure was in Fahrenheit => convert
        val isF = ((flags and 0x08) != 0)
        if (isF) {
            coreC = fToC(coreC)
            skinC = skinC?.let { fToC(it) }
        }


        val showCore = if (isFahrenheit.value) celsiusToFahrenheit(coreC) else coreC
        val showSkin = skinC?.let {
            if (isFahrenheit.value) celsiusToFahrenheit(it) else it
        }

        val coreUnit = if (isFahrenheit.value) "°F" else "°C"
        val skinUnit = coreUnit

        // Build a single display line with all the fields 
    
        val display = StringBuilder().apply {
            append("Core: %.2f %s".format(showCore, coreUnit))
            if (showSkin != null) {
                append(", Skin: %.2f %s".format(showSkin, skinUnit))
            }
            if (hr != null) {
                append(", HR: $hr")
            }
        }.toString()

        // Then append to readingsList
        readingsList.value = (readingsList.value + display).takeLast(8)

        Log.d("Parser", "flags=0x${flags.toString(16)} => core=$coreC, skin=$skinC, coreRes=$coreReserved, quality=$qualityAndState, hr=$hr, hsi=$hsi")

        val timeMs = System.currentTimeMillis()

        bluetoothManagerCore.appendCsvLine(
            timeMs = timeMs,
            coreTemp = coreC,
            skinTemp = skinC,
            coreRes = coreReserved,
            quality = qualityAndState,
            hr = hr,
            hsi = hsi
        )
    }

    private fun fToC(fVal: Double): Double {
        return (fVal - 32.0) * 5.0 / 9.0
    }


    //(&*^(*#&^$(*&^#$(*&^#($*&^#$(*&^#$*(&^#(*$&^(#*&^$(*#&^$(*#&^$(*&#$^(*#&^$(*&#^$*(&#^$(*&#^$

}
