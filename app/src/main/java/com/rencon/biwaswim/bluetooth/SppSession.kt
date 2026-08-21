package com.rencon.biwaswim.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Bluetooth SPP (Serial Port Profile) を使用して GNSS 受信機とのソケット通信を行うクラス。
 * chooblarin/gnss-tracker-demo の方式に準拠。
 */
class SppSession(private val device: BluetoothDevice) {
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    suspend fun open() {
        socket = withContext(Dispatchers.IO) {
            connectSpp(device)
        }
    }

    /**
     * ソケットからデータを読み込み、チャンクごとにコールバックを呼び出すループ。
     */
    suspend fun readLoop(onChunk: suspend (ReceivedChunk) -> Unit): ReadStopReason {
        val connectedSocket = socket ?: return ReadStopReason.NotConnected

        return withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            var stopReason: ReadStopReason? = null
            while (stopReason == null) {
                ensureActive()
                val count = try {
                    connectedSocket.inputStream.read(buffer)
                } catch (e: IOException) {
                    stopReason = ReadStopReason.StreamClosed
                    break
                }

                if (count < 0) {
                    stopReason = ReadStopReason.StreamClosed
                    continue
                }
                if (count == 0) continue

                onChunk(
                    ReceivedChunk(
                        text = String(buffer, 0, count, StandardCharsets.US_ASCII),
                        data = buffer.copyOf(count),
                        byteCount = count
                    )
                )
            }
            stopReason ?: ReadStopReason.StreamClosed
        }
    }

    /**
     * ソケットを切断し、リソースを解放します。
     */
    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    @SuppressLint("MissingPermission")
    private fun connectSpp(device: BluetoothDevice): BluetoothSocket {
        val secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            secureSocket.connect()
            return secureSocket
        } catch (secureError: IOException) {
            secureSocket.closeQuietly()

            val insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                insecureSocket.connect()
                return insecureSocket
            } catch (insecureError: IOException) {
                insecureSocket.closeQuietly()
                throw IOException(
                    "secure=${secureError.message ?: "failed"}, insecure=${insecureError.message ?: "failed"}",
                    insecureError
                )
            }
        }
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

data class ReceivedChunk(
    val text: String,
    val data: ByteArray,
    val byteCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReceivedChunk
        if (text != other.text) return false
        if (!data.contentEquals(other.data)) return false
        if (byteCount != other.byteCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + byteCount
        return result
    }
}

enum class ReadStopReason {
    NotConnected,
    StreamClosed
}
