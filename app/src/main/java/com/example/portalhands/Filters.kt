package com.example.portalhands

import kotlin.math.abs
import kotlin.math.sqrt

private fun clamp(v: Int): Int = v.coerceIn(0, 255)

private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

// Extração manual dos canais: evita a indireção de chamadas Color.red/green/blue
// dentro de loops que rodam dezenas de milhares de vezes por frame.
private inline fun red(c: Int) = (c shr 16) and 0xFF
private inline fun green(c: Int) = (c shr 8) and 0xFF
private inline fun blue(c: Int) = c and 0xFF

private fun gray(c: Int): Double = 0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)

private fun lerp(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt()

/**
 * Um filtro completo: a função que transforma os pixels + uma cor de destaque
 * usada no brilho animado da borda do portal, pra cada filtro ter uma
 * identidade visual própria.
 */
data class Filtro(
    val nome: String,
    val corGlow: Int,
    val aplicar: FiltroFunc
)

// ---------------------------------------------------------------------------
// Filtros originais, otimizados.
// ---------------------------------------------------------------------------

/** Posterização em 4 faixas, paleta neon. */
fun filtro1(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val gr = gray(pixels[i])
        pixels[i] = when {
            gr < 60 -> argb(18, 6, 40)
            gr < 130 -> argb(255, 20, 130)
            gr < 195 -> argb(0, 225, 255)
            else -> argb(255, 245, 210)
        }
    }
}

/** Trama de pontos (halftone). Compara distância ao quadrado — evita sqrt por pixel. */
fun filtro2(pixels: IntArray, w: Int, h: Int) {
    val cell = 6.0
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val gr = gray(pixels[i])
            val cx = (x % cell) - cell / 2
            val cy = (y % cell) - cell / 2
            val distSq = cx * cx + cy * cy
            val radius = (1 - gr / 255.0) * (cell / 1.4)
            pixels[i] = if (distSq < radius * radius) argb(15, 15, 15) else argb(245, 245, 245)
        }
    }
}

/** Deslocamento de canais de cor + scanlines. Wraparound por comparação, sem módulo. */
fun filtro3(pixels: IntArray, w: Int, h: Int) {
    val shift = 6
    val size = pixels.size
    val r = IntArray(size); val g = IntArray(size); val b = IntArray(size)
    for (i in 0 until size) {
        val c = pixels[i]
        r[i] = red(c); g[i] = green(c); b[i] = blue(c)
    }

    val out = IntArray(size)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            var rSrcX = x + shift
            while (rSrcX >= w) rSrcX -= w
            var bSrcX = x - shift
            while (bSrcX < 0) bSrcX += w
            val i = rowOffset + x
            out[i] = argb(r[rowOffset + rSrcX], g[i], b[rowOffset + bSrcX])
        }
    }

    for (y in 0 until h step 3) {
        val rowOffset = y * w
        for (x in 0 until w) {
            val i = rowOffset + x
            val c = out[i]
            out[i] = argb(
                (red(c) * 0.72).toInt(),
                (green(c) * 0.72).toInt(),
                (blue(c) * 0.72).toInt()
            )
        }
    }

    System.arraycopy(out, 0, pixels, 0, size)
}

private val jetLut: IntArray by lazy {
    IntArray(256) { i ->
        val t = i / 255.0
        val r = ((1.5 - abs(4 * t - 3)).coerceIn(0.0, 1.0) * 255).toInt()
        val g = ((1.5 - abs(4 * t - 2)).coerceIn(0.0, 1.0) * 255).toInt()
        val b = ((1.5 - abs(4 * t - 1)).coerceIn(0.0, 1.0) * 255).toInt()
        argb(r, g, b)
    }
}

/** Escala de cinza + colormap JET (térmico) via tabela pré-computada. */
fun filtro5(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val gr = gray(pixels[i]).toInt().coerceIn(0, 255)
        pixels[i] = jetLut[gr]
    }
}

// PRNG rápido (xorshift), sem sincronização — java.util.Random usa CAS internamente,
// caro quando chamado centenas de milhares de vezes por frame.
private var noiseSeed = 0x2545F4914F6CDD1DL
private fun fastNoise25(): Int {
    noiseSeed = noiseSeed xor (noiseSeed shl 13)
    noiseSeed = noiseSeed xor (noiseSeed ushr 7)
    noiseSeed = noiseSeed xor (noiseSeed shl 17)
    return ((noiseSeed and 0x7FFFFFFFL) % 25L).toInt()
}

