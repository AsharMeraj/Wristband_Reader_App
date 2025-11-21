package com.example.wristbandreader

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQ = 2001

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.FOREGROUND_SERVICE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple button to start service
        val startButton = Button(this).apply { text = "Start Vitals Scan" }
        setContentView(startButton)

        startButton.setOnClickListener {
            if (hasAllPermissions()) {
                checkBluetoothAndLocation()
            } else {
                requestAllPermissions()
            }
        }
    }

    // -------------------- Permissions --------------------
    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQ) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("MainActivity", "✔ All permissions granted")
                checkBluetoothAndLocation()
            } else {
                Toast.makeText(this, "Permissions are required!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------- Bluetooth & Location --------------------
    private fun checkBluetoothAndLocation() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        "android.permission.BLUETOOTH_CONNECT"
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Request the permission first
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf("android.permission.BLUETOOTH_CONNECT"),
                        500
                    )
                    return
                }
            }

            // Permission granted or not required → enable Bluetooth
            val enableBt = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBt) // User will see the prompt
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }


        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locEnabled) {
            Toast.makeText(this, "Turn on Location", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Safe to start service
        startVitalsService()
    }

    private fun startVitalsService() {
        Log.d("MainActivity", "🔵 Starting VitalsService in foreground...")
        val intent = Intent(this, VitalsService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Vitals Service Started", Toast.LENGTH_SHORT).show()
    }
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
