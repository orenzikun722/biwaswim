package com.rencon.biwaswim.usb

/**
 * USBシリアル通信の接続状態およびデータ受信を通知するリスナー。
 */
interface UsbSerialListener {
    /**
     * USBデバイスとシリアルポートの接続が確立されたときに呼ばれます。
     */
    fun onConnected()

    /**
     * USBデバイスとの接続が切断されたときに呼ばれます。
     */
    fun onDisconnected()

    /**
     * シリアルポートから生データを受信したときに呼ばれます。
     */
    fun onRawDataReceived(data: ByteArray)

    /**
     * 通信中にエラーが発生したときに呼ばれます。
     */
    fun onError(exception: Exception)
}
