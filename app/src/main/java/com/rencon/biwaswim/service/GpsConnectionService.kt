package com.rencon.biwaswim.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rencon.biwaswim.MainActivity
import com.rencon.biwaswim.R
import com.rencon.biwaswim.bluetooth.BluetoothGpsListener
import com.rencon.biwaswim.bluetooth.BluetoothGpsManager
import com.rencon.biwaswim.bluetooth.DiscoveredBluetoothDevice
import com.rencon.biwaswim.nmea.GpsLocation
import com.rencon.biwaswim.nmea.NmeaParseDetail
import com.rencon.biwaswim.usb.UsbSerialListener
import com.rencon.biwaswim.usb.UsbSerialManager

/**
 * USB/Bluetooth GPS通信をフォアグラウンドサービスとして管理するクラス。
 * - startForeground() でシステムが削除できない Ongoing 通知を常時表示します。
 * - UsbSerialManager と BluetoothGpsManager を内部で保持します。
 * - MainActivity は bindService() でこのサービスに接続し、ServiceListener を通じてイベントを受け取ります。
 */
class GpsConnectionService : Service(), UsbSerialListener, BluetoothGpsListener {

    companion object {
        private const val TAG = "GpsConnectionService"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val CHANNEL_ID_SERVICE = "gpsConnectionService"
    }

    // --- Binder ---

    inner class LocalBinder : Binder() {
        fun getService(): GpsConnectionService = this@GpsConnectionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // --- ServiceListener ---

    /**
     * サービスのイベントを MainActivity に通知するリスナー。
     */
    interface ServiceListener :
        UsbSerialListener,
        BluetoothGpsListener

    private var serviceListener: ServiceListener? = null

    fun setServiceListener(listener: ServiceListener?) {
        serviceListener = listener
    }

    // --- 状態 ---

    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var bluetoothGpsManager: BluetoothGpsManager

    var connectedBluetoothDeviceName: String? = null
        private set
    var isUsbConnected: Boolean = false
        private set
    var isBtConnected: Boolean = false
        private set

    // --- 遊泳トラッキング状態 ---
    private var isSwimmingActive: Boolean = false
    private var swimElapsedText: String? = null
    private var swimDistanceText: String? = null

    // --- ライフサイクル ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceWithNotification()

        usbSerialManager = UsbSerialManager(this, this)
        usbSerialManager.registerReceiver()
        usbSerialManager.connect()

        bluetoothGpsManager = BluetoothGpsManager(this, this)
        bluetoothGpsManager.startDiscovery(autoConnect = true)

        Log.d(TAG, "GpsConnectionService created and foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        usbSerialManager.unregisterReceiver()
        usbSerialManager.disconnect()
        bluetoothGpsManager.release()
        Log.d(TAG, "GpsConnectionService destroyed")
    }

    // --- 通知チャンネル ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_notification_title)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    // --- 通知生成と更新 ---

    private fun getConnectionStatusText(): String {
        return when {
            isUsbConnected && isBtConnected ->
                getString(R.string.service_notification_text_both_connected)
            isUsbConnected ->
                getString(R.string.service_notification_text_usb_connected)
            isBtConnected ->
                getString(
                    R.string.service_notification_text_bt_connected,
                    connectedBluetoothDeviceName ?: "BT"
                )
            else ->
                getString(R.string.service_notification_text_disconnected)
        }
    }

    private fun createForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val contentText: String
        val subText: String?

        if (isSwimmingActive && swimElapsedText != null && swimDistanceText != null) {
            title = getString(R.string.swim_notification_title)
            contentText = getString(R.string.swim_notification_text, swimElapsedText, swimDistanceText)
            subText = getConnectionStatusText()
        } else {
            title = getString(R.string.service_notification_title)
            contentText = getConnectionStatusText()
            subText = null
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.baseline_fmd_good_24)
            .setContentTitle(title)
            .setContentText(contentText)
            .apply {
                if (subText != null) {
                    setSubText(subText)
                }
            }
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createForegroundNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun updateForegroundNotification() {
        val notification = createForegroundNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    // --- 公開メソッド（MainActivity から呼び出す） ---

    /**
     * 遊泳トラッキング情報をフォアグラウンドサービス通知に紐づけて更新します。
     */
    fun updateSwimStatus(isSwimming: Boolean, elapsedTimeStr: String? = null, distanceStr: String? = null) {
        this.isSwimmingActive = isSwimming
        this.swimElapsedText = elapsedTimeStr
        this.swimDistanceText = distanceStr
        updateForegroundNotification()
    }

    fun getSortedDiscoveredDevices(): List<DiscoveredBluetoothDevice> =
        bluetoothGpsManager.getSortedDiscoveredDevices()

    fun startBluetoothDiscovery(autoConnect: Boolean = false) {
        bluetoothGpsManager.startDiscovery(autoConnect = autoConnect)
    }

    fun connectBluetooth(device: BluetoothDevice) {
        bluetoothGpsManager.connect(device)
    }

    fun connectUsb() {
        usbSerialManager.connect()
    }

    // --- UsbSerialListener ---

    override fun onConnected() {
        isUsbConnected = true
        updateForegroundNotification()
        serviceListener?.onConnected()
    }

    override fun onDisconnected() {
        isUsbConnected = false
        updateForegroundNotification()
        serviceListener?.onDisconnected()
    }

    override fun onRawDataReceived(data: ByteArray) {
        serviceListener?.onRawDataReceived(data)
    }

    override fun onError(exception: Exception) {
        isUsbConnected = false
        updateForegroundNotification()
        serviceListener?.onError(exception)
    }

    // --- BluetoothGpsListener ---

    @SuppressLint("MissingPermission")
    override fun onBluetoothConnected(device: BluetoothDevice) {
        isBtConnected = true
        connectedBluetoothDeviceName = try {
            device.name ?: device.address
        } catch (e: Exception) {
            device.address
        }
        updateForegroundNotification()
        serviceListener?.onBluetoothConnected(device)
    }

    override fun onBluetoothDisconnected() {
        isBtConnected = false
        connectedBluetoothDeviceName = null
        updateForegroundNotification()
        serviceListener?.onBluetoothDisconnected()
    }

    override fun onBluetoothError(exception: Exception) {
        isBtConnected = false
        connectedBluetoothDeviceName = null
        updateForegroundNotification()
        serviceListener?.onBluetoothError(exception)
    }

    override fun onLocationReceived(location: GpsLocation) {
        serviceListener?.onLocationReceived(location)
    }

    override fun onRawNmeaReceived(line: String) {
        serviceListener?.onRawNmeaReceived(line)
    }

    override fun onNmeaParseDetail(detail: NmeaParseDetail) {
        serviceListener?.onNmeaParseDetail(detail)
    }

    override fun onDiscoveryStarted() {
        serviceListener?.onDiscoveryStarted()
    }

    override fun onDeviceDiscovered(
        device: DiscoveredBluetoothDevice,
        allDevices: List<DiscoveredBluetoothDevice>
    ) {
        serviceListener?.onDeviceDiscovered(device, allDevices)
    }

    override fun onMultipleDevicesFound(devices: List<DiscoveredBluetoothDevice>) {
        serviceListener?.onMultipleDevicesFound(devices)
    }

    override fun onDiscoveryFinished(devices: List<DiscoveredBluetoothDevice>) {
        serviceListener?.onDiscoveryFinished(devices)
    }
}
