package com.rencon.biwaswim.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rencon.biwaswim.nmea.NmeaLineBuffer
import com.rencon.biwaswim.nmea.NmeaParseDetail
import com.rencon.biwaswim.nmea.NmeaParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bluetooth GNSS 受信機との接続管理、デバイス探索（RSSI電波強度ソート）、データ受信、NMEAパース、位置情報通知を行うマネージャークラス。
 */
class BluetoothGpsManager(
    private val context: Context,
    private val listener: BluetoothGpsListener
) {
    companion object {
        private const val TAG = "BluetoothGpsManager"
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectionJob: Job? = null
    private var sppSession: SppSession? = null
    private val nmeaParser = NmeaParser()
    private val lineBuffer = NmeaLineBuffer()

    private val discoveredDevices = LinkedHashMap<String, DiscoveredBluetoothDevice>()
    private var isReceiverRegistered = false
    private var isAutoConnectOnDiscovery = false

    var connectedDevice: BluetoothDevice? = null
        private set

    val isConnected: Boolean
        get() = sppSession != null && connectionJob?.isActive == true

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val name = try {
                            device.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: "Unknown"
                        } catch (e: Exception) {
                            intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: "Unknown"
                        }
                        val address = device.address ?: ""

                        val item = DiscoveredBluetoothDevice(
                            device = device,
                            name = name,
                            address = address,
                            rssi = rssi
                        )
                        discoveredDevices[address] = item
                        val sortedList = getSortedDiscoveredDevices()
                        listener.onDeviceDiscovered(item, sortedList)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Bluetooth discovery started")
                    listener.onDiscoveryStarted()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Bluetooth discovery finished. Discovered ${discoveredDevices.size} devices.")
                    val sortedList = getSortedDiscoveredDevices()
                    listener.onDiscoveryFinished(sortedList)

                    if (isAutoConnectOnDiscovery && !isConnected) {
                        handleAutoConnectAfterDiscovery(sortedList)
                    }
                }
            }
        }
    }

    /**
     * ペアリング済みの Bluetooth デバイス一覧を取得します。
     * GNSS受信機（QZ1等）が優先されるようにソートします。
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return try {
            adapter.bondedDevices.sortedWith(
                compareByDescending<BluetoothDevice> { device ->
                    val name = device.name ?: ""
                    name.contains("QZ1", ignoreCase = true) ||
                            name.contains("GNSS", ignoreCase = true) ||
                            name.contains("GPS", ignoreCase = true)
                }.thenBy { it.name ?: "" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting paired devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * 検出されたデバイスを電波強度（RSSI）の降順（強い順）でソートして取得します。
     * QZ1やGNSS機器を優先的に上位に並べます。
     */
    fun getSortedDiscoveredDevices(): List<DiscoveredBluetoothDevice> {
        return discoveredDevices.values.sortedWith(
            compareByDescending<DiscoveredBluetoothDevice> { it.isQz1OrGnss }
                .thenByDescending { it.rssi }
                .thenBy { it.name }
        )
    }

    /**
     * 周囲の Bluetooth デバイスの探索（Discovery）を開始します。
     * @param autoConnect 探索完了時に1台のみ検出された場合等に自動接続するかどうか
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(autoConnect: Boolean = true) {
        val adapter = bluetoothAdapter ?: run {
            listener.onBluetoothError(IllegalStateException("Bluetooth adapter is unavailable"))
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled")
            return
        }

        isAutoConnectOnDiscovery = autoConnect
        discoveredDevices.clear()

        // ペアリング済みデバイスもあらかじめ初期リストに追加（電波強度はスキャンで更新）
        try {
            for (bonded in adapter.bondedDevices) {
                val name = bonded.name ?: "Unknown"
                val address = bonded.address ?: ""
                discoveredDevices[address] = DiscoveredBluetoothDevice(
                    device = bonded,
                    name = name,
                    address = address,
                    rssi = -100
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get bonded devices during startDiscovery: ${e.message}")
        }

        registerDiscoveryReceiver()

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        val started = adapter.startDiscovery()
        Log.d(TAG, "startDiscovery initiated: $started")
    }

    /**
     * Bluetooth 探索をキャンセルします。
     */
    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            val adapter = bluetoothAdapter
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel discovery: ${e.message}")
        }
    }

    private fun handleAutoConnectAfterDiscovery(devices: List<DiscoveredBluetoothDevice>) {
        val qz1Devices = devices.filter { it.isQz1OrGnss }
        when {
            qz1Devices.size >= 2 -> {
                Log.d(TAG, "Multiple QZ1/GNSS devices found (${qz1Devices.size}). Showing selection dialog.")
                listener.onMultipleDevicesFound(devices)
            }
            qz1Devices.size == 1 -> {
                Log.d(TAG, "Single QZ1/GNSS device found: ${qz1Devices.first().name}. Connecting automatically.")
                connect(qz1Devices.first().device)
            }
            devices.size >= 2 -> {
                Log.d(TAG, "Multiple Bluetooth devices found (${devices.size}). Showing selection dialog.")
                listener.onMultipleDevicesFound(devices)
            }
            devices.size == 1 -> {
                Log.d(TAG, "Single Bluetooth device found: ${devices.first().name}. Connecting automatically.")
                connect(devices.first().device)
            }
            else -> {
                Log.d(TAG, "No Bluetooth device found during discovery. Trying paired devices fallback.")
                connectAuto()
            }
        }
    }

    private fun registerDiscoveryReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    discoveryReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterDiscoveryReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering discovery receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }

    /**
     * アドレスを指定して Bluetooth デバイスに接続します。
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: run {
            listener.onBluetoothError(IllegalStateException("Bluetooth adapter is unavailable"))
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            listener.onBluetoothError(e)
            return
        }
        connect(device)
    }

    /**
     * 指定された Bluetooth デバイスに接続します。
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        cancelDiscovery()
        disconnect()

        lineBuffer.clear()
        val session = SppSession(device)
        sppSession = session
        connectedDevice = device

        connectionJob = scope.launch {
            try {
                Log.d(TAG, "Connecting to ${device.name ?: device.address}...")
                session.open()
                Log.d(TAG, "Connected to ${device.name ?: device.address}")
                listener.onBluetoothConnected(device)

                val stopReason = session.readLoop { chunk ->
                    val lines = lineBuffer.append(chunk.text)
                    for (line in lines) {
                        val detail = nmeaParser.parseSentenceDetail(line)
                        withContext(Dispatchers.Main) {
                            listener.onRawNmeaReceived(line)
                            listener.onNmeaParseDetail(detail)
                            if (detail is NmeaParseDetail.LocationUpdate) {
                                listener.onLocationReceived(detail.location)
                            }
                        }
                    }
                }

                Log.d(TAG, "Read loop finished with reason: $stopReason")
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Bluetooth connection error: ${e.message}", e)
                    listener.onBluetoothError(e)
                }
            } finally {
                session.close()
                sppSession = null
                connectedDevice = null
                withContext(Dispatchers.Main) {
                    listener.onBluetoothDisconnected()
                }
            }
        }
    }

    /**
     * 初回検出されたGNSS候補またはペアリング済みデバイスに自動接続します。
     */
    @SuppressLint("MissingPermission")
    fun connectAuto(): Boolean {
        val paired = getPairedDevices()
        if (paired.isEmpty()) return false
        connect(paired.first())
        return true
    }

    /**
     * 接続を切断します。
     */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        sppSession?.close()
        sppSession = null
        connectedDevice = null
        lineBuffer.clear()
    }

    /**
     * マネージャーを破棄し、コルーチンスコープをキャンセルします。
     */
    fun release() {
        cancelDiscovery()
        unregisterDiscoveryReceiver()
        disconnect()
        scope.cancel()
    }
}
