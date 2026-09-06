package com.example.portalhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Igual a portal_width() do geometry.py: distância média entre os dedos das duas mãos. */
fun portalWidth(p1: PointF, p2: PointF, p3: PointF, p4: PointF): Float {
    val topW = hypot((p3.x - p1.x).toDouble(), (p3.y - p1.y).toDouble())
    val bottomW = hypot((p4.x - p2.x).toDouble(), (p4.y - p2.y).toDouble())
    return ((topW + bottomW) / 2.0).toFloat()
}

/** Igual a ClosingGestureDetector do geometry.py. */
class ClosingGestureDetector(
    private val closeRatio: Float = 0.16f,
    private val openRatio: Float = 0.30f
) {
    private var isClosed = false

    fun update(width: Float, frameW: Int): Boolean {
        val closeThreshold = closeRatio * frameW
        val openThreshold = openRatio * frameW

        var triggered = false
        if (!isClosed && width < closeThreshold) {
            isClosed = true
            triggered = true
        } else if (isClosed && width > openThreshold) {
            isClosed = false
        }
        return triggered
    }
}

/**
 * Um filtro recebe os pixels (ARGB, um Int por pixel) de uma região retangular e
 * os transforma "in place". Equivale às funções filtro_* de filters.py.
 */
typealias FiltroFunc = (pixels: IntArray, width: Int, height: Int) -> Unit

/**
 * Igual a paint_filter_in_polygon() do geometry.py: recorta o retângulo que envolve
 * o polígono, aplica o filtro só nos pixels dentro do polígono, e escreve o
 * resultado de volta no bitmap original.
 */
fun paintFilterInPolygon(bitmap: Bitmap, polygon: List<PointF>, filtro: FiltroFunc) {
    val w = bitmap.width
    val h = bitmap.height

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in polygon) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }

    val x = max(minX.toInt(), 0)
    val y = max(minY.toInt(), 0)
    val bw = min((maxX - minX).toInt(), w - x)
    val bh = min((maxY - minY).toInt(), h - y)
    if (bw <= 1 || bh <= 1) return

    // Máscara do polígono desenhada num bitmap do tamanho do recorte
    // (o polígono é deslocado para a origem do recorte).
    val maskBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val path = Path()
    polygon.forEachIndexed { i, p ->
        val px = p.x - x
        val py = p.y - y
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    Canvas(maskBitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

    val maskPixels = IntArray(bw * bh)
    maskBitmap.getPixels(maskPixels, 0, bw, 0, 0, bw, bh)

    val roiPixels = IntArray(bw * bh)
    bitmap.getPixels(roiPixels, 0, bw, x, y, bw, bh)

    val filteredPixels = roiPixels.copyOf()
    filtro(filteredPixels, bw, bh)

    for (i in roiPixels.indices) {
        if (Color.alpha(maskPixels[i]) > 0) {
            roiPixels[i] = filteredPixels[i]
        }
    }

    bitmap.setPixels(roiPixels, 0, bw, x, y, bw, bh)
    maskBitmap.recycle()
}

/** Igual a render_portal() do geometry.py. */
fun renderPortal(bitmap: Bitmap, p1: PointF, p2: PointF, p3: PointF, p4: PointF, filtro: FiltroFunc) {
    val polygon = listOf(p1, p3, p4, p2)
    paintFilterInPolygon(bitmap, polygon, filtro)

    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    val path = Path()
    polygon.forEachIndexed { i, p ->
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    canvas.drawPath(path, paint)
}
