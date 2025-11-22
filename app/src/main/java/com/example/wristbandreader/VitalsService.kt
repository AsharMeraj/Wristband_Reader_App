//package com.example.wristbandreader
//
//import android.annotation.SuppressLint
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.Service
//import android.bluetooth.*
//import android.bluetooth.le.*
//import android.content.Context
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.os.*
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import androidx.core.content.ContextCompat
//import com.jstyle.blesdk2208a.Util.BleSDK
//import com.jstyle.blesdk2208a.Util.ResolveUtil
//import com.jstyle.blesdk2208a.model.AutoMode
//import okhttp3.MediaType.Companion.toMediaType
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import okhttp3.RequestBody.Companion.toRequestBody
//import org.json.JSONObject
//import java.util.*
//import java.util.concurrent.TimeUnit
//
//class VitalsService : Service() {
//
//    private var bluetoothGatt: BluetoothGatt? = null
//    private var bluetoothLeScanner: BluetoothLeScanner? = null
//    private var writeChar: BluetoothGattCharacteristic? = null
//
//    private val wristbandName = "J2208A2 E4F7"
//    private val wristbandMac = "CD:27:0B:30:E4:F7"
//    private val wristbandServiceUUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb") // Replace with correct service UUID
//    private val wristbandNotifyUUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb") // Replace if needed
//
//    private val client = OkHttpClient.Builder()
//        .callTimeout(15, TimeUnit.SECONDS)
//        .build()
//
//    override fun onCreate() {
//        super.onCreate()
//        Log.d("VitalsService", "SERVICE STARTED")
//        createNotificationChannel()
//        startForeground(1, buildNotification())
//
//        if (!hasScanPermission() || !hasConnectPermission()) {
//            Log.e("VitalsService", "BLE permissions missing. Request them from Activity.")
//        } else {
//            startScanSafe()
//        }
//    }
//
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                "vitals_channel",
//                "Vitals Channel",
//                NotificationManager.IMPORTANCE_LOW
//            )
//            val manager = getSystemService(NotificationManager::class.java)
//            manager?.createNotificationChannel(channel)
//        }
//    }
//
//    private fun buildNotification() =
//        NotificationCompat.Builder(this, "vitals_channel")
//            .setContentTitle("Vitals Service Running")
//            .setContentText("Scanning and receiving data from wristband…")
//            .setSmallIcon(android.R.drawable.ic_menu_info_details)
//            .build()
//
//    @SuppressLint("MissingPermission")
//    private fun startScanSafe() {
//        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
//        val adapter = manager.adapter ?: run {
//            Log.e("VitalsService", "Bluetooth adapter not available")
//            return
//        }
//        if (!adapter.isEnabled) {
//            Log.e("VitalsService", "Bluetooth not enabled")
//            return
//        }
//        bluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
//            Log.e("VitalsService", "Bluetooth LE Scanner not available")
//            return
//        }
//
//        val scanFilters = listOf<ScanFilter>() // Filter in callback
//        val scanSettings = ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .build()
//
//        bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
//        Log.d("VitalsService", "Scanning started for wristband $wristbandName...")
//    }
//
//    private val scanCallback = object : ScanCallback() {
//        @SuppressLint("MissingPermission")
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            val device = result.device
//            val name = device.name ?: result.scanRecord?.deviceName ?: "NULL"
//
//            if (name == wristbandName || device.address.equals(wristbandMac, ignoreCase = true)) {
//                Log.d(
//                    "VitalsService",
//                    "Target Wristband Found → Name=$name | MAC=${device.address} | RSSI=${result.rssi}"
//                )
//                stopScanSafe(this)
//                connectToDeviceSafe(device)
//            } else {
//                Log.d("VitalsService", "Ignoring device → Name=$name | MAC=${device.address}")
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            Log.e("VitalsService", "Scan failed: $errorCode")
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun stopScanSafe(callback: ScanCallback) {
//        bluetoothLeScanner?.stopScan(callback)
//        Log.d("VitalsService", "Scan stopped")
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun connectToDeviceSafe(device: BluetoothDevice) {
//        bluetoothGatt?.close()
//        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
//        } else {
//            device.connectGatt(this, false, gattCallback)
//        }
//        Log.d("VitalsService", "Connecting to device → ${device.name ?: "NULL"} / ${device.address}")
//    }
//
//    @SuppressLint("MissingPermission")
//    private val gattCallback = object : BluetoothGattCallback() {
//
//        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
//            Log.d("VitalsService", "ConnectionStateChange → status=$status newState=$newState")
//            if (status != BluetoothGatt.GATT_SUCCESS) {
//                Log.e("VitalsService", "GATT ERROR, closing connection")
//                gatt.close()
//                return
//            }
//            when (newState) {
//                BluetoothProfile.STATE_CONNECTED -> {
//                    Log.d("VitalsService", "Connected, discovering services...")
//                    gatt.discoverServices()
//                }
//                BluetoothProfile.STATE_DISCONNECTED -> {
//                    Log.e("VitalsService", "Disconnected")
//                    gatt.close()
//                }
//            }
//        }
//
//        @SuppressLint("MissingPermission")
//        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
//            if (status != BluetoothGatt.GATT_SUCCESS) return
//            Log.d("VitalsService", "Services discovered with status=$status")
//
//            // Find service
//            val targetService = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID }
//            if (targetService == null) {
//                Log.e("VitalsService", "Target service not found")
//                return
//            }
//
//            // Find write characteristic
//            writeChar = targetService.characteristics.firstOrNull {
//                val props = it.properties
//                (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
//                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
//            }
//
//            // Find notify characteristic
//            val notifyChar = targetService.characteristics.firstOrNull {
//                it.uuid == wristbandNotifyUUID && (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
//            }
//
//            if (writeChar == null || notifyChar == null) {
//                Log.e("VitalsService", "Suitable characteristics not found")
//                return
//            }
//
//            Log.d(
//                "VitalsService",
//                "Write char: ${writeChar?.uuid} | Notify char: ${notifyChar?.uuid}"
//            )
//
//            // Enable notifications
//            notifyChar.let { char ->
//                gatt.setCharacteristicNotification(char, true)
//                char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
//                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//                    gatt.writeDescriptor(this)
//                }
//            }
//
//            // Start automatic measurement
//            Handler(Looper.getMainLooper()).postDelayed({
//                Log.d("VitalsService", "Enabling automatic reading mode")
//                writeChar?.let { wChar ->
//                    // HR
//                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoHeartRate)
//                    gatt.writeCharacteristic(wChar)
//
//                    // SpO2
//                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoSpo2)
//                    gatt.writeCharacteristic(wChar)
//
//                    // Temp
//                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoTemp)
//                    gatt.writeCharacteristic(wChar)
//                }
//            }, 1200) // increased delay to ensure notifications are enabled
//        }
//
//        override fun onCharacteristicChanged(
//            gatt: BluetoothGatt,
//            characteristic: BluetoothGattCharacteristic
//        ) {
//            val data = characteristic.value ?: return
//            if (data.isEmpty()) return
//            Log.d(
//                "VitalsService",
//                "Raw packet: ${data.joinToString("-") { "%02X".format(it) }}"
//            )
//
//            val timestamp = System.currentTimeMillis().toString()
//            try {
//                when (data[0].toInt() and 0xFF) {
//
//                    0xFE -> { // Double-tap detected
//                        Log.d("VitalsService", "Double-tap detected → requesting instant vitals")
//                        writeChar?.let { wChar ->
//                            bluetoothGatt?.let { gatt ->
//                                Handler(Looper.getMainLooper()).post {
//                                    // Trigger SpO2 measurement first
//                                    wChar.value = BleSDK.StartDeviceMeasurementWithType(2, true, 60) // 2 = SpO2
//                                    gatt.writeCharacteristic(wChar)
//
//                                    // Wait 2 seconds to ensure SpO2 is measured
//                                    Handler(Looper.getMainLooper()).postDelayed({
//                                        // Then request all vitals (HR, step, temp, SpO2)
//                                        wChar.value = BleSDK.RealTimeStep(true, true)
//                                        gatt.writeCharacteristic(wChar)
//                                    }, 2000)
//                                }
//                            }
//                        }
//                    }
//
//
//                    0x09 -> { // Real-time vitals
//                        try {
//                            val vitalsMap = ResolveUtil.getActivityData(data) // SDK method that extracts HR, SpO2, Temp
//                            val dic = vitalsMap["dicData"] as? Map<String, String>
//                            dic?.let {
//                                sendVitalsToBackend(
//                                    it["Blood_oxygen"] ?: "Unknown",
//                                    it["heartRate"] ?: "Unknown",
//                                    it["TempData"] ?: "Unknown",
//                                    timestamp
//                                )
//                                Log.d("VitalsService", "Real-time vitals: $it")
//                            }
//                        } catch (e: Exception) {
//                            Log.e("VitalsService", "Failed to parse real-time vitals: ${e.message}")
//                        }
//                    }
//
//
//                    0x28 -> { // Real-time vitals
//                        val vitalsMap = ResolveUtil.getSpo2(data) // or appropriate ResolveUtil method
//                        val dic = vitalsMap["dicData"] as? Map<String, String>
//                        dic?.let {
//                            sendVitalsToBackend(
//                                it["Blood_oxygen"] ?: "Unknown",
//                                it["Heart_rate"] ?: "Unknown",
//                                it["Temperature"] ?: "Unknown",
//                                timestamp
//                            )
//                            Log.d("VitalsService", "Real-time vitals: $it")
//                        }
//                    }
//
//                    0x23 -> { // Real-time vitals
//                        val vitalsMap = ResolveUtil.getActivityData(data)
//                        val dic = vitalsMap["dicData"] as? Map<String, String>
//                        dic?.let {
//                            sendVitalsToBackend(
//                                it["Blood_oxygen"] ?: "Unknown",
//                                it["Heart_rate"] ?: "Unknown",
//                                it["TempData"] ?: "Unknown",
//                                timestamp
//                            )
//                            Log.d("VitalsService", "Instant vitals: $it")
//                        }
//                    }
//
//                    0x66 -> { // Automatic vitals
//                        val spo2Map = ResolveUtil.GetAutomaticSpo2Monitoring(data)
//                        val dic = spo2Map["dicData"] as? Map<String, String>
//                        dic?.let {
//                            sendVitalsToBackend(
//                                it["Blood_oxygen"] ?: "Unknown",
//                                it["Heart_rate"] ?: "Unknown",
//                                it["Temperature"] ?: "Unknown",
//                                timestamp
//                            )
//                            Log.d("VitalsService", "Vitals received: $it")
//                        }
//                    }
//
//                    0x17 -> { // Auto heart
//                        val autoHeartMap = ResolveUtil.getAutoHeart(data)
//                        Log.d("VitalsService", "Auto heart schedule: $autoHeartMap")
//                    }
//
//                    0x70 -> { // SpO2 schedule
//                        val spo2ScheduleMap = ResolveUtil.getSpo2(data)
//                        Log.d("VitalsService", "SpO2 schedule: $spo2ScheduleMap")
//                    }
//
//                    0x21 -> { // Activity alarm
//                        val activityAlarmMap = ResolveUtil.getActivityAlarm(data)
//                        Log.d("VitalsService", "Activity alarm: $activityAlarmMap")
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e("VitalsService", "Error parsing packet: ${e.message}")
//            }
//        }
//
//        override fun onDescriptorWrite(
//            gatt: BluetoothGatt,
//            descriptor: BluetoothGattDescriptor,
//            status: Int
//        ) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                Log.d(
//                    "VitalsService",
//                    "Notifications enabled successfully for ${descriptor.characteristic.uuid}"
//                )
//            } else {
//                Log.e("VitalsService", "Failed to enable notifications: $status")
//            }
//        }
//    }
//
//    private fun sendVitalsToBackend(spo2: String, heartRate: String, temperature: String, timestamp: String) {
//        Thread {
//            try {
//                val json = JSONObject()
//                    .put("spo2", spo2)
//                    .put("heartRate", heartRate)
//                    .put("temperature", temperature)
//                    .put("timestamp", timestamp)
//                    .toString()
//
//                val request = Request.Builder()
//                    .url("https://backend-api-for-wristband.vercel.app/api/vitals")
//                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
//                    .build()
//
//                client.newCall(request).execute().use { response ->
//                    Log.d("VitalsService", "Backend response: ${response.code}")
//                }
//            } catch (e: Exception) {
//                Log.e("VitalsService", "Failed to send vitals: ${e.message}")
//            }
//        }.start()
//    }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//
//    @SuppressLint("MissingPermission")
//    override fun onDestroy() {
//        Log.d("VitalsService", "Service destroyed")
//        bluetoothGatt?.close()
//        super.onDestroy()
//    }
//
//    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
//        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
//    else true
//
//    private fun hasConnectPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
//        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
//    else true
//}
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
    private val wristbandServiceUUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb") // Replace with correct service UUID
    private val wristbandNotifyUUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb") // Replace if needed

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d("VitalsService", "SERVICE STARTED")
        createNotificationChannel()
        startForeground(1, buildNotification())

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

        val scanFilters = listOf<ScanFilter>() // Filter in callback
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
                Log.d(
                    "VitalsService",
                    "Target Wristband Found → Name=$name | MAC=${device.address} | RSSI=${result.rssi}"
                )
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

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("VitalsService", "ConnectionStateChange → status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("VitalsService", "GATT ERROR, closing connection")
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("VitalsService", "Connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.e("VitalsService", "Disconnected")
                    gatt.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            Log.d("VitalsService", "Services discovered with status=$status")

            // Find service
            val targetService = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID }
            if (targetService == null) {
                Log.e("VitalsService", "Target service not found")
                return
            }

            // Find write characteristic
            writeChar = targetService.characteristics.firstOrNull {
                val props = it.properties
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            }

            // Find notify characteristic
            val notifyChar = targetService.characteristics.firstOrNull {
                it.uuid == wristbandNotifyUUID && (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            }

            if (writeChar == null || notifyChar == null) {
                Log.e("VitalsService", "Suitable characteristics not found")
                return
            }

            Log.d(
                "VitalsService",
                "Write char: ${writeChar?.uuid} | Notify char: ${notifyChar?.uuid}"
            )

            // Enable notifications
            notifyChar.let { char ->
                gatt.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(this)
                }
            }

            // Start automatic measurement
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("VitalsService", "Enabling automatic reading mode")
                writeChar?.let { wChar ->
                    // HR
                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoHeartRate)
                    gatt.writeCharacteristic(wChar)

                    // SpO2
                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoSpo2)
                    gatt.writeCharacteristic(wChar)

                    // Temp
                    wChar.value = BleSDK.GetAutomatic(AutoMode.AutoTemp)
                    gatt.writeCharacteristic(wChar)
                }
            }, 1200) // increased delay to ensure notifications are enabled
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            if (data.isEmpty()) return

            Log.d("VitalsService", "Raw packet: ${data.joinToString("-") { "%02X".format(it) }}")

            try {
                when (data[0].toInt() and 0xFF) {

                    0xFE -> {
                        Log.d("VitalsService", "Double-tap detected → requesting instant vitals")

                        writeChar?.let { wChar ->
                            bluetoothGatt?.let { gatt ->

                                Handler(Looper.getMainLooper()).post {
                                    wChar.value = BleSDK.StartDeviceMeasurementWithType(3, true, 60)
                                    val success = gatt.writeCharacteristic(wChar)
                                    Log.d("VitalsService", "StartDeviceMeasurementWithType(3) sent: $success")
                                }

                                // MUST SEND SECOND COMMAND → OR NO VITAL DATA
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        val packet = BleSDK.GetBloodOxygen(3.toByte(), "00000000")
                                        wChar.value = packet
                                        val ok = gatt.writeCharacteristic(wChar)
                                        Log.d("VitalsService", "Requested current vitals: $ok")
                                    } catch (e: Exception) {
                                        Log.e("VitalsService", "Failed to request vitals: ${e.message}")
                                    }
                                }, 1000)

                            }
                        }
                    }


                    0x09, 0x28, 0x23, 0x66 -> {

                        try {
                            val dic = ResolveUtil.getActivityData(data)["dicData"] as? Map<String, String>

                            dic?.let {
                                sendVitalsToBackend(
                                    it["Blood_oxygen"] ?: "Unknown",
                                    it["heartRate"] ?: "Unknown",
                                    it["TempData"] ?: "Unknown",
                                    System.currentTimeMillis().toString()
                                )

                                Log.d("VitalsService", "Vitals sent to backend: $it")
                            }

                        } catch (e: Exception) {
                            Log.e("VitalsService", "Failed to parse vitals: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("VitalsService", "Error parsing packet: ${e.message}")
            }
        }


        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(
                    "VitalsService",
                    "Notifications enabled successfully for ${descriptor.characteristic.uuid}"
                )
            } else {
                Log.e("VitalsService", "Failed to enable notifications: $status")
            }
        }
    }

    private fun sendVitalsToBackend(spo2: String, heartRate: String, temperature: String, timestamp: String) {
        Thread {
            try {
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
                    Log.d("VitalsService", "Backend response: ${response.code}")
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
        super.onDestroy()
    }

    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else true

    private fun hasConnectPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    else true
}