package com.rencon.biwaswim

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.nio.Buffer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val ACTION_USB_PERMISSION = "com.example.app.USB_PERMISSION"
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    fun connectUsbDevice() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            Log.d("USB", "デバイスが見つかりません")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        // パーミッション要求
        if (!manager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            registerReceiver(usbReceiver, filter)
            manager.requestPermission(device, permissionIntent)
            return
        }

        openConnection(driver, manager)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                    val driver = UsbSerialProber.getDefaultProber()
                        .findAllDrivers(manager).firstOrNull() ?: return
                    openConnection(driver, manager)
                } else {
                    Log.d("USB", "パーミッションが拒否されました")
                }
            }
        }
    }

    private fun openConnection(
        driver: com.hoho.android.usbserial.driver.UsbSerialDriver,
        manager: UsbManager
    ) {
        val connection = manager.openDevice(driver.device) ?: run {
            Log.d("USB", "デバイスを開けません")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(
                115200,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
        } catch (e: Exception) {
            Log.e("USB", "オープン失敗: ${e.message}")
            return
        }

        usbSerialPort = port

        // 非同期で受信を待ち受ける
        ioManager = SerialInputOutputManager(port, this).also {
            Executors.newSingleThreadExecutor().submit(it)
        }
    }


    private val buffer = StringBuilder()
    // データ受信コールバック(別スレッドで呼ばれる点に注意)
    override fun onNewData(data: ByteArray) {

        buffer.append(String(data, Charsets.UTF_8))
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
        if(lines.isNotEmpty()) {
            runOnUiThread {
                for (line in lines) {
                    Log.d("USB", "receive: $line")
                    if(line.startsWith("\$GNGNS")){
                        val data = line.split(",")
                        if(data.size == 15){
                            latitude = (data[2].toFloat() * 100).toDouble()
                            longitude = (data[4].toFloat() * 100).toDouble()
                        }
                    }
                }
                // UI更新などはここで
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e("USB", "通信エラー: ${e.message}")
    }

    fun sendData(text: String) {
        usbSerialPort?.write(text.toByteArray(), 1000)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        connectUsbDevice()
    }
    override fun onDestroy() {
        super.onDestroy()
        ioManager?.stop()
        usbSerialPort?.close()
    }
}