package com.rencon.biwaswim.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.rencon.biwaswim.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 遊泳記録のSNSシェア用カード画像生成・保存・共有ヘルパークラス。
 */
object SwimCardGenerator {

    private const val TAG = "SwimCardGenerator"
    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1350

    /**
     * 1枚の自慢・SNSシェア用画像を生成します。
     */
    fun generateCardBitmap(
        context: Context,
        log: SwimLog,
        mapSnapshot: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 背景グラデーション (ディープブルー/スレートブラック)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, CARD_HEIGHT.toFloat(),
                Color.parseColor("#0A1128"),
                Color.parseColor("#001F54"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), bgPaint)

        // 背景アクセント（やわらかな円形グロー効果）
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A00E5FF")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(CARD_WIDTH * 0.85f, CARD_HEIGHT * 0.15f, 320f, glowPaint)
        canvas.drawCircle(CARD_WIDTH * 0.15f, CARD_HEIGHT * 0.85f, 260f, glowPaint)

        // 2. ヘッダーエリア
        var currentY = 70f

        // ブランドバッジ ("🏊 BIWA SWIM")
        val badgeRect = RectF(60f, currentY, 320f, currentY + 54f)
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(badgeRect, 27f, 27f, badgeBgPaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A1128")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🏊 BIWA SWIM", badgeRect.centerX(), currentY + 38f, badgeTextPaint)

        // ユーザー名バッジ（右寄せ）
        val userText = "👤 ${log.userName}"
        val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(userText, CARD_WIDTH - 60f, currentY + 38f, userPaint)

        currentY += 95f

        // メインタイトル
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("SWIM SESSION", 60f, currentY, titlePaint)

