package com.rencon.biwaswim.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * デバイスのバイブレーション制御を行うヘルパークラス。
 * Android 12 (API 31) 以降の VibratorManager および従来 API の両方に対応します。
 */
class VibrationHelper(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * 岸から離れすぎた際の明確で強い警告振動パターン（強パルス）を実行します。
     * パターン: 500ms振動 - 150ms休止 - 500ms振動 - 150ms休止 - 800ms振動 (合計2.1秒)
     */
    fun vibrateDistinctWarning() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val timings = longArrayOf(0, 500, 150, 500, 150, 800)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)

        val effect = if (vib.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        vib.vibrate(effect)
    }

    /**
     * 進行中の振動を停止します。
     */
    fun cancel() {
        vibrator?.cancel()
    }
}
