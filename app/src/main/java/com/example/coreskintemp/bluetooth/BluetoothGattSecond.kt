package com.example.coreskintemp.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.coreskintemp.GattCallbacks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * A separate GATT callback for handling the second CORE sensor.
 *
 * @param bluetoothManagerCore If you want to call back to the same manager for CSV logging, etc.
 * @param connectionStatus2 A separate mutable state to show connection status for the second sensor.
 * @param readingsList2 A separate list of readings for the second sensor 
 * @param isFahrenheit If you want to reuse the same temperature unit toggle as your first sensor.
 */
class BluetoothGattSecond(
    private val bluetoothManagerCore: BluetoothManagerCore,
    private val connectionStatus2: MutableState<String>,
    private val readingsList2: MutableState<List<String>>,
    private val isFahrenheit: MutableState<Boolean>
) : BluetoothGattCallback() {

    // Reuse the same service/characteristic UUIDs as the first CORE sensor
    private val serviceUuid = UUID.fromString("00002100-5B1E-4347-B07C-97B514DAE121")
    private val temperatureUuid = UUID.fromString("00002101-5B1E-4347-B07C-97B514DAE121")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothGatt.STATE_CONNECTED -> {
                Log.d("SecondCore", "Second sensor connected. Discovering services...")
                connectionStatus2.value = "2nd Sensor: Connected"
                gatt.discoverServices()
            }
            BluetoothGatt.STATE_DISCONNECTED -> {
                Log.e("SecondCore", "Second sensor disconnected.")
                gatt.close()
                connectionStatus2.value = "2nd Sensor: Disconnected"
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                Log.e("SecondCore", "Service not found on second device!")
                return
            }

            // Grab the same temperature characteristic (0x2101)
            val tempChar = service.getCharacteristic(temperatureUuid)
            if (tempChar != null) {
                enableTemperatureNotificationsSecond(gatt, tempChar)
            } else {
                Log.e("SecondCore", "Temp char not found on second device!")
            }
        } else {
            Log.e("SecondCore", "Service discovery failed with status=$status on second sensor.")
        }
    }

    private fun enableTemperatureNotificationsSecond(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(cccdUuid)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = gatt.writeDescriptor(cccd)
            Log.d("SecondCore", "Writing CCC descriptor to second sensor => $ok")
        } else {
            Log.e("SecondCore", "CCCD descriptor not found in second sensor’s temp char!")
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.uuid == temperatureUuid) {
            val rawData = characteristic.value
            parseSecondCoreTemp(rawData)
        }
    }

    /**
     * Parse the 0x2101 payload from the second sensor
     */
    private fun parseSecondCoreTemp(rawData: ByteArray) {
        if (rawData.isEmpty()) return

        val flags = rawData[0].toInt() and 0xFF
        var offset = 1

        val coreRaw = ByteBuffer.wrap(rawData, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
        offset += 2
        var coreC = coreRaw / 100.0

        var skinC: Double? = null
        if ((flags and 0x01) != 0) {
            val skinRaw = ByteBuffer.wrap(rawData, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
            offset += 2
            skinC = skinRaw / 100.0
        }

        val coreReserved: Int? = if ((flags and 0x02) != 0) {
            val temp = ByteBuffer.wrap(rawData, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            offset += 2
            temp
        } else null

        val qualityAndState: Int? = if ((flags and 0x04) != 0) {
            val q = (rawData[offset].toInt() and 0xFF)
            offset++
            q
        } else null

        val hr: Int? = if ((flags and 0x10) != 0) {
            val h = (rawData[offset].toInt() and 0xFF)
            offset++
            h
        } else null

        val hsi: Int? = if ((flags and 0x20) != 0) {
            val s = (rawData[offset].toInt() and 0xFF)
            offset++
            s
        } else null

        val isF = ((flags and 0x08) != 0)
        if (isF) {
            coreC = fToC(coreC)
            skinC = skinC?.let { fToC(it) }
        }

        val showCore = if (isFahrenheit.value) cToF(coreC) else coreC
        val showSkin = skinC?.let { if (isFahrenheit.value) cToF(it) else it }

        val unit = if (isFahrenheit.value) "°F" else "°C"
        val displayString = buildString {
            append("(2nd) Core: %.2f %s".format(showCore, unit))
            if (showSkin != null) {
                append(", Skin: %.2f %s".format(showSkin, unit))
            }
            if (hr != null) {
                append(", HR: $hr")
            }
        }

        // Update second sensor’s UI list
      //  readingsList2.value = (readingsList2.value + displayString).takeLast(8)
        //***************NOT DONE YET ************
        Log.d("SecondCoreParser", "Second sensor => core=$coreC, skin=$skinC, hr=$hr, hsi=$hsi")

    }

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0
    private fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0
}
