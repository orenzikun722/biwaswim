package com.rencon.biwaswim.bluetooth

import android.bluetooth.BluetoothDevice
import com.rencon.biwaswim.nmea.GpsLocation
import com.rencon.biwaswim.nmea.NmeaParseDetail

/**
 * スキャンで検出されたBluetoothデバイス情報。
 */
data class DiscoveredBluetoothDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
) {
    val isQz1OrGnss: Boolean
        get() = name.contains("QZ1", ignoreCase = true) ||
                name.contains("GNSS", ignoreCase = true) ||
                name.contains("GPS", ignoreCase = true)
}

/**
 * Bluetooth GNSS デバイスの接続状態および受信データの通知を受け取るリスナー。
 */
interface BluetoothGpsListener {
    /**
     * Bluetoothデバイスに接続された際に呼ばれます。
     */
    fun onBluetoothConnected(device: BluetoothDevice)

    /**
     * Bluetoothデバイスとの接続が切断された際に呼ばれます。
     */
    fun onBluetoothDisconnected()

    /**
     * NMEAデータからパースされた有効なGPS位置情報を受信した際に呼ばれます。
     */
    fun onLocationReceived(location: GpsLocation)

    /**
     * 生のNMEA行を受信した際に呼ばれます（オプション）。
     */
    fun onRawNmeaReceived(line: String) {}

    /**
     * NMEAセンテンスのパース詳細（位置更新、NoFix、チェックサムエラー、構文異常など）を受信した際に呼ばれます。
     */
    fun onNmeaParseDetail(detail: NmeaParseDetail) {}

    /**
     * 接続や読み取り中にエラーが発生した際に呼ばれます。
     */
    fun onBluetoothError(exception: Exception)

    /**
     * Bluetoothデバイス探索が開始された際に呼ばれます。
     */
    fun onDiscoveryStarted() {}

    /**
     * Bluetoothデバイス探索中に新しいデバイスが検出、またはRSSIが更新された際に呼ばれます。
     */
    fun onDeviceDiscovered(
        device: DiscoveredBluetoothDevice,
        allDevices: List<DiscoveredBluetoothDevice>
    ) {}

    /**
     * 複数の対象デバイス（QZ1等）が検出された際に、電波強度順にソートされたリストとともに呼ばれます。
     */
    fun onMultipleDevicesFound(devices: List<DiscoveredBluetoothDevice>) {}

    /**
     * Bluetoothデバイス探索が完了した際に呼ばれます。
     */
    fun onDiscoveryFinished(devices: List<DiscoveredBluetoothDevice>) {}
}
