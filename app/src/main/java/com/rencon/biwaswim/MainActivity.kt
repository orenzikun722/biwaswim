package com.rencon.biwaswim

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.concurrent.Executors
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val actionUsbPermission by lazy { "${packageName}.USB_PERMISSION" }
    private var isReceiverRegistered = false

    private lateinit var markerSource: GeoJsonSource
    private lateinit var markerLayer: SymbolLayer

    private var latitude: Double by Delegates.observable(0.0) { _, _, newValue ->
        if (::markerSource.isInitialized) {
            markerSource.setGeoJson(Point.fromLngLat(longitude, newValue))
        }
        if (::markerLayer.isInitialized) {
            markerLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        }
    }

    private var longitude: Double by Delegates.observable(0.0) { _, _, newValue ->
        if (::markerSource.isInitialized) {
            markerSource.setGeoJson(Point.fromLngLat(newValue, latitude))
        }
        if (::markerLayer.isInitialized) {
            markerLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        }
    }

    private lateinit var connectionStatus: TextView
    private lateinit var mapView: MapView
    private val buffer = StringBuilder()

    private val styleJson = """
        {
          "version": 8,
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": [
                "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg"
              ],
              "tileSize": 256
            }
          },
          "layers": [
            {
              "id": "satellite",
              "type": "raster",
              "source": "satellite"
            }
          ]
        }
    """.trimIndent()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    synchronized(this) {
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            Log.d("USB", "Permission granted")
                            connectUsbDevice()
                        } else {
                            Log.d("USB", "Permission denied")
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d("USB", "USB device attached broadcast received")
                    connectUsbDevice()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d("USB", "USB device detached broadcast received")
                    disconnectUsbDevice()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. MapLibre の初期化
        MapLibre.getInstance(this)

        // 2. レイアウトの読み込み
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        connectionStatus = findViewById(R.id.connectionStatus)

        // 3. View の取得と各種初期化
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            val style = Style.Builder().fromJson(styleJson)
            map.setStyle(style) { loadedStyle ->
                val point = Point.fromLngLat(longitude, latitude)
                val feature = Feature.fromGeometry(point)
                val featureCollection = FeatureCollection.fromFeature(feature)
                val source = GeoJsonSource("marker-source", featureCollection)

                loadedStyle.addSource(source)
                val bitmap = BitmapFactory.decodeResource(resources, R.drawable.marker)
                loadedStyle.addImage("marker-icon", bitmap)

                val layer = SymbolLayer("marker-layer", "marker-source").apply {
                    setProperties(
                        PropertyFactory.iconImage("marker-icon"),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.visibility(Property.NONE)
                    )
                }

                loadedStyle.addLayer(layer)
                markerSource = loadedStyle.getSourceAs("marker-source")!!
                markerLayer = loadedStyle.getLayerAs("marker-layer")!!
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(35.25, 136.1))
                .zoom(10.0)
                .tilt(60.0)
                .build()
        }

        registerUsbReceiver()
        connectUsbDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            Log.d("USB", "onNewIntent: USB device attached")
            connectUsbDevice()
        }
    }

    fun connectUsbDevice() {
        if (usbSerialPort != null) {
            Log.d("USB", "Already connected")
            return
        }

        val manager = getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            Log.d("USB", "Device not found")
            updateConnectionStatus(false)
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        // パーミッション要求
        if (!manager.hasPermission(device)) {
            Log.d("USB", "Requesting permission for device: ${device.deviceName}")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(actionUsbPermission).setPackage(packageName)
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, intent, flags
            )
            manager.requestPermission(device, permissionIntent)
            return
        }

        openConnection(driver, manager)
    }

    private fun openConnection(
        driver: UsbSerialDriver,
        manager: UsbManager
    ) {
        val connection = manager.openDevice(driver.device) ?: run {
            Log.e("USB", "Failed to open device connection")
            disconnectUsbDevice()
            return
        }

        val port = driver.ports.firstOrNull() ?: run {
            Log.e("USB", "No serial port available")
            disconnectUsbDevice()
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                115200,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            usbSerialPort = port

            // 非同期で受信を待ち受ける
            ioManager = SerialInputOutputManager(port, this).also {
                Executors.newSingleThreadExecutor().submit(it)
            }

            updateConnectionStatus(true)
            Log.d("USB", "USB connection opened successfully")
        } catch (e: Exception) {
            Log.e("USB", "Failed to open port: ${e.message}")
            disconnectUsbDevice()
        }
    }

    private fun disconnectUsbDevice() {
        try {
            ioManager?.listener = null
            ioManager?.stop()
        } catch (e: Exception) {
            Log.e("USB", "Error stopping IO manager: ${e.message}")
        } finally {
            ioManager = null
        }

        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e("USB", "Error closing USB port: ${e.message}")
        } finally {
            usbSerialPort = null
        }

        updateConnectionStatus(false)
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        runOnUiThread {
            if (::connectionStatus.isInitialized) {
                if (isConnected) {
                    connectionStatus.setBackgroundColor(
                        ContextCompat.getColor(
                            this,
                            R.color.connected
                        )
                    )
                    connectionStatus.text = ContextCompat.getString(this, R.string.connected)
                } else {
                    connectionStatus.setBackgroundColor(
                        ContextCompat.getColor(
                            this,
                            R.color.disconnected
                        )
                    )
                    connectionStatus.text = ContextCompat.getString(this, R.string.disconnected)
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(actionUsbPermission)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterUsbReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                Log.e("USB", "Error unregistering receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }

    override fun onNewData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)

        synchronized(buffer) {
            buffer.append(text)
            val lines = mutableListOf<String>()
            var newlineIndex: Int

            while (true) {
                newlineIndex = buffer.indexOf("\n")
                if (newlineIndex == -1) break

                var line = buffer.substring(0, newlineIndex)
                if (line.endsWith("\r")) {
                    line = line.dropLast(1)
                }

                lines.add(line)
                buffer.delete(0, newlineIndex + 1)
            }

            if (lines.isNotEmpty()) {
                runOnUiThread {
                    for (line in lines) {
                        if (line.startsWith("\$GNGLL")) {
                            val parts = line.split(",")
                            if (parts.size >= 5) {
                                latitude = DMStoDcimal(parts[1], "latitude")
                                longitude = DMStoDcimal(parts[3], "longitude")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e("USB", "Connection Error: ${e.message}")
        disconnectUsbDevice()
    }

    fun DMStoDcimal(
        dms: String,
        type: String
    ): Double {
        if (type == "latitude") {
            val hour = dms.substring(0, 2).toDouble()
            val minute = dms.substring(2, 4).toDouble()
            val second = dms.substring(4).toDouble()
            val decimal = hour + (minute / 60) + (second / 3600)
            return decimal
        } else if (type == "longitude") {
            val hour = dms.substring(0, 3).toDouble()
            val minute = dms.substring(3, 5).toDouble()
            val second = dms.substring(5).toDouble()
            val decimal = hour + (minute / 60) + (second / 3600)
            return decimal
        } else {
            throw IllegalArgumentException("Invalid type: $type")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbReceiver()
        disconnectUsbDevice()
        mapView.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) {
            mapView.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) {
            mapView.onStop()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) {
            mapView.onSaveInstanceState(outState)
        }
    }
}