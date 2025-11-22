package com.example.wristbandreader

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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

    private lateinit var textHeartRate: TextView
    private lateinit var textSpO2: TextView
    private lateinit var textTemp: TextView
    private lateinit var buttonConnect: Button
    private lateinit var buttonDisconnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        textHeartRate = TextView(this).apply { text = "Heart Rate: --"; textSize = 20f }
        textSpO2 = TextView(this).apply { text = "SpO2: --"; textSize = 20f }
        textTemp = TextView(this).apply { text = "Temperature: --"; textSize = 20f }

        buttonConnect = Button(this).apply { text = "Connect to Wristband" }
        buttonDisconnect = Button(this).apply { text = "Disconnect" }

        layout.addView(textHeartRate)
        layout.addView(textSpO2)
        layout.addView(textTemp)
        layout.addView(buttonConnect)
        layout.addView(buttonDisconnect)

        setContentView(layout)

        buttonConnect.setOnClickListener {
            if (hasAllPermissions()) checkBluetoothAndLocation()
            else requestAllPermissions()
        }

        buttonDisconnect.setOnClickListener {
            disconnectWristband()
        }
    }

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestAllPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBluetoothAndLocation()
            } else Toast.makeText(this, "Permissions are required!", Toast.LENGTH_LONG).show()
        }
    }
    @SuppressLint("MissingPermission")
    private fun checkBluetoothAndLocation() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Toast.makeText(this, "Turn on Location", Toast.LENGTH_LONG).show()
            return
        }

        connectWristband()
    }

    private fun connectWristband() {
        Log.d("MainActivity", "🔵 Connecting to wristband...")
        Toast.makeText(this, "Connection successful!", Toast.LENGTH_SHORT).show()

        // Start reading vitals after connection
        startVitalsService()
    }

    private fun disconnectWristband() {
        Log.d("MainActivity", "🔴 Disconnecting wristband...")
        Toast.makeText(this, "Wristband disconnected", Toast.LENGTH_SHORT).show()

        // Stop the foreground service
        val intent = Intent(this, VitalsService::class.java)
        stopService(intent)
    }


    private fun startVitalsService() {
        val intent = Intent(this, VitalsService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Vitals Service Started", Toast.LENGTH_SHORT).show()
    }
}
