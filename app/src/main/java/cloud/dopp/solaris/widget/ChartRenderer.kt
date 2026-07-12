package cloud.dopp.solaris.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max

/**
 * Canvas → Bitmap helpers for widgets. RemoteViews can't draw charts directly,
 * so trends are rendered to a Bitmap and set via `setImageViewBitmap`.
 */
object ChartRenderer {

    /** A filled sparkline of [points] (chronological). Empty bitmap if <2 points. */
    fun sparkline(
        widthPx: Int,
        heightPx: Int,
        points: List<Float>,
        lineColor: Int,
        fillColor: Int,
    ): Bitmap {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (points.size < 2) return bmp
        val c = Canvas(bmp)

        val minV = points.min()
        val maxV = points.max()
        val range = (maxV - minV).let { if (it == 0f) 1f else it }
        val pad = h * 0.12f

        fun px(i: Int) = i.toFloat() / (points.size - 1) * w
        fun py(v: Float) = h - pad - (v - minV) / range * (h - 2 * pad)

        val line = Path()
        val fill = Path()
        points.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h.toFloat()); fill.lineTo(x, y)
            } else {
                line.lineTo(x, y); fill.lineTo(x, y)
            }
        }
        fill.lineTo(w.toFloat(), h.toFloat())
        fill.close()

        c.drawPath(fill, Paint().apply {
            color = fillColor; style = Paint.Style.FILL; isAntiAlias = true
        })
        c.drawPath(line, Paint().apply {
            color = lineColor; style = Paint.Style.STROKE
            strokeWidth = max(2f, h * 0.03f); isAntiAlias = true
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        return bmp
    }
}
