package com.example.coreskintemp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.coreskintemp.ui.theme.CoreSkinTempTheme
import com.example.coreskintemp.utils.PermissionUtils
import com.example.coreskintemp.bluetooth.BluetoothManagerCore
import java.lang.reflect.Method

class MainActivity : ComponentActivity() {

    // Connection status for UI
    val connectionStatus = mutableStateOf("Not Connected")

    // List of recent temperature readings
    val readingsList = mutableStateOf<List<String>>(emptyList())

    // Toggle temperature display unit
    val isFahrenheit = mutableStateOf(false)

    val fileNameState = mutableStateOf("")     // Bound to a TextField
    val isRecording = mutableStateOf(false)    // Indicates if currently logging to CSV


    //  Bluetooth manager
    private lateinit var bluetoothManagerCore: BluetoothManagerCore

    // Broadcast receiver to ignore system pairing dialogs
    private val pairingRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_PAIRING_REQUEST == intent.action) {
                // Cancel system pairing dialog
                //abortBroadcast()
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    Log.w("PairingRequest", "Rejecting pairing request from ${device.address}")

                }
            }
        }
    }

    /**
     * Activity Result Launcher for requesting multiple permissions
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted) {
                Log.d("Permission", "All required permissions granted.")
            } else {
                Log.e("Permission", "Permissions denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Android's Bluetooth adapter
        val systemBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = systemBluetoothManager.adapter

        // Create custom BluetoothManagerCore
        bluetoothManagerCore = BluetoothManagerCore(
            context = this,
            activity = this,
            bluetoothAdapter = adapter,
            connectionStatus = connectionStatus,
            readingsList = readingsList,
            isFahrenheit = isFahrenheit
        )

        // Register the pairing request broadcast receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        registerReceiver(pairingRequestReceiver, filter)

        // Request necessary BLE permissions (defined in PermissionUtils)
        PermissionUtils.requestBluetoothPermissionsIfNeeded(this, requestPermissionLauncher)

        // Set up the Compose-based UI
        setContent {
            CoreSkinTempTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainUI(
                        connectionStatus = connectionStatus.value,
                        readings = readingsList.value,
                        isFahrenheit = isFahrenheit.value,
                        onConnect = { bluetoothManagerCore.scanForCoreDevices() },
                        onDisconnect = { bluetoothManagerCore.disconnectDevice() },
                        onToggleUnit = { toggleTemperatureUnit() },

                        // New:
                        onPairHrm = { bluetoothManagerCore.startBleHrmScan() },
                        onEnableSkinTemp = { bluetoothManagerCore.scanForSecondCoreDevice() },

                        //csv
                        fileName = fileNameState.value,
                        onFileNameChange = { newName -> fileNameState.value = newName },
                        isRecording = isRecording.value,
                        onStartRecording = { startRecording() },
                        onStopRecording = { stopRecording() }
                    )
                }
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        // Close GATT connection if open
        bluetoothManagerCore.closeConnection()
        // Unregister the pairing receiver
        unregisterReceiver(pairingRequestReceiver)
    }

    /**
     * Toggle the temperature unit between Celsius and Fahrenheit.
     */
    private fun toggleTemperatureUnit(): String {
        isFahrenheit.value = !isFahrenheit.value
        return if (isFahrenheit.value) "Switched to Fahrenheit" else "Switched to Celsius"
    }

    private fun startRecording() {
        val fileName = fileNameState.value
        if (fileName.isBlank()) {
            Log.e("CSV", "File name is empty—cannot start recording.")
            return
        }
        // Tell the BluetoothManagerCore to open the CSV file and set a flag that is loged.
        bluetoothManagerCore.startCsvLogging(fileName)
        isRecording.value = true
    }

    private fun stopRecording() {
        bluetoothManagerCore.stopCsvLogging()
        isRecording.value = false
    }
}

/**
 * UI Composable for displaying the BLE connection status and temperature readings.
 */
@Composable
fun MainUI(
    connectionStatus: String,
    readings: List<String>,
    isFahrenheit: Boolean,
    onConnect: () -> String,
    onDisconnect: () -> String,
    onToggleUnit: () -> String,
    onPairHrm:() -> Unit,
    onEnableSkinTemp: () -> Unit,

    fileName: String,
    onFileNameChange: (String) -> Unit,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var scanStatus by remember { mutableStateOf("Not Connected") }
    var lastAction by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        //CSV file stuff
        OutlinedTextField(
            value = fileName,
            onValueChange = onFileNameChange,
            label = { Text("CSV File Name") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (!isRecording) {
            Button(onClick = onStartRecording) {
                Text(text = "Start Recording CSV")
            }
        } else {
            Button(onClick = onStopRecording) {
                Text(text = "Stop Recording CSV")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))


        // 1) Connect to CORE
        Button(onClick = {
            scanStatus = onConnect()
        }) {
            Text(text = "Connect to CORE")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2) Pair HRM
        Button(onClick = {
            onPairHrm()
        }) {
            Text(text = "Pair HRM")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3) Enable skin temp
        Button(onClick = {
            onEnableSkinTemp()
        }) {
            Text(text = "Enable Skin Temp (2nd Sensor)")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4) Disconnect
        Button(onClick = {
            lastAction = onDisconnect()
        }) {
            Text(text = "Disconnect")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5) Toggle temperature unit
        Button(onClick = {
            lastAction = onToggleUnit()
        }) {
            val label = if (isFahrenheit) "Switch to °C" else "Switch to °F"
            Text(text = label)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Display connection status
        Text(
            text = "Status: $connectionStatus",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Display recent temperature readings
        Text(
            text = "Recent Readings:",
            style = MaterialTheme.typography.bodyMedium
        )

        readings.forEach { reading ->
            Text(
                text = reading,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
