package com.rencon.biwaswim

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.rencon.biwaswim.map.MapManager
import com.rencon.biwaswim.nmea.NmeaParser
import com.rencon.biwaswim.usb.UsbSerialListener
import com.rencon.biwaswim.usb.UsbSerialManager

class MainActivity : AppCompatActivity(), UsbSerialListener {

    private lateinit var connectionStatus: TextView
    private lateinit var mapManager: MapManager
    private lateinit var usbSerialManager: UsbSerialManager
    private val nmeaParser = NmeaParser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWindowInsets()

        connectionStatus = findViewById(R.id.connectionStatus)

        // 1. 地図管理クラスの初期化
        mapManager = MapManager(this, findViewById(R.id.mapView))
        mapManager.initialize(savedInstanceState)

        // 2. USBシリアル管理クラスの初期化
        usbSerialManager = UsbSerialManager(this, this)
        usbSerialManager.registerReceiver()
        usbSerialManager.connect()
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
            usbSerialManager.connect()
        }
    }

    // --- UsbSerialListener 実装 ---

    override fun onConnected() {
        updateConnectionStatus(true)
    }

    override fun onDisconnected() {
        updateConnectionStatus(false)
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
        updateConnectionStatus(false)
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
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
        usbSerialManager.unregisterReceiver()
        usbSerialManager.disconnect()
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