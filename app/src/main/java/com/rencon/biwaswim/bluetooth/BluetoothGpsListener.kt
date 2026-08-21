package com.rencon.biwaswim.bluetooth

import android.bluetooth.BluetoothDevice
import com.rencon.biwaswim.nmea.GpsLocation

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
     * 接続や読み取り中にエラーが発生した際に呼ばれます。
     */
    fun onBluetoothError(exception: Exception)
}
