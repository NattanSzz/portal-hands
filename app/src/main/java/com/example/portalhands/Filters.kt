package com.example.portalhands

import android.graphics.Color
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

private fun clamp(v: Int): Int = v.coerceIn(0, 255)

private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

private fun gray(r: Int, g: Int, b: Int): Double = 0.299 * r + 0.587 * g + 0.114 * b

private fun lerp(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt()

data class Filtro(
    val nome: String,
    val corGlow: Int,
    val aplicar: FiltroFunc
)

fun filtro1(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val gr = gray(Color.red(c), Color.green(c), Color.blue(c))
        pixels[i] = when {
            gr < 60 -> argb(18, 6, 40)   
            gr < 130 -> argb(255, 20, 130)  
            gr < 195 -> argb(0, 225, 255)  
            else -> argb(255, 245, 210)     
        }
    }
}


fun filtro2(pixels: IntArray, w: Int, h: Int) {
    val cell = 6.0
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val c = pixels[i]
            val gr = gray(Color.red(c), Color.green(c), Color.blue(c))
            val cx = (x % cell) - cell / 2
            val cy = (y % cell) - cell / 2
            val distCenter = sqrt(cx * cx + cy * cy)
            val radius = (1 - gr / 255.0) * (cell / 1.4)
            pixels[i] = if (distCenter < radius) argb(15, 15, 15) else argb(245, 245, 245)
        }
    }
}

fun filtro3(pixels: IntArray, w: Int, h: Int) {
    val shift = 6
    val size = pixels.size
    val r = IntArray(size); val g = IntArray(size); val b = IntArray(size)
    for (i in pixels.indices) {
        val c = pixels[i]
        r[i] = Color.red(c); g[i] = Color.green(c); b[i] = Color.blue(c)
    }

    val out = IntArray(size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val rSrcX = ((x + shift) % w + w) % w
            val bSrcX = ((x - shift) % w + w) % w
            out[i] = argb(r[y * w + rSrcX], g[i], b[y * w + bSrcX])
        }
    }

    for (y in 0 until h step 3) {
        for (x in 0 until w) {
            val i = y * w + x
            val c = out[i]
            out[i] = argb(
                (Color.red(c) * 0.72).toInt(),
                (Color.green(c) * 0.72).toInt(),
                (Color.blue(c) * 0.72).toInt()
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

fun filtro5(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val gr = gray(Color.red(c), Color.green(c), Color.blue(c)).toInt().coerceIn(0, 255)
        pixels[i] = jetLut[gr]
    }
}

fun filtro6(pixels: IntArray, w: Int, h: Int) {
    val cx = w / 2.0
    val cy = h / 2.0
    val maxDist = sqrt(cx * cx + cy * cy).let { if (it == 0.0) 1.0 else it }
    val rnd = Random()

    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val c = pixels[i]
            val r = Color.red(c).toDouble()
            val g = Color.green(c).toDouble()
            val b = Color.blue(c).toDouble()

            val outB = 0.272 * b + 0.534 * g + 0.131 * r
            val outG = 0.349 * b + 0.686 * g + 0.168 * r
            val outR = 0.393 * b + 0.769 * g + 0.189 * r

            val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val vignette = (1 - 0.5 * (dist / maxDist)).coerceIn(0.0, 1.0)

            val finalR = (outR * vignette + rnd.nextInt(25)).coerceIn(0.0, 255.0).toInt()
            val finalG = (outG * vignette + rnd.nextInt(25)).coerceIn(0.0, 255.0).toInt()
            val finalB = (outB * vignette + rnd.nextInt(25)).coerceIn(0.0, 255.0).toInt()

            pixels[i] = argb(finalR, finalG, finalB)
        }
    }
}

