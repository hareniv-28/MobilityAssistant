package com.hareni.mobilityassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class DetectionResult(val rect: RectF, val label: String, val score: Float)

class DetectionOverlay @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        textSize = 36f
        isAntiAlias = true
    }

    private var results: List<DetectionResult> = emptyList()

    fun setResults(r: List<DetectionResult>) {
        results = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (res in results) {
            paint.color = 0xff00ff00.toInt()
            canvas.drawRect(res.rect, paint)
            canvas.drawText("${res.label} ${"%.2f".format(res.score)}", res.rect.left, res.rect.top - 8f, textPaint)
        }
    }
}
