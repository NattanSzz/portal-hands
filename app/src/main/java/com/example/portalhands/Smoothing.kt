package com.example.portalhands

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro 1€ (One Euro Filter) — o filtro padrão usado em interfaces de rastreamento de
 * mão/gesto para suavizar ruído sem introduzir atraso perceptível. Ele se adapta à
 * velocidade do movimento: quando o ponto está "parado" (tremendo), suaviza bastante;
 * quando o ponto se move rápido, reduz a suavização para não atrasar o desenho atrás do
 * movimento real da mão.
 *
 * minCutoff: suavização na base (menor = mais suave / mais "peso"; maior = mais
 *            responsivo, porém mais tremido em repouso)
 * beta:      quão rápido a suavização diminui conforme a velocidade aumenta
 *            (maior = menos atraso perceptível em movimentos rápidos)
 *
 * Ajuste esses dois valores se quiser: se ainda sentir tremedeira parado, DIMINUA
 * minCutoff; se sentir atraso ao mover a mão rápido, AUMENTE beta.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 10f,
    private val dCutoff: Float = 1f
) {
    private var xPrev: Float? = null
    private var dxPrev = 0f
    private var tPrevMs: Long? = null

    private fun smoothingFactor(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSeconds)
    }

    fun filter(x: Float, timestampMs: Long): Float {
        val prevT = tPrevMs
        if (prevT == null) {
            tPrevMs = timestampMs
            xPrev = x
            return x
        }

        var dt = (timestampMs - prevT) / 1000f
        if (dt <= 0f) dt = 1f / 30f // proteção contra timestamps iguais/retrocedendo
        tPrevMs = timestampMs

        val xPrevVal = xPrev ?: x
        val dx = (x - xPrevVal) / dt
        val aD = smoothingFactor(dCutoff, dt)
        val dxSmoothed = dxPrev + aD * (dx - dxPrev)
        dxPrev = dxSmoothed

        val cutoff = minCutoff + beta * abs(dxSmoothed)
        val a = smoothingFactor(cutoff, dt)
        val xFiltered = xPrevVal + a * (x - xPrevVal)
        xPrev = xFiltered
        return xFiltered
    }

    fun reset() {
        xPrev = null
        dxPrev = 0f
        tPrevMs = null
    }
}

/** Aplica o OneEuroFilter separadamente em x e y de um ponto 2D. */
class PointFilter(minCutoff: Float = 1.5f, beta: Float = 10f) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)

    fun filter(p: PointF, timestampMs: Long): PointF =
        PointF(fx.filter(p.x, timestampMs), fy.filter(p.y, timestampMs))

    fun reset() {
        fx.reset()
        fy.reset()
    }
}

/** Os quatro pontos que formam o polígono do portal (mesma ordem usada em geometry.kt). */
data class PortalPoints(val p1: PointF, val p2: PointF, val p3: PointF, val p4: PointF)

/**
 * Suaviza os 4 pontos do portal frame a frame e "segura" a última posição válida por
 * alguns frames quando a detecção falha momentaneamente (oclusão, motion blur, ângulo
 * ruim) — em vez de o portal simplesmente sumir da tela a cada falha isolada.
 *
 * maxMissedFrames: quantos frames seguidos sem detecção ainda mantêm o portal visível
 * antes de considerá-lo realmente fora de vista. Com câmera a ~25-30fps, 8 frames ≈
 * 270-320ms — suficiente pra cobrir uma falha passageira sem esconder o portal por
 * segundos, mas sem "prender" um portal fantasma se a mão realmente sair de quadro.
 * Se ainda sumir às vezes, aumente esse número; se sentir que ele "gruda" por muito
 * tempo depois de tirar a mão, diminua.
 */
class PortalTracker(private val maxMissedFrames: Int = 8) {
    private val f1 = PointFilter()
    private val f2 = PointFilter()
    private val f3 = PointFilter()
    private val f4 = PointFilter()

    private var lastPoints: PortalPoints? = null
    private var missedFrames = 0

    fun update(raw: PortalPoints?, timestampMs: Long): PortalPoints? {
        if (raw != null) {
            missedFrames = 0
            val smoothed = PortalPoints(
                f1.filter(raw.p1, timestampMs),
                f2.filter(raw.p2, timestampMs),
                f3.filter(raw.p3, timestampMs),
                f4.filter(raw.p4, timestampMs)
            )
            lastPoints = smoothed
            return smoothed
        }

        missedFrames++
        if (missedFrames > maxMissedFrames) {
            lastPoints = null
            f1.reset(); f2.reset(); f3.reset(); f4.reset()
        }
        return lastPoints
    }
}
