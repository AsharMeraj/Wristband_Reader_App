package com.example.wristbandreader

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jstyle.blesdk2208a.Util.BleSDK
import com.jstyle.blesdk2208a.Util.ResolveUtil
import com.jstyle.blesdk2208a.model.AutoMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

class VitalsService : Service() {

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val wristbandName = "J2208A2 E4F7"
    private val wristbandMac = "CD:27:0B:30:E4:F7"
    private val wristbandServiceUUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val wristbandNotifyUUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    private var spo2Sent = false
    private var retryCount = 0
    private val maxRetries = 3

    private lateinit var gattCallback: BluetoothGattCallback

    override fun onCreate() {
        super.onCreate()
        Log.d("VitalsService", "SERVICE STARTED")
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupGattCallback()

        if (!hasScanPermission() || !hasConnectPermission()) {
            Log.e("VitalsService", "BLE permissions missing. Request them from Activity.")
        } else {
            startScanSafe()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vitals_channel",
                "Vitals Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, "vitals_channel")
            .setContentTitle("Vitals Service Running")
            .setContentText("Scanning and receiving data from wristband…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

    private fun notifyConnectionStatus(status: String) {
        val intent = Intent("com.example.wristbandreader.CONNECTION_STATUS")
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    private fun startScanSafe() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run {
            Log.e("VitalsService", "Bluetooth adapter not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.e("VitalsService", "Bluetooth not enabled")
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            Log.e("VitalsService", "Bluetooth LE Scanner not available")
            return
        }

        val scanFilters = listOf<ScanFilter>()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
        Log.d("VitalsService", "Scanning started for wristband $wristbandName...")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "NULL"

            if (name == wristbandName || device.address.equals(wristbandMac, ignoreCase = true)) {
                Log.d("VitalsService", "Target Wristband Found → Name=$name | MAC=${device.address} | RSSI=${result.rssi}")
                stopScanSafe(this)
                connectToDeviceSafe(device)
            } else {
                Log.d("VitalsService", "Ignoring device → Name=$name | MAC=${device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("VitalsService", "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafe(callback: ScanCallback) {
        bluetoothLeScanner?.stopScan(callback)
        Log.d("VitalsService", "Scan stopped")
    }

    @SuppressLint("MissingPermission")
    private fun connectToDeviceSafe(device: BluetoothDevice) {
        bluetoothGatt?.close()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
        Log.d("VitalsService", "Connecting to device → ${device.name ?: "NULL"} / ${device.address}")
    }
    private var isManualDisconnect = false

    @SuppressLint("MissingPermission")
    private fun setupGattCallback() {
        var measurementInProgress = false  // prevent automatic restart

        gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d("VitalsService", "ConnectionStateChange → status=$status newState=$newState")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("VitalsService", "GATT ERROR $status")
                    gatt.close()
                    if (!isManualDisconnect) retryConnection(gatt.device)
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d("VitalsService", "Connected, discovering services...")
                        notifyConnectionStatus("Connected")
                        gatt.discoverServices()
                        retryCount = 0
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.e("VitalsService", "Disconnected")
                        gatt.close()
                        notifyConnectionStatus("Disconnected")
                        if (!isManualDisconnect) retryConnection(gatt.device) else isManualDisconnect = false
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private fun retryConnection(device: BluetoothDevice) {
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("VitalsService", "Retrying connection to ${device.name}")
                    bluetoothGatt = device.connectGatt(this@VitalsService, false, gattCallback)
                }, 1000)
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                Log.d("VitalsService", "Services discovered")
                val targetService = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID } ?: return

                writeChar = targetService.characteristics.firstOrNull {
                    val props = it.properties
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                }

                val notifyChar = targetService.characteristics.firstOrNull {
                    it.uuid == wristbandNotifyUUID && (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                }

                if (writeChar == null || notifyChar == null) {
                    Log.e("VitalsService", "Suitable characteristics not found")
                    return
                }

                gatt.setCharacteristicNotification(notifyChar, true)
                notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(this)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    writeChar?.let { wChar ->
                        wChar.value = BleSDK.GetAutomatic(AutoMode.AutoHeartRate)
                        gatt.writeCharacteristic(wChar)

                        wChar.value = BleSDK.GetAutomatic(AutoMode.AutoSpo2)
                        gatt.writeCharacteristic(wChar)

                        wChar.value = BleSDK.GetAutomatic(AutoMode.AutoTemp)
                        gatt.writeCharacteristic(wChar)
                    }
                }, 1200)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value ?: return
                if (data.isEmpty()) return

                val packetType = data[0].toInt() and 0xFF
                Log.d("VitalsService", "Received packet type: 0x${packetType.toString(16)}")

                try {
                    when (packetType) {
                        0x09, 0x28 -> handleActivityData(data)
                        0x60 -> handleSpo2Data(data)
                        0xFE -> resetMeasurement(gatt)  // only start if not already
                        else -> Log.d("VitalsService", "Unhandled packet type: 0x${packetType.toString(16)}")
                    }
                } catch (e: Exception) {
                    Log.e("VitalsService", "Error parsing packet: ${e.message}")
                }
            }

            private fun handleActivityData(data: ByteArray) {
                val dic = ResolveUtil.getActivityData(data)["dicData"] as? Map<String, String> ?: return
                val spo2 = dic["Blood_oxygen"]?.toIntOrNull() ?: 0
                val hr = dic["heartRate"] ?: "Unknown"
                val temp = dic["TempData"] ?: "Unknown"

                Log.d("VitalsService", "ActivityData → SPO2: $spo2 | HR: $hr | Temp: $temp | Sending: ${!spo2Sent && spo2 > 0}")

                if (!spo2Sent && spo2 > 0) {
                    sendVitalsToBackend(spo2.toString(), hr, temp, System.currentTimeMillis().toString())
                    spo2Sent = true
                }
            }

            private fun handleSpo2Data(data: ByteArray) {
                if (data.size < 4) return
                val dic = ResolveUtil.getSpo2(data)["dicData"] as? Map<String, String> ?: return
                val spo2 = dic["Blood_oxygen"] ?: return
                val hr = dic["heartRate"] ?: "Unknown"
                val temp = dic["TempData"] ?: "Unknown"

                Log.d("VitalsService", "Spo2Data → SPO2: $spo2 | HR: $hr | Temp: $temp | Sending: ${!spo2Sent}")

                if (!spo2Sent) {
                    sendVitalsToBackend(spo2.toString(), hr, temp, System.currentTimeMillis().toString())
                }
            }

            private fun resetMeasurement(gatt: BluetoothGatt) {
                Log.d("VitalsService", "Double-tap detected → starting measurement")
                measurementInProgress = true
                spo2Sent = false

                writeChar?.let { wChar ->
                    Handler(Looper.getMainLooper()).post {
                        wChar.value = BleSDK.StartDeviceMeasurementWithType(3, true, 60)
                        gatt.writeCharacteristic(wChar)
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            wChar.value = BleSDK.GetBloodOxygen(3.toByte(), "00000000")
                            gatt.writeCharacteristic(wChar)
                        } catch (_: Exception) {}
                    }, 1000)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("VitalsService", "Notifications enabled for ${descriptor.characteristic.uuid}")
                } else {
                    Log.e("VitalsService", "Failed to enable notifications: $status")
                }
            }
        }
    }


