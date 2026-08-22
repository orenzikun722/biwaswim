package com.rencon.biwaswim

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.rencon.biwaswim.bluetooth.BluetoothGpsListener
import com.rencon.biwaswim.bluetooth.BluetoothGpsManager
import com.rencon.biwaswim.bluetooth.DiscoveredBluetoothDevice
import com.rencon.biwaswim.map.MapManager
import com.rencon.biwaswim.nmea.GpsLocation
import com.rencon.biwaswim.nmea.NmeaParseDetail
import com.rencon.biwaswim.nmea.NmeaParser
import com.rencon.biwaswim.permission.checkPermission
import com.rencon.biwaswim.usb.UsbSerialListener
import com.rencon.biwaswim.usb.UsbSerialManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class MainActivity : AppCompatActivity(), UsbSerialListener, BluetoothGpsListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val INITIAL_DATA_WAIT_MS = 3000L
        private const val DATA_TIMEOUT_MS = 4000L
        private const val ERROR_HOLD_MS = 3000L
    }

    private class ConnectionHealth {
        var isConnected: Boolean = false
        var connectedAt: Long = 0L
        var lastDataReceivedTime: Long = 0L
        var lastValidLocationTime: Long = 0L
        var lastChecksumErrorTime: Long = 0L
        var lastMalformedTime: Long = 0L
        var lastNoFixTime: Long = 0L

        fun onConnected() {
            isConnected = true
            connectedAt = System.currentTimeMillis()
            lastDataReceivedTime = 0L
            lastValidLocationTime = 0L
            lastChecksumErrorTime = 0L
            lastMalformedTime = 0L
            lastNoFixTime = 0L
        }

        fun onDisconnected() {
            isConnected = false
            connectedAt = 0L
            lastDataReceivedTime = 0L
            lastValidLocationTime = 0L
            lastChecksumErrorTime = 0L
            lastMalformedTime = 0L
            lastNoFixTime = 0L
        }
    }

    private enum class HealthStatus {
        HEALTHY,           // 正常に測位中
        WAITING_DATA,      // 接続直後（データ待機中）
        TIMEOUT,           // データ未受信・途絶
        CHECKSUM_ERROR,    // チェックサムエラー
        MALFORMED,         // データフォーマット異常
        NO_FIX             // 測位中（有効なFixなし）
    }

    private lateinit var connectionStatus: TextView
    private lateinit var mapManager: MapManager
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var bluetoothGpsManager: BluetoothGpsManager
    private val nmeaParser = NmeaParser()
    private lateinit var context: Context
    private var openedSettings = false
    private val dialogs = mutableListOf<AlertDialog>()
    private var selectionDialog: AlertDialog? = null

    private val usbHealth = ConnectionHealth()
    private val btHealth = ConnectionHealth()
    private var connectedBluetoothDeviceName: String? = null
    private var healthMonitorJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val btConnectGranted = grants[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val btScanGranted = grants[Manifest.permission.BLUETOOTH_SCAN] ?: true
        if (!btConnectGranted || !btScanGranted) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.needed_permission))
                .setMessage(getString(R.string.needed_nearby_permission))
                .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                    openedSettings = true
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                    finish()
                }
                .create()
                .apply {
                    setCanceledOnTouchOutside(false)
                }
            dialogs.add(dialog)

            dialog.setOnDismissListener {
                dialogs.remove(dialog)
            }
            dialog.show()
        }
        grants[Manifest.permission.POST_NOTIFICATIONS]?.let { granted ->
            if (!granted) {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.needed_permission))
                    .setMessage(getString(R.string.needed_notification_permission))
                    .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        openedSettings = true
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                        finish()
                    }
                    .create()
                    .apply {
                        setCanceledOnTouchOutside(false)
                    }
                dialogs.add(dialog)

                dialog.setOnDismissListener {
                    dialogs.remove(dialog)
                }
                dialog.show()
            }
        }
        grants[Manifest.permission.ACCESS_FINE_LOCATION]?.let { granted ->
            if (!granted) {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.needed_permission))
                    .setMessage(getString(R.string.needed_location_permission))
                    .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        openedSettings = true
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.close_app)) { _, _ ->
                        finish()
                    }
                    .create()
                    .apply {
                        setCanceledOnTouchOutside(false)
                    }
                dialogs.add(dialog)

                dialog.setOnDismissListener {
                    dialogs.remove(dialog)
                }
                dialog.show()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = buildList {
            if (!checkPermission.checkBluetoothPermission(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (!checkPermission.checkNotificationPermission(context)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!checkPermission.checkLocationPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isEmpty()) {
            setupClasses()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this
        MapLibre.getInstance(context)

        setContentView(R.layout.activity_main)

        mapManager = MapManager(this, findViewById(R.id.mapView))
        mapManager.initialize(savedInstanceState)

        setupWindowInsets()

        connectionStatus = findViewById(R.id.connectionStatus)
        connectionStatus.setOnClickListener {
            if (::bluetoothGpsManager.isInitialized) {
                showManualDeviceSelectionDialog()
            }
        }

        requestPermissions()
    }

    private fun setupClasses() {
        // USB接続初期化
        usbSerialManager = UsbSerialManager(context, this)
        usbSerialManager.registerReceiver()
        usbSerialManager.connect()

        // Bluetooth接続初期化（探索と電波強度ソート開始）
        bluetoothGpsManager = BluetoothGpsManager(context, this)
        bluetoothGpsManager.startDiscovery(autoConnect = true)
        Log.d(TAG, "Bluetooth discovery and auto connect initiated")
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            if (::usbSerialManager.isInitialized) {
                usbSerialManager.connect()
            }
        }
    }

    private fun ConnectionHealth.evaluateStatus(now: Long): HealthStatus {
        if (!isConnected) return HealthStatus.TIMEOUT

        val hasRecentValidLocation = (lastValidLocationTime > 0 && now - lastValidLocationTime < DATA_TIMEOUT_MS)

        // 1. 直近でエラーが発生している場合（まだ正常な位置情報が得られていない場合）
        if (lastChecksumErrorTime > 0 && now - lastChecksumErrorTime < ERROR_HOLD_MS && !hasRecentValidLocation) {
            return HealthStatus.CHECKSUM_ERROR
        }

        if (lastMalformedTime > 0 && now - lastMalformedTime < ERROR_HOLD_MS && !hasRecentValidLocation) {
            return HealthStatus.MALFORMED
        }

        // 2. データ受信の有無・タイムアウト判定
        if (lastDataReceivedTime == 0L) {
            return if (now - connectedAt < INITIAL_DATA_WAIT_MS) {
                HealthStatus.WAITING_DATA
            } else {
                HealthStatus.TIMEOUT
            }
        }

        if (now - lastDataReceivedTime >= DATA_TIMEOUT_MS) {
            return HealthStatus.TIMEOUT
        }

        // 3. 有効な位置情報が得られているか
        if (hasRecentValidLocation) {
            return HealthStatus.HEALTHY
        }

        // 4. データは届いているが衛星未捕捉 (No Fix)
        return HealthStatus.NO_FIX
    }

    private fun handleNmeaDetail(detail: NmeaParseDetail, isUsb: Boolean) {
        val health = if (isUsb) usbHealth else btHealth
        val now = System.currentTimeMillis()
        health.lastDataReceivedTime = now

        when (detail) {
            is NmeaParseDetail.LocationUpdate -> {
                health.lastValidLocationTime = now
                runOnUiThread {
                    mapManager.updateLocation(
                        latitude = detail.location.latitude,
                        longitude = detail.location.longitude
                    )
                }
            }
            is NmeaParseDetail.NoFix -> {
                health.lastNoFixTime = now
            }
            is NmeaParseDetail.InvalidChecksum -> {
                health.lastChecksumErrorTime = now
                Log.w(TAG, "NMEA Checksum error (${if (isUsb) "USB" else "BT"}): ${detail.rawSentence}")
            }
            is NmeaParseDetail.Malformed -> {
                health.lastMalformedTime = now
                Log.w(TAG, "NMEA Malformed sentence (${if (isUsb) "USB" else "BT"}): ${detail.rawSentence}")
            }
            is NmeaParseDetail.Unsupported -> {
                // GSV, GSA などの補助センテンス（正常ストリームの一部）
            }
        }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                updateOverallConnectionStatus()
            }
        }
    }

    private fun stopHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }

    // --- UsbSerialListener 実装 ---

    override fun onConnected() {
        usbHealth.onConnected()
        updateOverallConnectionStatus()
    }

    override fun onDisconnected() {
        usbHealth.onDisconnected()
        updateOverallConnectionStatus()
    }

    override fun onRawDataReceived(data: ByteArray) {
        val details = nmeaParser.parseRawDataWithDetails(data)
        for (detail in details) {
            handleNmeaDetail(detail, isUsb = true)
        }
        updateOverallConnectionStatus()
    }

    override fun onError(exception: Exception) {
        usbHealth.onDisconnected()
        updateOverallConnectionStatus()
    }

    // --- BluetoothGpsListener 実装 ---

    override fun onBluetoothConnected(device: BluetoothDevice) {
        btHealth.onConnected()
        connectedBluetoothDeviceName = try {
            device.name ?: device.address
        } catch (e: Exception) {
            device.address
        }
        Log.d(TAG, "Bluetooth GNSS connected: $connectedBluetoothDeviceName")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothDisconnected() {
        btHealth.onDisconnected()
        connectedBluetoothDeviceName = null
        Log.d(TAG, "Bluetooth GNSS disconnected")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothError(exception: Exception) {
        btHealth.onDisconnected()
        connectedBluetoothDeviceName = null
        Log.e(TAG, "Bluetooth GNSS error: ${exception.message}", exception)
        updateOverallConnectionStatus()
    }

    override fun onRawNmeaReceived(line: String) {
        btHealth.lastDataReceivedTime = System.currentTimeMillis()
    }

    override fun onNmeaParseDetail(detail: NmeaParseDetail) {
        handleNmeaDetail(detail, isUsb = false)
        updateOverallConnectionStatus()
    }

    override fun onLocationReceived(location: GpsLocation) {
        // handleNmeaDetail で処理されるためここでは追加処理なし
    }

    override fun onMultipleDevicesFound(devices: List<DiscoveredBluetoothDevice>) {
        runOnUiThread {
            showDeviceSelectionDialog(devices)
        }
    }

    override fun onDiscoveryFinished(devices: List<DiscoveredBluetoothDevice>) {
        Log.d(TAG, "Bluetooth discovery finished with ${devices.size} devices")
    }

    /**
     * 検出されたBluetoothデバイス（電波強度順）を選択するためのダイアログを表示します。
     */
    private fun showDeviceSelectionDialog(devices: List<DiscoveredBluetoothDevice>) {
        selectionDialog?.dismiss()

        if (devices.isEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_bluetooth_device))
                .setMessage(getString(R.string.no_device_found))
                .setPositiveButton(getString(R.string.rescan)) { _, _ ->
                    bluetoothGpsManager.startDiscovery(autoConnect = false)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            dialogs.add(dialog)
            dialog.setOnDismissListener { dialogs.remove(dialog) }
            dialog.show()
            selectionDialog = dialog
            return
        }

        val itemLabels = devices.map { dev ->
            val signalStrength = when {
                dev.rssi >= -60 -> "強"
                dev.rssi >= -80 -> "中"
                dev.rssi > -100 -> "弱"
                else -> "未測定"
            }
            val rssiText = if (dev.rssi > -100) "${dev.rssi} dBm ($signalStrength)" else "ペアリング済"
            "${dev.name}\n${dev.address}  [$rssiText]"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_bluetooth_device))
            .setItems(itemLabels) { _, which ->
                val selected = devices[which]
                Log.d(TAG, "User selected Bluetooth device: ${selected.name} (${selected.address})")
                bluetoothGpsManager.connect(selected.device)
            }
            .setPositiveButton(getString(R.string.rescan)) { _, _ ->
                bluetoothGpsManager.startDiscovery(autoConnect = false)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            selectionDialog = null
        }
        dialog.show()
        selectionDialog = dialog
    }

    /**
     * 手動でBluetoothデバイス選択ダイアログを開き、最新の一覧表示とスキャンを行います。
     */
    private fun showManualDeviceSelectionDialog() {
        val currentDevices = bluetoothGpsManager.getSortedDiscoveredDevices()
        showDeviceSelectionDialog(currentDevices)
        bluetoothGpsManager.startDiscovery(autoConnect = false)
    }

    /**
     * USBおよびBluetoothの接続・受信状況（およびエラー警告）に応じてステータス表示を更新します。
     */
    private fun updateOverallConnectionStatus() {
        val now = System.currentTimeMillis()
        val isUsbConnected = usbHealth.isConnected
        val isBtConnected = btHealth.isConnected

        runOnUiThread {
            if (!::connectionStatus.isInitialized) return@runOnUiThread

            if (!isUsbConnected && !isBtConnected) {
                connectionStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.disconnected))
                connectionStatus.text = getString(R.string.disconnected)
                return@runOnUiThread
            }

            val btName = connectedBluetoothDeviceName ?: "BT"
            val btStatus = if (isBtConnected) btHealth.evaluateStatus(now) else null
            val usbStatus = if (isUsbConnected) usbHealth.evaluateStatus(now) else null

            val hasWarning = (btStatus != null && btStatus != HealthStatus.HEALTHY) ||
                    (usbStatus != null && usbStatus != HealthStatus.HEALTHY)

            val colorRes = if (hasWarning) R.color.warning else R.color.connected
            connectionStatus.setBackgroundColor(ContextCompat.getColor(this, colorRes))

            val statusText = when {
                isUsbConnected && isBtConnected -> {
                    val usbText = formatSourceStatus("USB", usbStatus ?: HealthStatus.TIMEOUT)
                    val btText = formatSourceStatus("BT: $btName", btStatus ?: HealthStatus.TIMEOUT)
                    if (!hasWarning) {
                        getString(R.string.connected_both, btName)
                    } else {
                        "$usbText | $btText"
                    }
                }
                isUsbConnected -> {
                    formatSourceStatus(getString(R.string.connected_usb), usbStatus ?: HealthStatus.TIMEOUT)
                }
                isBtConnected -> {
                    val baseName = if (connectedBluetoothDeviceName != null) {
                        getString(R.string.connected_bluetooth, connectedBluetoothDeviceName)
                    } else {
                        getString(R.string.connected_bluetooth_simple)
                    }
                    formatSourceStatus(baseName, btStatus ?: HealthStatus.TIMEOUT)
                }
                else -> getString(R.string.disconnected)
            }
            connectionStatus.text = statusText
        }
    }

    private fun formatSourceStatus(name: String, status: HealthStatus): String {
        return when (status) {
            HealthStatus.HEALTHY -> name
            HealthStatus.WAITING_DATA -> getString(R.string.status_warning_no_data, name)
            HealthStatus.TIMEOUT -> getString(R.string.status_warning_timeout, name)
            HealthStatus.CHECKSUM_ERROR -> getString(R.string.status_warning_checksum, name)
            HealthStatus.MALFORMED -> getString(R.string.status_warning_malformed, name)
            HealthStatus.NO_FIX -> getString(R.string.status_warning_no_fix, name)
        }
    }

    // --- ライフサイクル委譲 ---

    override fun onStart() {
        super.onStart()
        mapManager.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapManager.onResume()
        startHealthMonitor()
        if (openedSettings) {
            openedSettings = false
            if (!checkPermission.checkLocationPermission(context)) {
                requestPermissions()
                return
            }
            if (!checkPermission.checkBluetoothPermission(context)) {
                requestPermissions()
                return
            }
            if (!checkPermission.checkNotificationPermission(context)) {
                requestPermissions()
                return
            }
            dialogs.forEach {
                if (it.isShowing) {
                    it.dismiss()
                }
            }

            dialogs.clear()
        }
    }

    override fun onPause() {
        super.onPause()
        stopHealthMonitor()
        mapManager.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapManager.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        selectionDialog?.dismiss()
        selectionDialog = null
        if (::usbSerialManager.isInitialized) {
            usbSerialManager.unregisterReceiver()
            usbSerialManager.disconnect()
        }
        if (::bluetoothGpsManager.isInitialized) {
            bluetoothGpsManager.release()
        }
        mapManager.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapManager.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapManager.onSaveInstanceState(outState)
    }
}