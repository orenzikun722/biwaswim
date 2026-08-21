package com.rencon.biwaswim.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * USBシリアル通信のパーミッション要求、デバイス探索、接続/切断、データ送受信を管理するクラス。
 */
class UsbSerialManager(
    private val context: Context,
    private val listener: UsbSerialListener
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val BAUDRATE = 115200
    }

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val actionUsbPermission: String by lazy {
        "${context.packageName}.USB_PERMISSION"
    }
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    synchronized(this) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            Log.d(TAG, "Permission granted")
                            connect()
                        } else {
                            Log.d(TAG, "Permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached broadcast received")
                    connect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB device detached broadcast received")
                    disconnect()
                }
            }
        }
    }

    /**
     * USBの接続・切断・パーミッション監視用の BroadcastReceiver を登録します。
     */
    fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(actionUsbPermission)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    usbReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    /**
     * 登録した BroadcastReceiver を解除します。
     */
    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }

    /**
     * USBデバイスを探索し、シリアル接続を開始します。
     */
    fun connect() {
        if (usbSerialPort != null) {
            Log.d(TAG, "Already connected")
            return
        }

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            Log.d(TAG, "Device not found")
            listener.onDisconnected()
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        // パーミッション確認・要求
        if (!manager.hasPermission(device)) {
            Log.d(TAG, "Requesting permission for device: ${device.deviceName}")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(actionUsbPermission).setPackage(context.packageName)
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, intent, flags
            )
            manager.requestPermission(device, permissionIntent)
            return
        }

        openConnection(driver, manager)
    }

    private fun openConnection(driver: UsbSerialDriver, manager: UsbManager) {
        val connection = manager.openDevice(driver.device) ?: run {
            Log.e(TAG, "Failed to open device connection")
            disconnect()
            return
        }

        val port = driver.ports.firstOrNull() ?: run {
            Log.e(TAG, "No serial port available")
            disconnect()
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                BAUDRATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            usbSerialPort = port

            ioManager = SerialInputOutputManager(port, this).also {
                Executors.newSingleThreadExecutor().submit(it)
            }

            Log.d(TAG, "USB connection opened successfully")
            listener.onConnected()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open port: ${e.message}")
            disconnect()
        }
    }

    /**
     * シリアル通信を切断し、リソースを解放します。
     */
    fun disconnect() {
        try {
            ioManager?.listener = null
            ioManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping IO manager: ${e.message}")
        } finally {
            ioManager = null
        }

        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB port: ${e.message}")
        } finally {
            usbSerialPort = null
        }

        listener.onDisconnected()
    }

    override fun onNewData(data: ByteArray) {
        listener.onRawDataReceived(data)
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Connection Error: ${e.message}")
        listener.onError(e)
        disconnect()
    }
}