        // 日時サブタイトル
        currentY += 45f
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 28f
        }
        canvas.drawText(log.getFormattedDate(), 60f, currentY, datePaint)

        currentY += 45f

        // 3. マップ / 軌跡 ビジュアルエリア (中央)
        val mapAreaWidth = CARD_WIDTH - 120
        val mapAreaHeight = 580
        val mapAreaRect = RectF(60f, currentY, (60 + mapAreaWidth).toFloat(), currentY + mapAreaHeight)

        // マップカード背景
        val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(mapAreaRect, 28f, 28f, mapBgPaint)

        // マップ枠線
        val mapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(mapAreaRect, 28f, 28f, mapStrokePaint)

        // 軌跡またはスナップショット描画
        val trackVisual = if (mapSnapshot != null) {
            fitBitmapToRect(mapSnapshot, mapAreaWidth, mapAreaHeight)
        } else {
            renderTrackOnCanvas(log.trackPoints, mapAreaWidth, mapAreaHeight)
        }

        if (trackVisual != null) {
            canvas.save()
            // クリップして角丸内に収める
            val clipPath = Path().apply {
                addRoundRect(mapAreaRect, 28f, 28f, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(trackVisual, mapAreaRect.left, mapAreaRect.top, null)
            canvas.restore()
        }

        currentY += mapAreaHeight + 40f

        // 4. スタッツダッシュボード (2 x 2 グリッド)
        val gridMargin = 60f
        val colGap = 24f
        val rowGap = 24f
        val cellWidth = (CARD_WIDTH - (gridMargin * 2) - colGap) / 2f
        val cellHeight = 150f

        // スタッツ1: 遊泳距離
        drawStatCard(
            canvas = canvas,
            rect = RectF(gridMargin, currentY, gridMargin + cellWidth, currentY + cellHeight),
            icon = "🏊",
            label = "DISTANCE",
            value = log.getFormattedDistance(),
            valueColor = Color.parseColor("#00E5FF")
        )

        // スタッツ2: 遊泳時間
        drawStatCard(
            canvas = canvas,
            rect = RectF(gridMargin + cellWidth + colGap, currentY, CARD_WIDTH - gridMargin, currentY + cellHeight),
            icon = "⏱️",
            label = "TIME",
            value = log.getFormattedDuration(),
            valueColor = Color.parseColor("#FFD166")
        )

        currentY += cellHeight + rowGap

        // スタッツ3: ペース (/100m)
        drawStatCard(
            canvas = canvas,
            rect = RectF(gridMargin, currentY, gridMargin + cellWidth, currentY + cellHeight),
            icon = "⚡",
            label = "PACE (/100m)",
            value = log.getPacePer100m(),
            valueColor = Color.parseColor("#06D6A0")
        )

        // スタッツ4: 平均速度
        drawStatCard(
            canvas = canvas,
            rect = RectF(gridMargin + cellWidth + colGap, currentY, CARD_WIDTH - gridMargin, currentY + cellHeight),
            icon = "🚀",
            label = "AVG SPEED",
            value = log.getAverageSpeedKmh(),
            valueColor = Color.parseColor("#EF476F")
        )

        currentY += cellHeight + 35f

        // 5. フッター
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Tracked with Biwa Swim • びわ湖オープンウォータースイム", CARD_WIDTH / 2f, CARD_HEIGHT - 35f, footerPaint)

        return bitmap
    }

    private fun drawStatCard(
        canvas: Canvas,
        rect: RectF,
        icon: String,
        label: String,
        value: String,
        valueColor: Int
    ) {
        // カード背景
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 20f, 20f, cardBgPaint)

        // カードボーダー
        val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(rect, 20f, 20f, cardStrokePaint)

        // ラベル & アイコン
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("$icon $label", rect.left + 24f, rect.top + 45f, labelPaint)

        // バリュー
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(value, rect.left + 24f, rect.top + 115f, valuePaint)
    }

    /**
     * スナップショットがない場合やフォールバック時に、座標群から美しい軌跡ビジュアルを描画
     */
    fun renderTrackOnCanvas(points: List<Pair<Double, Double>>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景: 湖水風ダークブルー
        canvas.drawColor(Color.parseColor("#091E3A"))

        // 水面風グリッド線
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#152C4E")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val gridStep = 60f
        var gx = 0f
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint)
            gx += gridStep
        }
        var gy = 0f
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
            gy += gridStep
        }

        if (points.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No Track Data", width / 2f, height / 2f, emptyPaint)
            return bitmap
        }

        // バウンディングボックスの計算
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        points.forEach { (lat, lon) ->
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }

        val padding = 70f
        val drawW = width - padding * 2
        val drawH = height - padding * 2

        var rawLatSpan = max(maxLat - minLat, 0.0004)
        var rawLonSpan = max(maxLon - minLon, 0.0004)

        // 経度方向の縮尺補正（琵琶湖付近: cos(35.25°) ≈ 0.816）
        val latMeters = rawLatSpan * 111000.0
        val lonMeters = rawLonSpan * 111000.0 * 0.816

        val scale = min(drawW / lonMeters, drawH / latMeters)
        val fittedW = (lonMeters * scale).toFloat()
        val fittedH = (latMeters * scale).toFloat()

        val offsetX = padding + (drawW - fittedW) / 2f
        val offsetY = padding + (drawH - fittedH) / 2f

        val midLat = (minLat + maxLat) / 2.0
        val midLon = (minLon + maxLon) / 2.0

        fun toScreenX(lon: Double): Float {
            val dLonMeters = (lon - midLon) * 111000.0 * 0.816
            return (offsetX + fittedW / 2f + dLonMeters * scale).toFloat()
        }

        fun toScreenY(lat: Double): Float {
            val dLatMeters = (lat - midLat) * 111000.0
            // 緯度が高い（北）ほど画面の上
            return (offsetY + fittedH / 2f - dLatMeters * scale).toFloat()
        }

        // 軌跡グロー効果
        val glowTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600E5FF")
            strokeWidth = 16f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // メイン軌跡線
        val mainTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        val firstX = toScreenX(points.first().second)
        val firstY = toScreenY(points.first().first)
        path.moveTo(firstX, firstY)

        for (i in 1 until points.size) {
            val px = toScreenX(points[i].second)
            val py = toScreenY(points[i].first)
            path.lineTo(px, py)
        }

        canvas.drawPath(path, glowTrackPaint)
        canvas.drawPath(path, mainTrackPaint)

        // STARTマーカー (🟢)
        val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06D6A0")
            style = Paint.Style.FILL
        }
        val startStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(firstX, firstY, 16f, startPaint)
        canvas.drawCircle(firstX, firstY, 16f, startStroke)

        val pinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("START", firstX + 22f, firstY + 7f, pinTextPaint)

        // FINISHマーカー (🏁)
        if (points.size > 1) {
            val lastX = toScreenX(points.last().second)
            val lastY = toScreenY(points.last().first)
            val finishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#EF476F")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(lastX, lastY, 16f, finishPaint)
            canvas.drawCircle(lastX, lastY, 16f, startStroke)
            canvas.drawText("FINISH", lastX + 22f, lastY + 7f, pinTextPaint)
        }

        return bitmap
    }

    private fun fitBitmapToRect(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcWidth = src.width
        val srcHeight = src.height

        val scale = max(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
        val scaledW = (srcWidth * scale).toInt()
        val scaledH = (srcHeight * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val cropX = max(0, (scaledW - targetWidth) / 2)
        val cropY = max(0, (scaledH - targetHeight) / 2)

        return Bitmap.createBitmap(scaled, cropX, cropY, targetWidth, targetHeight)
    }

    /**
     * 生成したカード画像を一時キャッシュに保存します。
     */
    fun saveCardToCache(context: Context, bitmap: Bitmap, logId: String): File? {
        return try {
            val cacheDir = File(context.cacheDir, "swim_cards").apply { mkdirs() }
            val file = File(cacheDir, "swim_card_$logId.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save card to cache", e)
            null
        }
    }

    /**
     * 生成した画像を端末のギャラリー（写真ライブラリ）に保存します。
     */
    fun saveCardToGallery(context: Context, bitmap: Bitmap, logId: String): Boolean {
        return try {
            val fileName = "BiwaSwim_${logId}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BiwaSwim")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val biwaDir = File(picturesDir, "BiwaSwim").apply { mkdirs() }
                val file = File(biwaDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save card to gallery", e)
            false
        }
    }

    /**
     * SNSや他アプリに画像をエクスポート・共有するIntentを起動します。
     */
    fun shareCard(context: Context, imageFile: File, log: SwimLog) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val shareText = """
                🏊 びわ湖を泳ぎました！
                【遊泳距離】${log.getFormattedDistance()}
                【遊泳時間】${log.getFormattedDuration()}
                【平均ペース】${log.getPacePer100m()}
                
                #BiwaSwim #びわ湖 #オープンウォータースイム
            """.trimIndent()

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, context.getString(R.string.share_swim_record))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share card", e)
        }
    }
}
