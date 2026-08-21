package com.rencon.biwaswim

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.rencon.biwaswim.bluetooth.BluetoothGpsListener
import com.rencon.biwaswim.bluetooth.BluetoothGpsManager
import com.rencon.biwaswim.map.MapManager
import com.rencon.biwaswim.nmea.GpsLocation
import com.rencon.biwaswim.nmea.NmeaParser
import com.rencon.biwaswim.permission.checkPermission
import com.rencon.biwaswim.usb.UsbSerialListener
import com.rencon.biwaswim.usb.UsbSerialManager
import org.maplibre.android.MapLibre

class MainActivity : AppCompatActivity(), UsbSerialListener, BluetoothGpsListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var connectionStatus: TextView
    private lateinit var mapManager: MapManager
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var bluetoothGpsManager: BluetoothGpsManager
    private val nmeaParser = NmeaParser()
    private lateinit var context: Context
    private var openedSettings = false
    private val dialogs = mutableListOf<AlertDialog>()

    private var isUsbConnected = false
    private var isBluetoothConnected = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants[Manifest.permission.BLUETOOTH_CONNECT]?.let { granted ->
            if (!granted) {
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
                add(Manifest.permission.BLUETOOTH_CONNECT)
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
        requestPermissions()
    }

    private fun setupClasses() {
        // USB接続初期化
        usbSerialManager = UsbSerialManager(context, this)
        usbSerialManager.registerReceiver()
        usbSerialManager.connect()

        // Bluetooth接続初期化
        bluetoothGpsManager = BluetoothGpsManager(context, this)
        val autoConnected = bluetoothGpsManager.connectAuto()
        Log.d(TAG, "Bluetooth auto connect initiated: $autoConnected")
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

    // --- UsbSerialListener 実装 ---

    override fun onConnected() {
        isUsbConnected = true
        updateOverallConnectionStatus()
    }

    override fun onDisconnected() {
        isUsbConnected = false
        updateOverallConnectionStatus()
    }

    override fun onRawDataReceived(data: ByteArray) {
        val locations = nmeaParser.parseRawData(data)
        if (locations.isNotEmpty()) {
            val latestLocation = locations.last()
            runOnUiThread {
                mapManager.updateLocation(
                    latitude = latestLocation.latitude,
                    longitude = latestLocation.longitude
                )
            }
        }
    }

    override fun onError(exception: Exception) {
        isUsbConnected = false
        updateOverallConnectionStatus()
    }

    // --- BluetoothGpsListener 実装 ---

    override fun onBluetoothConnected(device: BluetoothDevice) {
        isBluetoothConnected = true
        Log.d(TAG, "Bluetooth GNSS connected: ${device.name ?: device.address}")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothDisconnected() {
        isBluetoothConnected = false
        Log.d(TAG, "Bluetooth GNSS disconnected")
        updateOverallConnectionStatus()
    }

    override fun onBluetoothError(exception: Exception) {
        isBluetoothConnected = false
        Log.e(TAG, "Bluetooth GNSS error: ${exception.message}", exception)
        updateOverallConnectionStatus()
    }

    override fun onLocationReceived(location: GpsLocation) {
        runOnUiThread {
            mapManager.updateLocation(
                latitude = location.latitude,
                longitude = location.longitude
            )
        }
    }

    private fun updateOverallConnectionStatus() {
        val isConnected = isUsbConnected || isBluetoothConnected
        runOnUiThread {
            if (::connectionStatus.isInitialized) {
                val colorRes = if (isConnected) R.color.connected else R.color.disconnected
                val textRes = if (isConnected) R.string.connected else R.string.disconnected

                connectionStatus.setBackgroundColor(ContextCompat.getColor(this, colorRes))
                connectionStatus.text = ContextCompat.getString(this, textRes)
            }
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
        mapManager.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapManager.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
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