private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    val temp = IntArray(pixels.size)
    val out = IntArray(pixels.size)

    for (y in 0 until h) {
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (dx in -radius..radius) {
                val xx = x + dx
                if (xx in 0 until w) {
                    val c = pixels[y * w + xx]
                    sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
                    count++
                }
            }
            temp[y * w + x] = argb(sr / count, sg / count, sb / count)
        }
    }

    for (x in 0 until w) {
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy in 0 until h) {
                    val c = temp[yy * w + x]
                    sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
                    count++
                }
            }
            out[y * w + x] = argb(sr / count, sg / count, sb / count)
        }
    }
    return out
}

private fun boxBlurGray(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
    val temp = FloatArray(values.size)
    val out = FloatArray(values.size)

    for (y in 0 until h) {
        for (x in 0 until w) {
            var sum = 0f; var count = 0
            for (dx in -radius..radius) {
                val xx = x + dx
                if (xx in 0 until w) { sum += values[y * w + xx]; count++ }
            }
            temp[y * w + x] = sum / count
        }
    }
    for (x in 0 until w) {
        for (y in 0 until h) {
            var sum = 0f; var count = 0
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy in 0 until h) { sum += temp[yy * w + x]; count++ }
            }
            out[y * w + x] = sum / count
        }
    }
    return out
}

fun filtroBlanco(pixels: IntArray, w: Int, h: Int) {
    val original = pixels.copyOf()
    val blurred = boxBlur(pixels, w, h, 12)

    val bright = IntArray(pixels.size)
    for (i in original.indices) {
        val c = original[i]
        bright[i] = if (gray(Color.red(c), Color.green(c), Color.blue(c)) > 170) c else 0xFF000000.toInt()
    }
    val bloom = boxBlur(bright, w, h, 8)

    for (i in pixels.indices) {
        val c = blurred[i]
        val bl = bloom[i]
        val baseR = Color.red(c) * 0.55 + 255 * 0.45
        val baseG = Color.green(c) * 0.55 + 255 * 0.45
        val baseB = Color.blue(c) * 0.55 + 255 * 0.45
        pixels[i] = argb(
            (baseR + Color.red(bl) * 0.35).toInt(),
            (baseG + Color.green(bl) * 0.35).toInt(),
            (baseB + Color.blue(bl) * 0.35).toInt()
        )
    }
}

fun filtroRosa(pixels: IntArray, w: Int, h: Int) {
    val cell = 5.0
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val c = pixels[i]
            val gr = gray(Color.red(c), Color.green(c), Color.blue(c))
            val cx = (x % cell) - cell / 2
            val cy = (y % cell) - cell / 2
            val distCenter = sqrt(cx * cx + cy * cy)
            val radius = (1 - gr / 255.0) * (cell / 1.3)
            pixels[i] = if (distCenter < radius) argb(130, 20, 55) else argb(245, 190, 215)
        }
    }
}

fun filtroGrid(pixels: IntArray, w: Int, h: Int) {
    val step = 22
    val lr = 235; val lg = 235; val lb = 235
    val original = pixels.copyOf()
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (x % step == 0 || y % step == 0) {
                val i = y * w + x
                val orig = original[i]
                pixels[i] = argb(
                    (lr * 0.75 + Color.red(orig) * 0.25).toInt(),
                    (lg * 0.75 + Color.green(orig) * 0.25).toInt(),
                    (lb * 0.75 + Color.blue(orig) * 0.25).toInt()
                )
            }
        }
    }
}

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

fun filtroAurora(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val gr = gray(Color.red(c), Color.green(c), Color.blue(c)).toInt().coerceIn(0, 255)
        pixels[i] = auroraLut[gr]
    }
}

fun filtroNeonEdges(pixels: IntArray, w: Int, h: Int) {
    val size = pixels.size
    val grayVals = FloatArray(size)
    for (i in 0 until size) {
        val c = pixels[i]
        grayVals[i] = gray(Color.red(c), Color.green(c), Color.blue(c)).toFloat()
    }

    val edge = FloatArray(size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val nx = if (x + 1 < w) x + 1 else x
            val ny = if (y + 1 < h) y + 1 else y
            val gx = grayVals[i] - grayVals[y * w + nx]
            val gy = grayVals[i] - grayVals[ny * w + x]
            edge[i] = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
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
