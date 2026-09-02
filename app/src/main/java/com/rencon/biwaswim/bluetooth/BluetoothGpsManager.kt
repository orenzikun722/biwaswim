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
        const val PREFS_NAME = "app_data"
        const val KEY_PREVIOUSLY_CONNECTED_BT_DEVICES = "previously_connected_bt_devices"
        const val KEY_LAST_CONNECTED_BT_DEVICE = "last_connected_bt_device"
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
    private val scannedAddressesInCurrentDiscovery = mutableSetOf<String>()
    private var isReceiverRegistered = false
    private var isAutoConnectOnDiscovery = false

    var connectedDevice: BluetoothDevice? = null
        private set

    val isConnected: Boolean
        get() = sppSession != null && connectionJob?.isActive == true

    /**
     * 指定されたMACアドレスのデバイスが過去に接続されたことがあるか（またはシステムでペアリング済みか）判定します。
     */
    fun isPreviouslyConnected(address: String): Boolean {
        if (address.isBlank()) return false
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_PREVIOUSLY_CONNECTED_BT_DEVICES, emptySet()) ?: emptySet()
            if (set.contains(address)) return true
            val last = prefs.getString(KEY_LAST_CONNECTED_BT_DEVICE, null)
            if (last.equals(address, ignoreCase = true)) return true

            // ペアリング済み（Bonded）デバイスも過去に接続・登録されたデバイスとみなす
            val adapter = bluetoothAdapter
            if (adapter != null && adapter.isEnabled) {
                if (adapter.bondedDevices.any { it.address.equals(address, ignoreCase = true) }) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking previously connected device: ${e.message}")
        }
        return false
    }

    /**
     * 接続成功したデバイスのMACアドレスを SharedPreferences に保存します。
     */
    fun markDeviceAsConnected(address: String) {
        if (address.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentSet = HashSet(prefs.getStringSet(KEY_PREVIOUSLY_CONNECTED_BT_DEVICES, emptySet()) ?: emptySet())
            currentSet.add(address)
            prefs.edit()
                .putStringSet(KEY_PREVIOUSLY_CONNECTED_BT_DEVICES, currentSet)
                .putString(KEY_LAST_CONNECTED_BT_DEVICE, address)
                .apply()
            Log.d(TAG, "Saved device as previously connected: $address")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save connected device to preferences: ${e.message}")
        }
    }

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

                        if (address.isNotEmpty()) {
                            scannedAddressesInCurrentDiscovery.add(address)
                        }

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
                    Log.d(
                        TAG,
                        "Bluetooth discovery finished. Discovered ${discoveredDevices.size} devices (${scannedAddressesInCurrentDiscovery.size} scanned in range)."
                    )
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
     * ペアリング済みの Bluetooth デバイス一覧を取得します（QZ1から始まるデバイスのみ）。
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return try {
            adapter.bondedDevices.filter { device ->
                (device.name ?: "").startsWith("QZ1", ignoreCase = true)
            }.sortedWith(
                compareByDescending<BluetoothDevice> { isPreviouslyConnected(it.address) }
                    .thenBy { it.name ?: "" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting paired devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * 検出されたデバイスのうちQZ1から始まるデバイスのみを、過去の接続履歴、電波強度（RSSI）の降順（強い順）でソートして取得します。
     */
    fun getSortedDiscoveredDevices(): List<DiscoveredBluetoothDevice> {
        return discoveredDevices.values.filter { device ->
            device.name.startsWith("QZ1", ignoreCase = true)
        }.sortedWith(
            compareByDescending<DiscoveredBluetoothDevice> { isPreviouslyConnected(it.address) }
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
        scannedAddressesInCurrentDiscovery.clear()

        // ペアリング済みデバイスのうちQZ1から始まるデバイスのみ初期リストに追加（電波強度はスキャンで更新）
        try {
            for (bonded in adapter.bondedDevices) {
                val name = bonded.name ?: "Unknown"
                if (name.startsWith("QZ1", ignoreCase = true)) {
                    val address = bonded.address ?: ""
                    discoveredDevices[address] = DiscoveredBluetoothDevice(
                        device = bonded,
                        name = name,
                        address = address,
                        rssi = -100
                    )
                }
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

    /**
     * スキャン完了時の自動接続判定処理。
     * 「周囲（電波範囲内）にQZ1デバイスが1台のみ存在し、かつ以前に接続したことがある場合」に自動接続します。
     */
    private fun handleAutoConnectAfterDiscovery(allSortedDevices: List<DiscoveredBluetoothDevice>) {
        // 周囲で実際にスキャン電波（ACTION_FOUND）を受信したQZ1デバイス
        val nearbyQz1Devices = allSortedDevices.filter {
            it.address in scannedAddressesInCurrentDiscovery && it.name.startsWith("QZ1", ignoreCase = true)
        }

        Log.d(
            TAG,
            "handleAutoConnectAfterDiscovery: nearby QZ1 total=${nearbyQz1Devices.size}"
        )

        when {
            nearbyQz1Devices.size == 1 -> {
                val singleDevice = nearbyQz1Devices.first()
                if (isPreviouslyConnected(singleDevice.address)) {
                    Log.d(
                        TAG,
                        "Single QZ1 device found around and previously connected: ${singleDevice.name} (${singleDevice.address}). Auto-connecting."
                    )
                    connect(singleDevice.device)
                } else {
                    Log.d(
                        TAG,
                        "Single QZ1 device found around but NOT previously connected: ${singleDevice.name} (${singleDevice.address}). Showing selection dialog."
                    )
                    listener.onMultipleDevicesFound(allSortedDevices)
                }
            }
            nearbyQz1Devices.size >= 2 -> {
                Log.d(
                    TAG,
                    "Multiple QZ1 devices found around (${nearbyQz1Devices.size}). Showing selection dialog."
                )
                listener.onMultipleDevicesFound(allSortedDevices)
            }
            else -> {
                Log.d(TAG, "No nearby QZ1 devices found during scan. Auto-connect skipped.")
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
                markDeviceAsConnected(device.address)
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
