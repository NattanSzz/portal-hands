package com.example.portalhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Distância média entre os dedos das duas mãos — define a "largura" do portal. */
fun portalWidth(p1: PointF, p2: PointF, p3: PointF, p4: PointF): Float {
    val topW = hypot((p3.x - p1.x).toDouble(), (p3.y - p1.y).toDouble())
    val bottomW = hypot((p4.x - p2.x).toDouble(), (p4.y - p2.y).toDouble())
    return ((topW + bottomW) / 2.0).toFloat()
}

/** Detecta o gesto de fechar/abrir os dedos pra alternar de filtro. */
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
 * os transforma "in place".
 */
typealias FiltroFunc = (pixels: IntArray, width: Int, height: Int) -> Unit

/**
 * Buffers reaproveitados entre frames (só a thread única da câmera acessa isso,
 * então não há necessidade de sincronização). Evita alocar arrays novos a cada
 * frame — uma das maiores fontes de lixo de memória do app.
 */
private object PixelBuffers {
    var original = IntArray(0)
    var filtered = IntArray(0)
    var crossings = FloatArray(8)

    fun ensureCapacity(size: Int) {
        if (original.size < size) original = IntArray(size)
        if (filtered.size < size) filtered = IntArray(size)
    }
}

/**
 * Pinta o filtro dentro do polígono usando PREENCHIMENTO POR VARREDURA (scanline):
 * em vez de testar cada pixel individualmente contra o polígono (caro, com uma
 * divisão de ponto flutuante por pixel), calcula uma vez por LINHA onde a borda
 * do polígono cruza aquela linha, e copia o trecho "de dentro" de uma vez com
 * System.arraycopy — muito mais rápido que testar pixel a pixel.
 */
fun paintFilterInPolygon(bitmap: Bitmap, polygon: List<PointF>, filtro: FiltroFunc) {
    val w = bitmap.width
    val h = bitmap.height

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (p in polygon) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }

    val x = max(minX.toInt(), 0)
    val y = max(minY.toInt(), 0)
    val bw = min((maxX - minX).toInt(), w - x)
    val bh = min((maxY - minY).toInt(), h - y)
    if (bw <= 1 || bh <= 1) return

    val needed = bw * bh
    PixelBuffers.ensureCapacity(needed)
    val original = PixelBuffers.original
    val filtered = PixelBuffers.filtered

    bitmap.getPixels(original, 0, bw, x, y, bw, bh)
    System.arraycopy(original, 0, filtered, 0, needed)
    filtro(filtered, bw, bh)

    val n = polygon.size
    val vx = FloatArray(n) { polygon[it].x }
    val vy = FloatArray(n) { polygon[it].y }

    for (py in 0 until bh) {
        val worldY = (y + py).toFloat()
        val rowOffset = py * bw

        // Onde a borda do polígono cruza esta linha horizontal.
        var count = 0
        var j = n - 1
        for (i in 0 until n) {
            val yi = vy[i]; val yj = vy[j]
            if ((yi > worldY) != (yj > worldY)) {
                val xCross = (vx[j] - vx[i]) * (worldY - yi) / (yj - yi) + vx[i]
                if (count >= PixelBuffers.crossings.size) {
                    PixelBuffers.crossings = PixelBuffers.crossings.copyOf(count * 2)
                }
                PixelBuffers.crossings[count] = xCross
                count++
            }
            j = i
        }
        if (count < 2) continue

        val crossings = PixelBuffers.crossings
        // Poucos elementos (tipicamente 2) — insertion sort é mais que suficiente.
        for (a in 1 until count) {
            val v = crossings[a]
            var b = a - 1
            while (b >= 0 && crossings[b] > v) {
                crossings[b + 1] = crossings[b]
                b--
            }
            crossings[b + 1] = v
        }

        var k = 0
        while (k + 1 < count) {
            val startPx = (crossings[k] - x).toInt().coerceIn(0, bw)
            val endPx = (crossings[k + 1] - x).toInt().coerceIn(0, bw)
            if (endPx > startPx) {
                System.arraycopy(filtered, rowOffset + startPx, original, rowOffset + startPx, endPx - startPx)
            }
            k += 2
        }
    }

    bitmap.setPixels(original, 0, bw, x, y, bw, bh)
}

/**
 * Desenha o portal: pinta o filtro dentro do polígono e depois desenha uma
 * borda "viva" — brilho colorido (cor de destaque do filtro atual), uma leve
 * pulsação, e traços em movimento contornando o polígono. Tudo aqui é desenho
 * vetorial do Canvas (rápido); o custo pesado fica só na parte de pixels acima.
 */
fun renderPortal(
    bitmap: Bitmap,
    p1: PointF, p2: PointF, p3: PointF, p4: PointF,
    filtro: FiltroFunc,
    corGlow: Int,
    tempoMs: Long
) {
    val polygon = listOf(p1, p3, p4, p2)
    paintFilterInPolygon(bitmap, polygon, filtro)

    val canvas = Canvas(bitmap)
    val path = Path()
    polygon.forEachIndexed { i, p ->
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()

    val pulse = (sin(tempoMs / 450.0) * 0.5 + 0.5).toFloat()

    val glowOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = corGlow
        style = Paint.Style.STROKE
        strokeWidth = 12f + pulse * 6f
        alpha = (110 + pulse * 70).toInt().coerceIn(0, 255)
        maskFilter = BlurMaskFilter(20f + pulse * 6f, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawPath(path, glowOuter)

    val glowInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = corGlow
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 225
        maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawPath(path, glowInner)

    val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        alpha = 230
        pathEffect = DashPathEffect(floatArrayOf(22f, 16f), (tempoMs / 6f) % 38f)
    }
    canvas.drawPath(path, dash)

    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    canvas.drawPath(path, core)
}
