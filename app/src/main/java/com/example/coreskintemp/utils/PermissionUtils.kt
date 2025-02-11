package com.example.coreskintemp.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat

object PermissionUtils {

    /**
     * Check if all required Bluetooth permissions are granted.
     */
    fun hasBluetoothPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val fineLocGranted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            return scanGranted && connectGranted && fineLocGranted
        } else {
            // For older versions, only ACCESS_FINE_LOCATION is needed
            val fineLocGranted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fineLocGranted
        }
    }

    /**
     * Request any missing Bluetooth permissions if needed.
     * Pass in a (possibly null) ActivityResultLauncher if you want to handle the results.
     */
    fun requestBluetoothPermissionsIfNeeded(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<Array<String>>?
    ) {
        val neededPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Add location permission if not granted (for older versions or scanning)
        if (ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (neededPermissions.isNotEmpty()) {
            Log.d("Permission", "Requesting permissions: $neededPermissions")
            if (launcher != null) {
                // Use the provided ActivityResultLauncher
                launcher.launch(neededPermissions.toTypedArray())
            } else {
                // Fallback: request permissions without the ActivityResultLauncher
                ActivityCompat.requestPermissions(
                    activity,
                    neededPermissions.toTypedArray(),
                    1234
                )
            }
        } else {
            Log.d("Permission", "All required permissions already granted.")
        }
    }
}
