package com.example.coreskintemp.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.MutableState
import com.example.coreskintemp.GattCallbacks
import com.example.coreskintemp.utils.PermissionUtils
import java.io.File
import java.io.FileWriter
import java.util.*

/**
 * This class uses one main GATT for the first sensor (bluetoothGatt) and an optional second GATT
 * for a second sensor (bluetoothGattSecond). It also sets up a single GattCallbacks instance
 * for the first sensor and a BluetoothGattSecond callback instance for the second sensor.
 */
class BluetoothManagerCore(
    private val context: Context,
    private val activity: ComponentActivity,
    val bluetoothAdapter: BluetoothAdapter?,
    val connectionStatus: MutableState<String>,
    val readingsList: MutableState<List<String>>,
    val isFahrenheit: MutableState<Boolean>
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattSecond: BluetoothGatt? = null
    private lateinit var secondGattCallback: BluetoothGattSecond

    //CSV logging fields
    private var csvFile: File? = null
    private var csvWriter: FileWriter? = null
    private var isLogging = false

    // GattCallbacks instance. Pass references so it can update states.
    private val gattCallback = GattCallbacks(
        bluetoothManagerCore = this,
        connectionStatus = connectionStatus,
        readingsList = readingsList,
        isFahrenheit = isFahrenheit,
        onGattClosed = {
            bluetoothGatt = null
        }
    )


    // Custom service and characteristic UUIDs
    private val serviceUuid = UUID.fromString("00002100-5B1E-4347-B07C-97B514DAE121")
    private val temperatureUuid = UUID.fromString("00002101-5B1E-4347-B07C-97B514DAE121")

    /**
     * Initiate a BLE scan for devices advertising the CORE service UUID.
     */
    fun scanForCoreDevices(): String {
        // Check permissions just in case
        if (!PermissionUtils.hasBluetoothPermissions(context)) {
            Log.e("Bluetooth", "Missing required Bluetooth permissions. Requesting now...")
            PermissionUtils.requestBluetoothPermissionsIfNeeded(activity, null)
            return "Requesting permissions..."
        }

        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e("Bluetooth", "BluetoothLeScanner is null. Is Bluetooth disabled?")
            return "Scanner not available"
        }

        // Define scan filter for devices advertising the service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        // Define scan settings for low-latency mode
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Start scanning
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Stop scanning automatically after 30 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            bluetoothLeScanner.stopScan(scanCallback)
            Log.d("Bluetooth", "Scan stopped after timeout.")
        }, 30_000)

        return "Scanning for devices..."
    }

    /**
     * Callback used for scanning results.
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device
            Log.d("Bluetooth", "Discovered device: ${device?.name} (${device?.address})")

            if (device?.name?.startsWith("CORE") == true) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(device)
                //startBleHrmScan()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("Bluetooth", "Scan failed with error code $errorCode")
        }
    }

    /**
     * Connect to a BLE device without bonding.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("Bluetooth", "Missing BLUETOOTH_CONNECT permission")
                PermissionUtils.requestBluetoothPermissionsIfNeeded(activity, null)
                return
            }
        }

        Log.d("Bluetooth", "Connecting to device: ${device.name} (${device.address})")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Disconnect from the connected BLE device.
     */
    fun disconnectDevice(): String {
        bluetoothGatt?.disconnect()
        return "Disconnect requested"
    }

    /**
     * Close the GATT connection if open.
     */
    fun closeConnection() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }


    fun startBleHrmScan(): String {
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e("BluetoothManagerCore", "startBleHrmScan: Not connected to CORE yet!")
            return "Not connected to CORE"
        }

        val cpCharacteristic = gatt
            .getService(serviceUuid)
            ?.getCharacteristic(UUID.fromString("00002102-5B1E-4347-B07C-97B514DAE121"))

        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found in manager!")
            return "Control Point char not found"
        }

        // Write Op Code [0x0D, 0xFF] to start scanning
        val opcode = byteArrayOf(0x0D, 0xFF.toByte())
        cpCharacteristic.value = opcode
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "startBleHrmScan -> writeCharacteristic(0x0D) returned: $success")

        if (success) {
            // Wait ~2 seconds before the first 0x0E request
            Handler(Looper.getMainLooper()).postDelayed({
                // Now do a single 0x0E request
                writeGetTotalBleHrms()


                startPollingForHrmCount()
            }, 2000)
        }

        return if (success) "Scanning for BLE HRMs..." else "WriteCharacteristic failed"
    }




    //+++++++++++++++++++++++++++++++_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_+_



    private val handler = Handler(Looper.getMainLooper())
    private var oldCount = -1
    private var unchangedCycles = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            writeGetTotalBleHrms()
            handler.postDelayed(this, 1000)
        }
    }

    fun startPollingForHrmCount() {
        oldCount = -1
        unchangedCycles = 0
        handler.post(pollRunnable)
    }

    fun stopPollingForHrmCount() {
        handler.removeCallbacks(pollRunnable)
        Log.d("BluetoothManagerCore", "stopPollingForHrmCount() called.")
    }

    /**
     * Write the 0x0E opcode => "Get total BLE HRMs" to the control point characteristic (0x2102).
     */
    fun writeGetTotalBleHrms() {
        val gatt = bluetoothGatt ?: return
        val serviceUuid = UUID.fromString("00002100-5B1E-4347-B07C-97B514DAE121")
        val controlPointUuid = UUID.fromString("00002102-5B1E-4347-B07C-97B514DAE121")

        val cpCharacteristic = gatt
            .getService(serviceUuid)
            ?.getCharacteristic(controlPointUuid)

        if (cpCharacteristic == null) {
            Log.e("ControlPoint", "Control Point characteristic not found!")
            return
        }

        // Get total BLE HRMs
        val opcode = byteArrayOf(0x0E)
        cpCharacteristic.value = opcode
        cpCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(cpCharacteristic)
        Log.d("ControlPoint", "writeGetTotalBleHrms -> writeCharacteristic(0x0E) returned: $success")


    }


    // parse requestOpCode == 0x0E 
    fun onHrmCountUpdated(total: Int) {
        if (total == oldCount) {
            unchangedCycles++
            if (unchangedCycles >= 2) {
                stopPollingForHrmCount()
                Log.d("BluetoothManagerCore", "Scan complete, total BLE HRMs=$total")
            }
        } else {
            oldCount = total
            unchangedCycles = 0
        }
    }


    //CSV ****************************************()*)(*_)(*_)(*_)(*_)(*_)(*_)*(

    fun startCsvLogging(fileName: String) {
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Log.e("CSV", "getExternalFilesDir returned null.")
                return
            }

            csvFile = File(dir, "$fileName.csv")
            // If the file did not exist write header
            val isNewFile = !csvFile!!.exists()

            // Open in append mode
            csvWriter = FileWriter(csvFile, true)

            if (isNewFile) {
                // Write CSV header
                csvWriter?.appendLine("time,core_temp,skin_temp,core_res,quality,hr,hsi")
                csvWriter?.flush()
            }
            isLogging = true
            Log.d("CSV", "CSV logging started at: ${csvFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("CSV", "Error starting CSV logging: ${e.message}")
        }
    }

    fun stopCsvLogging() {
        try {
            csvWriter?.flush()
            csvWriter?.close()
        } catch (e: Exception) {
            Log.e("CSV", "Error closing CSV file: ${e.message}")
        } finally {
            isLogging = false
            csvWriter = null
            csvFile = null
            Log.d("CSV", "CSV logging stopped.")
        }
    }

    /**
     * timeMs => the timestamp
     * coreTemp, skinTemp => doubles or null
     * coreRes => "core reserved" data, if present
     * quality => the sensor's quality/state
     * hr => heart rate
     * hsi => heat strain index
     */
    fun appendCsvLine(
        timeMs: Long,
        coreTemp: Double?,
        skinTemp: Double?,
        coreRes: Int?,
        quality: Int?,
        hr: Int?,
        hsi: Int?
    ) {
        if (!isLogging || csvWriter == null) return

        // Build a single CSV row: time,core,skin,coreRes,quality,hr,hsi
        val line = StringBuilder().apply {
            append(timeMs)
            append(",")
            append(coreTemp?.toString() ?: "")
            append(",")
            append(skinTemp?.toString() ?: "")
            append(",")
            append(coreRes?.toString() ?: "")
            append(",")
            append(quality?.toString() ?: "")
            append(",")
            append(hr?.toString() ?: "")
            append(",")
            append(hsi?.toString() ?: "")
        }.toString()

        try {
            csvWriter?.appendLine(line)
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e("CSV", "Failed to append to CSV: ${e.message}")
        }
    }

    //)(*&)(*&)(*&)(*&)(*&)(*&)(*&)(*&)(*&)(*&)(*&)(*&*&^(&*%^*&^%*&^$&^#$%&%$*^%(%^
    fun scanForSecondCoreDevice(): String {
        if (!PermissionUtils.hasBluetoothPermissions(context)) {
            PermissionUtils.requestBluetoothPermissionsIfNeeded(activity, null)
            return "Requesting permissions..."
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return "Scanner not available"

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, secondCoreScanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(secondCoreScanCallback)
        }, 30_000)

        return "Scanning for 2nd CORE..."
    }

    private val secondCoreScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return

            // If device is the same as first sensor, skip it:
            val firstGattAddress = bluetoothGatt?.device?.address
            if (device.address == firstGattAddress) {
                return 
            }

            //  connect to it as the second sensor:
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            connectSecondCoreGatt(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("SecondCoreScan", "Scan failed: $errorCode")
        }
    }

    private fun connectSecondCoreGatt(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                PermissionUtils.requestBluetoothPermissionsIfNeeded(activity, null)
                return
            }
        }
        secondGattCallback = BluetoothGattSecond(
            bluetoothManagerCore = this,
            connectionStatus2 = connectionStatus, 
            readingsList2 = readingsList,         
            isFahrenheit = isFahrenheit
        )

        Log.d("SecondCore", "Connecting to second CORE: ${device.address}")

        // 2) Use callback
        bluetoothGattSecond = device.connectGatt(
            context,
            false,
            secondGattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }


}