    // Updated sendVitalsToBackend with logging backend response
    private fun sendVitalsToBackend(spo2: String, heartRate: String, temperature: String, timestamp: String) {
        Thread {
            try {
                Log.d("VitalsService", "Sending to backend → SPO2: $spo2 | HR: $heartRate | Temp: $temperature | Timestamp: $timestamp")
                val json = JSONObject()
                    .put("spo2", spo2)
                    .put("heartRate", heartRate)
                    .put("temperature", temperature)
                    .put("timestamp", timestamp)
                    .toString()

                val request = Request.Builder()
                    .url("https://backend-api-for-wristband.vercel.app/api/vitals")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d("VitalsService", "Backend response code: ${response.code}")
                    if (response.isSuccessful) {
                        Log.d("VitalsService", "Backend accepted data")
                    } else {
                        Log.e("VitalsService", "Backend rejected data")
                    }
                }
            } catch (e: Exception) {
                Log.e("VitalsService", "Failed to send vitals: ${e.message}")
            }
        }.start()
    }




    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.d("VitalsService", "Service destroyed")
        bluetoothGatt?.close()
        sendVitalsToBackend("", "", "", System.currentTimeMillis().toString()) // Ensure backend gets empty on destroy
        super.onDestroy()
    }

    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else true

    private fun hasConnectPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    else true
}
