package com.rencon.biwaswim.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.rencon.biwaswim.nmea.NmeaLineBuffer
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
 * Bluetooth GNSS 受信機との接続管理、データ受信、NMEAパース、位置情報通知を行うマネージャークラス。
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

    val isConnected: Boolean
        get() = sppSession != null && connectionJob?.isActive == true

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
        disconnect()

        lineBuffer.clear()
        val session = SppSession(device)
        sppSession = session

        connectionJob = scope.launch {
            try {
                Log.d(TAG, "Connecting to ${device.name ?: device.address}...")
                session.open()
                Log.d(TAG, "Connected to ${device.name ?: device.address}")
                listener.onBluetoothConnected(device)

                val stopReason = session.readLoop { chunk ->
                    val lines = lineBuffer.append(chunk.text)
                    for (line in lines) {
                        listener.onRawNmeaReceived(line)
                        val location = nmeaParser.parseSentence(line)
                        if (location != null) {
                            withContext(Dispatchers.Main) {
                                listener.onLocationReceived(location)
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
        lineBuffer.clear()
    }

    /**
     * マネージャーを破棄し、コルーチンスコープをキャンセルします。
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
