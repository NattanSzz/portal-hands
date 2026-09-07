package com.example.portalhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

// ---------------------------------------------------------------------------------
// GLOW-UP: anel de energia neon (shader real do Canvas) + brilho pulsante interno.
// Paleta "portal moderno": ciano elétrico -> violeta -> magenta.
// Nada disso toca nos algoritmos dos filtros (que continuam fiéis à versão desktop,
// como documentado acima) — é uma camada decorativa desenhada por cima.
// ---------------------------------------------------------------------------------

private val PORTAL_GLOW_COLORS = intArrayOf(
    Color.parseColor("#00E5FF"), // ciano elétrico
    Color.parseColor("#7C4DFF"), // violeta
    Color.parseColor("#FF2FD0"), // magenta neon
    Color.parseColor("#00E5FF")  // fecha o ciclo suavemente
)

private fun polygonCenter(polygon: List<PointF>): PointF {
    var cx = 0f; var cy = 0f
    for (p in polygon) { cx += p.x; cy += p.y }
    return PointF(cx / polygon.size, cy / polygon.size)
}

private fun buildPolygonPath(polygon: List<PointF>): Path {
    val path = Path()
    polygon.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
    return path
}

/**
 * Anel de energia neon girando ao redor do portal: um SweepGradient (shader real do
 * Canvas do Android) com a paleta ciano/violeta/magenta, rotacionado a cada frame
 * conforme o tempo decorrido. Desenhado em duas camadas — um brilho externo
 * desfocado (BlurMaskFilter) e uma linha nítida por cima — pra dar aquele efeito de
 * "borda mágica" de jogo moderno em vez do contorno branco fino original.
 */
private fun drawPortalGlow(canvas: Canvas, polygon: List<PointF>, elapsedSeconds: Float) {
    val center = polygonCenter(polygon)
    val path = buildPolygonPath(polygon)

    val sweep = SweepGradient(center.x, center.y, PORTAL_GLOW_COLORS, null)
    val rotation = Matrix().apply { postRotate((elapsedSeconds * 55f) % 360f, center.x, center.y) }
    sweep.setLocalMatrix(rotation)

    // Camada 1: glow externo suave, largo e desfocado.
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = sweep
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        alpha = 190
    }
    canvas.drawPath(path, glowPaint)

    // Camada 2: linha nítida por cima, mesmo shader, sem blur.
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = sweep
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, corePaint)
}

/**
 * Brilho interno sutil e pulsante ("respiração" de energia), clipado dentro do
 * polígono do portal e desenhado por cima do filtro já aplicado. Nunca lava as
 * cores do filtro — é só um leve pulso branco translúcido que dá a sensação de
 * energia viva dentro do portal.
 */
private fun drawPortalShimmer(canvas: Canvas, polygon: List<PointF>, elapsedSeconds: Float) {
    val path = buildPolygonPath(polygon)
    val center = polygonCenter(polygon)

    var maxR = 1f
    for (p in polygon) {
        maxR = max(maxR, hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble()).toFloat())
    }

    val pulse = sin(elapsedSeconds * 2.2f) * 0.5f + 0.5f // oscila suavemente entre 0 e 1
    val alpha = (16 + pulse * 24).toInt() // brilho discreto, nunca exagerado

    val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            center.x, center.y, maxR,
            intArrayOf(Color.argb(alpha, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    canvas.save()
    canvas.clipPath(path)
    canvas.drawPaint(shimmerPaint)
    canvas.restore()
}

/**
 * Igual a render_portal() do geometry.py, com o glow-up: aplica o filtro dentro do
 * polígono e desenha por cima o brilho pulsante + o anel de energia neon animado.
 * elapsedSeconds vem do relógio do app (tempo desde que a Activity foi criada) e
 * controla a rotação do anel e a pulsação do brilho — são só osciladores baseados
 * em tempo, então continuam suaves independente do fps do momento.
 */
fun renderPortal(
    bitmap: Bitmap,
    p1: PointF,
    p2: PointF,
    p3: PointF,
    p4: PointF,
    filtro: FiltroFunc,
    elapsedSeconds: Float
) {
    val polygon = listOf(p1, p3, p4, p2)
    paintFilterInPolygon(bitmap, polygon, filtro)
    val canvas = Canvas(bitmap)
    drawPortalShimmer(canvas, polygon, elapsedSeconds)
    drawPortalGlow(canvas, polygon, elapsedSeconds)
}
