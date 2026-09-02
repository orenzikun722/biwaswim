package com.rencon.biwaswim.disaster.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.rencon.biwaswim.R
import com.rencon.biwaswim.disaster.model.DisasterAlertMessage
import com.rencon.biwaswim.notification.sendNotification
import com.rencon.biwaswim.vibration.VibrationHelper

/**
 * 災害アラート（EEW / 地震情報）のモダンなUI表示・アニメーション・バイブレーション・通知を制御するヘルパー
 */
class DisasterAlertUiHelper(
    private val context: Context,
    private val bannerCard: CardView,
    private val vibrationHelper: VibrationHelper?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAlert: DisasterAlertMessage? = null
    private var dismissRunnable: Runnable? = null
    private var pulseAnimator: AnimatorSet? = null

    // バナー内のビュー群
    private val bannerBackground: LinearLayout = bannerCard.findViewById(R.id.bannerBackground)
    private val textAlertTag: TextView = bannerCard.findViewById(R.id.textAlertTag)
    private val textSerial: TextView = bannerCard.findViewById(R.id.textSerial)
    private val textAlertTime: TextView = bannerCard.findViewById(R.id.textAlertTime)
    private val btnBannerClose: ImageButton = bannerCard.findViewById(R.id.btnBannerClose)
    private val scaleBadgeContainer: LinearLayout = bannerCard.findViewById(R.id.scaleBadgeContainer)
    private val labelScaleHeader: TextView = bannerCard.findViewById(R.id.labelScaleHeader)
    private val textScaleValue: TextView = bannerCard.findViewById(R.id.textScaleValue)
    private val textAlertSummary: TextView = bannerCard.findViewById(R.id.textAlertSummary)
    private val chipHypocenter: TextView = bannerCard.findViewById(R.id.chipHypocenter)
    private val chipMagnitude: TextView = bannerCard.findViewById(R.id.chipMagnitude)
    private val chipDepth: TextView = bannerCard.findViewById(R.id.chipDepth)

    init {
        bannerCard.visibility = View.GONE
        btnBannerClose.setOnClickListener {
            hideAlertBanner()
        }
        bannerCard.setOnClickListener {
            currentAlert?.let { alert ->
                showDetailDialog(alert)
            }
        }
    }

    /**
     * 新しい災害アラートを受け取り、バナー表示・アニメーション・バイブ・通知を発動
     */
    fun showAlert(alert: DisasterAlertMessage) {
        currentAlert = alert

        // 1. バナービューのコンテンツ設定
        setupBannerContent(alert)

        // 2. バイブレーションと通知の発行
        triggerHapticAndNotification(alert)

        // 3. アニメーション付きでバナーを表示
        animateBannerIn()

        // 4. 自動非表示タイマー（EEWは35秒、通常地震情報は20秒）
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        val autoHideDelayMs = if (alert.isEEW) 35_000L else 20_000L
        dismissRunnable = Runnable {
            hideAlertBanner()
        }.also {
            mainHandler.postDelayed(it, autoHideDelayMs)
        }
    }

    private fun setupBannerContent(alert: DisasterAlertMessage) {
        // グラデーション背景の動的生成
        val (startColor, endColor) = alert.gradientColors
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = 16f * context.resources.displayMetrics.density
            setStroke((1.5f * context.resources.displayMetrics.density).toInt(), Color.parseColor("#66FFFFFF"))
        }
        bannerBackground.background = gradient

        // タグ（EEW or Quake）
        if (alert.isEEW) {
            textAlertTag.text = "🚨 緊急地震速報 (EEW)"
            labelScaleHeader.text = "予想震度"
        } else {
            textAlertTag.text = "⚠️ 地震情報"
            labelScaleHeader.text = "最大震度"
        }

        // 第何報
        val serial = alert.serialText
        if (serial.isNotEmpty()) {
            textSerial.visibility = View.VISIBLE
            textSerial.text = serial
        } else {
            textSerial.visibility = View.GONE
        }

        // 時刻
        textAlertTime.text = alert.eventTimeText.takeLast(8)

        // 震度バッジ
        val scaleStr = alert.displayScale
        textScaleValue.text = scaleStr
        val accentColor = Color.parseColor(alert.accentColorHex)
        textScaleValue.setTextColor(accentColor)
        labelScaleHeader.setTextColor(accentColor)

        // サマリー
        textAlertSummary.text = alert.summary ?: "地震が検知されました"

        // チップ
        chipHypocenter.text = "震源: ${alert.hypocenterName}"
        chipMagnitude.text = alert.magnitudeText
        chipDepth.text = alert.depthText
    }

    private fun triggerHapticAndNotification(alert: DisasterAlertMessage) {
        // バイブレーション（EEWなら即座に警告振動）
        vibrationHelper?.vibrateDistinctWarning()

        // システム通知
        val title = if (alert.isEEW) "🚨 【緊急地震速報】予想震度 ${alert.displayScale}" else "⚠️ 【地震情報】最大震度 ${alert.displayScale}"
        val body = alert.summary ?: "${alert.hypocenterName} ${alert.magnitudeText}"
        sendNotification(
            context = context,
            channelid = "disaster_alert_channel",
            title = title,
            message = body,
            isOnGoing = false
        )
    }

    private fun animateBannerIn() {
        pulseAnimator?.cancel()
        bannerCard.visibility = View.VISIBLE
        bannerCard.alpha = 0f
        bannerCard.translationY = -80f * context.resources.displayMetrics.density
        bannerCard.scaleX = 0.92f
        bannerCard.scaleY = 0.92f

        val slideIn = ObjectAnimator.ofFloat(bannerCard, View.TRANSLATION_Y, -80f * context.resources.displayMetrics.density, 0f).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.2f)
        }
        val fadeIn = ObjectAnimator.ofFloat(bannerCard, View.ALPHA, 0f, 1f).apply {
            duration = 350
        }
        val scaleX = ObjectAnimator.ofFloat(bannerCard, View.SCALE_X, 0.92f, 1f).apply {
            duration = 400
        }
        val scaleY = ObjectAnimator.ofFloat(bannerCard, View.SCALE_Y, 0.92f, 1f).apply {
            duration = 400
        }

        AnimatorSet().apply {
            playTogether(slideIn, fadeIn, scaleX, scaleY)
            start()
        }

        // バッジのパルス発光アニメーション
        startBadgePulse()
    }

    private fun startBadgePulse() {
        val pulseX = ObjectAnimator.ofFloat(scaleBadgeContainer, View.SCALE_X, 1f, 1.15f, 1f).apply {
            duration = 800
            repeatCount = 4
            interpolator = AccelerateDecelerateInterpolator()
        }
        val pulseY = ObjectAnimator.ofFloat(scaleBadgeContainer, View.SCALE_Y, 1f, 1.15f, 1f).apply {
            duration = 800
            repeatCount = 4
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimator = AnimatorSet().apply {
            playTogether(pulseX, pulseY)
            start()
        }
    }

    fun hideAlertBanner() {
        pulseAnimator?.cancel()
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }

        val slideOut = ObjectAnimator.ofFloat(bannerCard, View.TRANSLATION_Y, 0f, -80f * context.resources.displayMetrics.density).apply {
            duration = 300
        }
        val fadeOut = ObjectAnimator.ofFloat(bannerCard, View.ALPHA, 1f, 0f).apply {
            duration = 250
        }
        AnimatorSet().apply {
            playTogether(slideOut, fadeOut)
            start()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bannerCard.visibility = View.GONE
                }
            })
        }
    }

    /**
     * 詳細モーダルダイアログの表示
     */
    fun showDetailDialog(alert: DisasterAlertMessage) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_disaster_detail, null)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ダイアログ内のビュー取得
        val headerLayout = dialogView.findViewById<LinearLayout>(R.id.dialogHeaderLayout)
        val textTitle = dialogView.findViewById<TextView>(R.id.dialogTextTitle)
        val textSerial = dialogView.findViewById<TextView>(R.id.dialogTextSerial)
        val textSummary = dialogView.findViewById<TextView>(R.id.dialogTextSummary)
        val detailHypocenter = dialogView.findViewById<TextView>(R.id.detailHypocenter)
        val detailMaxScale = dialogView.findViewById<TextView>(R.id.detailMaxScale)
        val detailMagnitude = dialogView.findViewById<TextView>(R.id.detailMagnitude)
        val detailDepth = dialogView.findViewById<TextView>(R.id.detailDepth)
        val detailTime = dialogView.findViewById<TextView>(R.id.detailTime)
        val sectionAreas = dialogView.findViewById<LinearLayout>(R.id.sectionAreasLayout)
        val containerAreas = dialogView.findViewById<LinearLayout>(R.id.containerAreaItems)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnDialogClose)

        // ヘッダー背景グラデーション
        val (startColor, endColor) = alert.gradientColors
        headerLayout.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        )

        // タイトル
        textTitle.text = if (alert.isEEW) "🚨 緊急地震速報 (EEW)" else "⚠️ 地震情報"
        if (alert.serialText.isNotEmpty()) {
            textSerial.visibility = View.VISIBLE
            textSerial.text = alert.serialText
        } else {
            textSerial.visibility = View.GONE
        }
        textSummary.text = alert.summary ?: ""

        // 震源詳細
        detailHypocenter.text = alert.hypocenterName
        detailMaxScale.text = "震度 ${alert.displayScale}"
        detailMaxScale.setTextColor(Color.parseColor(alert.accentColorHex))
        detailMagnitude.text = alert.magnitudeText
        detailDepth.text = alert.depthText
        detailTime.text = alert.eventTimeText

        // 対象地域リスト
        val areas = alert.data?.areas
        if (!areas.isNullOrEmpty()) {
            sectionAreas.visibility = View.VISIBLE
            containerAreas.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for (area in areas) {
                val itemView = inflater.inflate(R.layout.item_disaster_area, containerAreas, false)
                val textScale = itemView.findViewById<TextView>(R.id.textAreaScale)
                val textPref = itemView.findViewById<TextView>(R.id.textAreaPref)
                val textName = itemView.findViewById<TextView>(R.id.textAreaName)

                textScale.text = area.displayScale
                textPref.text = area.pref ?: ""
                textName.text = area.name ?: ""

                // 震度色
                val areaScaleVal = area.scaleTo ?: area.scaleFrom ?: 30
                val areaColor = when {
                    areaScaleVal >= 50 -> Color.parseColor("#D50000")
                    areaScaleVal >= 40 -> Color.parseColor("#FF6D00")
                    else -> Color.parseColor("#FFAB00")
                }
                textScale.setTextColor(areaColor)

                containerAreas.addView(itemView)
            }
        } else {
            sectionAreas.visibility = View.GONE
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