/** Sépia + vinheta + ruído. */
fun filtro6(pixels: IntArray, w: Int, h: Int) {
    val cx = w / 2.0
    val cy = h / 2.0
    val maxDist = sqrt(cx * cx + cy * cy).let { if (it == 0.0) 1.0 else it }

    for (y in 0 until h) {
        val dy0 = y - cy
        val rowOffset = y * w
        for (x in 0 until w) {
            val i = rowOffset + x
            val c = pixels[i]
            val r = red(c).toDouble()
            val g = green(c).toDouble()
            val b = blue(c).toDouble()

            val outB = 0.272 * b + 0.534 * g + 0.131 * r
            val outG = 0.349 * b + 0.686 * g + 0.168 * r
            val outR = 0.393 * b + 0.769 * g + 0.189 * r

            val dx0 = x - cx
            val dist = sqrt(dx0 * dx0 + dy0 * dy0)
            val vignette = (1 - 0.5 * (dist / maxDist)).coerceIn(0.0, 1.0)

            val finalR = (outR * vignette + fastNoise25()).coerceIn(0.0, 255.0).toInt()
            val finalG = (outG * vignette + fastNoise25()).coerceIn(0.0, 255.0).toInt()
            val finalB = (outB * vignette + fastNoise25()).coerceIn(0.0, 255.0).toInt()

            pixels[i] = argb(finalR, finalG, finalB)
        }
    }
}

/**
 * Blur separável com JANELA DESLIZANTE: em vez de somar os vizinhos de novo a cada
 * pixel (O(raio) por pixel), mantém uma soma corrente e só troca o pixel que sai
 * pelo que entra na janela (O(1) por pixel). Isso é o que mais pesava no app —
 * um blur de raio 12 ficava ~25x mais lento sem essa técnica.
 */
private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    val temp = IntArray(pixels.size)
    val out = IntArray(pixels.size)
    val windowSize = 2 * radius + 1

    for (y in 0 until h) {
        val rowOffset = y * w
        var sr = 0; var sg = 0; var sb = 0
        for (dx in -radius..radius) {
            val c = pixels[rowOffset + dx.coerceIn(0, w - 1)]
            sr += red(c); sg += green(c); sb += blue(c)
        }
        temp[rowOffset] = argb(sr / windowSize, sg / windowSize, sb / windowSize)
        for (x in 1 until w) {
            val addC = pixels[rowOffset + (x + radius).coerceIn(0, w - 1)]
            val removeC = pixels[rowOffset + (x - radius - 1).coerceIn(0, w - 1)]
            sr += red(addC) - red(removeC)
            sg += green(addC) - green(removeC)
            sb += blue(addC) - blue(removeC)
            temp[rowOffset + x] = argb(sr / windowSize, sg / windowSize, sb / windowSize)
        }
    }

    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (dy in -radius..radius) {
            val c = temp[dy.coerceIn(0, h - 1) * w + x]
            sr += red(c); sg += green(c); sb += blue(c)
        }
        out[x] = argb(sr / windowSize, sg / windowSize, sb / windowSize)
        for (y in 1 until h) {
            val addC = temp[(y + radius).coerceIn(0, h - 1) * w + x]
            val removeC = temp[(y - radius - 1).coerceIn(0, h - 1) * w + x]
            sr += red(addC) - red(removeC)
            sg += green(addC) - green(removeC)
            sb += blue(addC) - blue(removeC)
            out[y * w + x] = argb(sr / windowSize, sg / windowSize, sb / windowSize)
        }
    }
    return out
}

/** Mesma técnica de janela deslizante, para um canal único (usado no filtro de bordas neon). */
private fun boxBlurGray(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
    val temp = FloatArray(values.size)
    val out = FloatArray(values.size)
    val windowSize = (2 * radius + 1).toFloat()

    for (y in 0 until h) {
        val rowOffset = y * w
        var sum = 0f
        for (dx in -radius..radius) sum += values[rowOffset + dx.coerceIn(0, w - 1)]
        temp[rowOffset] = sum / windowSize
        for (x in 1 until w) {
            sum += values[rowOffset + (x + radius).coerceIn(0, w - 1)] -
                values[rowOffset + (x - radius - 1).coerceIn(0, w - 1)]
            temp[rowOffset + x] = sum / windowSize
        }
    }

    for (x in 0 until w) {
        var sum = 0f
        for (dy in -radius..radius) sum += temp[dy.coerceIn(0, h - 1) * w + x]
        out[x] = sum / windowSize
        for (y in 1 until h) {
            sum += temp[(y + radius).coerceIn(0, h - 1) * w + x] -
                temp[(y - radius - 1).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = sum / windowSize
        }
    }
    return out
}

/** Blur + mistura com branco + bloom (brilho suave) nas áreas claras. */
fun filtroBlanco(pixels: IntArray, w: Int, h: Int) {
    val original = pixels.copyOf()
    val blurred = boxBlur(pixels, w, h, 12)

    val bright = IntArray(pixels.size)
    for (i in original.indices) {
        val c = original[i]
        bright[i] = if (gray(c) > 170) c else 0xFF000000.toInt()
    }
    val bloom = boxBlur(bright, w, h, 8)

    for (i in pixels.indices) {
        val c = blurred[i]
        val bl = bloom[i]
        val baseR = red(c) * 0.55 + 255 * 0.45
        val baseG = green(c) * 0.55 + 255 * 0.45
        val baseB = blue(c) * 0.55 + 255 * 0.45
        pixels[i] = argb(
            (baseR + red(bl) * 0.35).toInt(),
            (baseG + green(bl) * 0.35).toInt(),
            (baseB + blue(bl) * 0.35).toInt()
        )
    }
}

/** Trama de pontos em tons de rosa. Mesma otimização de distância ao quadrado. */
fun filtroRosa(pixels: IntArray, w: Int, h: Int) {
    val cell = 5.0
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val gr = gray(pixels[i])
            val cx = (x % cell) - cell / 2
            val cy = (y % cell) - cell / 2
            val distSq = cx * cx + cy * cy
            val radius = (1 - gr / 255.0) * (cell / 1.3)
            pixels[i] = if (distSq < radius * radius) argb(130, 20, 55) else argb(245, 190, 215)
        }
    }
}

