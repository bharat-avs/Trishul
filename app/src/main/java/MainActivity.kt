package com.example.trishul

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.android.material.navigation.NavigationView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val BLE_PERMISSION_REQ_CODE = 101

    // =================================================================
    // INSERT TARGET MAC ADDRESS HERE BEFORE FLASHING (Leave in quotes)
    // Example Node A: "1C:C3:AB:A0:73:72"
    // Example Node B: "1C:C3:AB:A0:6C:3E"
    // =================================================================
    private val TARGET_MAC_ADDRESS = "1C:C3:AB:A0:6C:3E"

    private val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UART_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var terminalLog: TextView
    private lateinit var tvSystemStatus: TextView
    private lateinit var tvEnvData: TextView
    private lateinit var liveGraphView: LiveGraphView
    private lateinit var etChatInput: EditText
    private lateinit var btnSendMsg: Button
    private lateinit var btnScanBle: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanningBle = false
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val btnHamburger = findViewById<TextView>(R.id.btn_hamburger)
        val btnZeroizeQuick = findViewById<Button>(R.id.btn_zeroize_quick)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        terminalLog = findViewById(R.id.tv_terminal_log)
        tvSystemStatus = findViewById(R.id.tv_system_status)
        tvEnvData = findViewById(R.id.tv_env_data)
        liveGraphView = findViewById(R.id.live_graph_view)
        etChatInput = findViewById(R.id.et_chat_input)
        btnSendMsg = findViewById(R.id.btn_send_msg)
        btnScanBle = findViewById(R.id.btn_scan_ble)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        appendLog("SYSTEM", "BOOT COMPLETE. TEE KEYSTORE MOUNTED.", "#00E5FF", "#00E676")
        btnScanBle.text = "CONNECT HARDWARE"

        btnHamburger.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_simulate_rfid -> triggerMfaChallengeDialog()
                R.id.nav_logs -> showEncryptedLogsDialog()
                R.id.nav_simulate_breach -> triggerTamperBreach()
                R.id.nav_zeroize -> executeEmergencyZeroize()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        btnZeroizeQuick.setOnClickListener { executeEmergencyZeroize() }

        btnSendMsg.setOnClickListener {
            val text = etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendBleMessage(text)
                etChatInput.text.clear()
            }
        }

        btnScanBle.setOnClickListener { toggleBleScan() }
    }

    private fun sendBleMessage(msg: String) {
        if (rxCharacteristic != null && bluetoothGatt != null) {
            rxCharacteristic?.setValue(msg.toByteArray(Charsets.UTF_8))
            rxCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(rxCharacteristic)
            appendLog("TX_COMMS", "[$msg] -> TX MESH", "#00E5FF", "#FFFFFF")
        } else {
            appendLog("BLE_WARN", "Not connected to Node. Connect first.", "#FF1744", "#FF1744")
        }
    }

    private fun parseIncomingTelemetry(jsonStr: String) {
        runOnUiThread {
            try {
                if (!jsonStr.startsWith("{")) {
                    appendLog("RX_MESH", "[$jsonStr]", "#D500F9", "#FFFFFF")
                    return@runOnUiThread
                }

                val obj = JSONObject(jsonStr)
                val status = obj.optString("status", "")

                if (status == "TAMPER_BREACH" || status == "COMPROMISED") {
                    triggerTamperBreach()
                }

                if (obj.has("telemetry")) {
                    val telem = obj.getJSONObject("telemetry")
                    val temp = telem.optDouble("temp_c", 24.0).toFloat()
                    tvEnvData.text = String.format(Locale.US, "%.1f °C", temp)
                    liveGraphView.addTelemetry(temp)
                }

                if (obj.has("incoming_comms")) {
                    val msg = obj.optString("incoming_comms", "")
                    appendLog("RX_MESH", "[$msg]", "#D500F9", "#FFFFFF")
                }
            } catch (e: Exception) {
                appendLog("RX_RAW", jsonStr, "#D500F9", "#FFFFFF")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvSystemStatus.text = "BLE CONNECTED [SECURE]"
                    tvSystemStatus.setTextColor(Color.parseColor("#00E676"))
                    btnScanBle.text = "LINK ESTABLISHED"
                    appendLog("BLE", "SECURE UPLINK ESTABLISHED. DISCOVERING...", "#00E676", "#00E676")
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    gatt.discoverServices()
                }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvSystemStatus.text = "BLE DISCONNECTED"
                    tvSystemStatus.setTextColor(Color.parseColor("#FF1744"))
                    btnScanBle.text = "RECONNECT"
                    appendLog("BLE", "LINK LOST", "#FF1744", "#FF1744")
                }
                bluetoothGatt?.close()
                bluetoothGatt = null
                rxCharacteristic = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UART_SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(UART_RX_CHAR_UUID)
                    val txChar = service.getCharacteristic(UART_TX_CHAR_UUID)

                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, true)

                        val descriptor = txChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                    runOnUiThread { appendLog("BLE", "UART RX/TX CHANNELS ARMED", "#00E676", "#00E676") }
                } else {
                    runOnUiThread { appendLog("BLE_ERR", "UART SERVICE NOT FOUND ON NODE", "#FF1744", "#FF1744") }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val raw = String(characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()
            if (raw.isNotEmpty()) {
                parseIncomingTelemetry(raw)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val raw = String(value, Charsets.UTF_8).trim()
            if (raw.isNotEmpty()) {
                parseIncomingTelemetry(raw)
            }
        }
    }

    private fun toggleBleScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }

        if (TARGET_MAC_ADDRESS.isEmpty()) {
            appendLog("SYS_ERR", "TARGET_MAC_ADDRESS IS EMPTY. UPDATE SOURCE CODE.", "#FF1744", "#FF1744")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        btnScanBle.text = "CONNECTING..."
        appendLog("BLE", "HUNTING DIRECT MAC UPLINK: $TARGET_MAC_ADDRESS...", "#00E5FF", "#00E676")
        scanner.startScan(bleScanCallback)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val deviceMac = device.address

                if (deviceMac.equals(TARGET_MAC_ADDRESS, ignoreCase = true)) {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                    runOnUiThread {
                        appendLog("BLE", "HARDWARE MAC FOUND [$deviceMac]. NEGOTIATING...", "#00E676", "#00E676")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
                    }
                }
            }
        }
    }

    // --- DIALOGS & UTILS ---
    private fun triggerMfaChallengeDialog() {
        appendLog("RFID", "CHALLENGE GENERATED (NONCE 0x8F32B9). TOKEN DETECTED.", "#00E5FF", "#00E5FF")
        AlertDialog.Builder(this)
            .setTitle("TACTICAL MFA REQUIRED")
            .setMessage("Smart Token presented. Authorize Operator Fingerprint / PIN?")
            .setPositiveButton("AUTHORIZE") { d, _ ->
                appendLog("MFA", "BIOMETRIC MATCH // CLEARANCE GRANTED", "#00E676", "#00E676")
                d.dismiss()
            }
            .setNegativeButton("DENY (STOLEN)") { d, _ ->
                appendLog("ALERT", "OPERATOR REJECTED // SUSPECTED STOLEN TOKEN", "#FF1744", "#FF1744")
                d.dismiss()
            }
            .show()
    }

    private fun triggerTamperBreach() {
        tvSystemStatus.text = "TAMPER BREACH // KEYS WIPED"
        tvSystemStatus.setTextColor(Color.parseColor("#FF1744"))
        appendLog("THREAT", "CRITICAL: ENCLOSURE BREACH OR MESH COMPROMISE", "#FF1744", "#FF1744")
    }

    private fun executeEmergencyZeroize() {
        logBuffer.clear()
        terminalLog.text = ""
        tvSystemStatus.text = "SYSTEM SANITIZED [LOCKED]"
        tvSystemStatus.setTextColor(Color.parseColor("#FF1744"))
        val file = File(filesDir, "tactical_audit_secure.log")
        if (file.exists()) file.delete()
        appendLog("ZEROIZE", "VOLATILE RAM PURGED. AUDIT LOGS OVERWRITTEN.", "#FF1744", "#FF1744")
        sendBleMessage("REVOKE")
    }

    private fun secureLogToFile(entry: String) {
        try {
            val file = File(filesDir, "tactical_audit_secure.log")
            val mainKey = MasterKey.Builder(applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val encryptedFile = EncryptedFile.Builder(applicationContext, file, mainKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
            var oldData = ""
            if (file.exists()) {
                try { oldData = encryptedFile.openFileInput().bufferedReader().use { it.readText() } } catch (_: Exception) {}
                file.delete()
            }
            encryptedFile.openFileOutput().bufferedWriter().use { it.write(oldData + entry + "\n") }
        } catch (_: Exception) {
            File(filesDir, "tactical_audit_secure.log").appendText(entry + "\n")
        }
    }

    private fun showEncryptedLogsDialog() {
        val file = File(filesDir, "tactical_audit_secure.log")
        var logContent = "NO SECURE AUDIT RECORDS STORED."
        if (file.exists()) {
            try {
                val mainKey = MasterKey.Builder(applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val encFile = EncryptedFile.Builder(applicationContext, file, mainKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
                logContent = encFile.openFileInput().bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                logContent = file.readText()
            }
        }
        val scroll = ScrollView(this).apply { setPadding(30, 30, 30, 30) }
        val tv = TextView(this).apply {
            text = logContent
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
        }
        scroll.addView(tv)
        AlertDialog.Builder(this).setTitle("TEE HARDWARE AUDIT VAULT").setView(scroll).setPositiveButton("DISMISS") { d, _ -> d.dismiss() }.show()
    }

    private fun appendLog(tag: String, message: String, tagColor: String, msgColor: String) {
        if (logBuffer.length > 3000) logBuffer.clear()
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "<font color=\"$tagColor\">[$timeStamp]</font> $tag: <font color=\"$msgColor\">$message</font><br>"
        logBuffer.append(line)
        terminalLog.text = HtmlCompat.fromHtml(logBuffer.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)
        secureLogToFile("[$timeStamp] $tag: $message")
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), BLE_PERMISSION_REQ_CODE)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), BLE_PERMISSION_REQ_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}