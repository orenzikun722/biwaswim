package com.rencon.biwaswim.nmea

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * ストリームから分割されて届くバイト列/文字列をバッファリングし、
 * 改行区切りの完全な NMEA センテンス（$ または ! で始まる行）として抽出するクラス。
 */
class NmeaLineBuffer {
    private val pending = StringBuilder()

    /**
     * 文字列を追加し、完成した NMEA 行のリストを返します。
     */
    fun append(text: String): List<String> {
        synchronized(pending) {
            pending.append(text)
            val lines = pending.toString().split('\n')
            pending.clear()
            pending.append(lines.lastOrNull() ?: "")

            return lines
                .dropLast(1)
                .map { it.trim() }
                .filter { it.startsWith("$") || it.startsWith("!") }
        }
    }

    /**
     * バイト列を追加し、完成した NMEA 行のリストを返します。
     */
    fun append(data: ByteArray, charset: Charset = StandardCharsets.US_ASCII): List<String> {
        return append(String(data, charset))
    }

    /**
     * バッファをクリアします。
     */
    fun clear() {
        synchronized(pending) {
            pending.clear()
        }
    }
}