/** Sobreposição de grade clara. */
fun filtroGrid(pixels: IntArray, w: Int, h: Int) {
    val step = 22
    val original = pixels.copyOf()
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (x % step == 0 || y % step == 0) {
                val i = y * w + x
                val orig = original[i]
                pixels[i] = argb(
                    (235 * 0.75 + red(orig) * 0.25).toInt(),
                    (235 * 0.75 + green(orig) * 0.25).toInt(),
                    (235 * 0.75 + blue(orig) * 0.25).toInt()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filtros novos.
// ---------------------------------------------------------------------------

private val auroraStops = listOf(
    0.00 to Triple(30, 10, 60),
    0.35 to Triple(200, 30, 150),
    0.60 to Triple(255, 110, 60),
    0.85 to Triple(255, 210, 90),
    1.00 to Triple(230, 255, 240)
)

private val auroraLut: IntArray by lazy {
    IntArray(256) { i ->
        val t = i / 255.0
        var lo = auroraStops.first()
        var hi = auroraStops.last()
        for (j in 0 until auroraStops.size - 1) {
            if (t >= auroraStops[j].first && t <= auroraStops[j + 1].first) {
                lo = auroraStops[j]; hi = auroraStops[j + 1]
                break
            }
        }
        val span = (hi.first - lo.first).let { if (it == 0.0) 1.0 else it }
        val localT = ((t - lo.first) / span).coerceIn(0.0, 1.0)
        argb(
            lerp(lo.second.first, hi.second.first, localT),
            lerp(lo.second.second, hi.second.second, localT),
            lerp(lo.second.third, hi.second.third, localT)
        )
    }
}

/** Gradiente holográfico suave via tabela pré-computada. */
fun filtroAurora(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val gr = gray(pixels[i]).toInt().coerceIn(0, 255)
        pixels[i] = auroraLut[gr]
    }
}

/** Contornos brilhantes estilo "Tron". Usa distância de Manhattan em vez de sqrt (mais rápido, mesmo efeito visual aqui). */
fun filtroNeonEdges(pixels: IntArray, w: Int, h: Int) {
    val size = pixels.size
    val grayVals = FloatArray(size)
    for (i in 0 until size) grayVals[i] = gray(pixels[i]).toFloat()

    val edge = FloatArray(size)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            val i = rowOffset + x
            val nx = if (x + 1 < w) x + 1 else x
            val ny = if (y + 1 < h) y + 1 else y
            val gx = grayVals[i] - grayVals[rowOffset + nx]
            val gy = grayVals[i] - grayVals[ny * w + x]
            edge[i] = abs(gx) + abs(gy)
        }
    }

    val glow = boxBlurGray(edge, w, h, 3)

    for (i in 0 until size) {
        val e = (edge[i] * 2.4f).coerceIn(0f, 255f)
        val g = (glow[i] * 3.2f).coerceIn(0f, 255f)
        pixels[i] = argb(
            (8 + g * 0.25f + e * 0.15f).toInt(),
            (12 + g * 0.55f + e * 0.55f).toInt(),
            (24 + g * 0.95f + e * 0.85f).toInt()
        )
    }
}

/** Lista de filtros disponíveis, na ordem em que o gesto de fechar as mãos alterna entre eles. */
val FILTERS: List<Filtro> = listOf(
    Filtro("Grade", argb(230, 230, 255), ::filtroGrid),
    Filtro("Posterize Neon", argb(255, 30, 150), ::filtro1),
    Filtro("Meio-tom", argb(120, 220, 255), ::filtro2),
    Filtro("Glitch RGB", argb(255, 90, 60), ::filtro3),
    Filtro("Térmico", argb(255, 140, 30), ::filtro5),
    Filtro("Sépia", argb(255, 200, 110), ::filtro6),
    Filtro("Bloom Sonhador", argb(160, 200, 255), ::filtroBlanco),
    Filtro("Rosa Doce", argb(255, 90, 170), ::filtroRosa),
    Filtro("Aurora", argb(190, 90, 255), ::filtroAurora),
    Filtro("Bordas Neon", argb(60, 255, 210), ::filtroNeonEdges)
)
