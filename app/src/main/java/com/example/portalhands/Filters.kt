package com.example.portalhands

import android.graphics.Color
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

private fun clamp(v: Int): Int = v.coerceIn(0, 255)

private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

private fun gray(r: Int, g: Int, b: Int): Double = 0.299 * r + 0.587 * g + 0.114 * b

/** Igual a filtro_1: posterização em 4 faixas de cinza. */
fun filtro1(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val gr = gray(Color.red(c), Color.green(c), Color.blue(c))
        pixels[i] = when {
            gr < 60 -> argb(10, 8, 15)
            gr < 130 -> argb(214, 30, 118)
            gr < 195 -> argb(235, 140, 35)
            else -> argb(240, 240, 235)
        }
    }
}

/** Igual a filtro_2: trama de pontos (halftone) preto e branco, célula de 6px. */
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

/** Igual a filtro_3: deslocamento de canais de cor + linhas de scanline. */
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

    // escurece cada 3ª linha (efeito de scanline)
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

/** Aproximação do colormap JET do OpenCV usada em filtro_5. */
private val jetLut: IntArray by lazy {
    IntArray(256) { i ->
        val t = i / 255.0
        val r = ((1.5 - abs(4 * t - 3)).coerceIn(0.0, 1.0) * 255).toInt()
        val g = ((1.5 - abs(4 * t - 2)).coerceIn(0.0, 1.0) * 255).toInt()
        val b = ((1.5 - abs(4 * t - 1)).coerceIn(0.0, 1.0) * 255).toInt()
        argb(r, g, b)
    }
}

/** Igual a filtro_5: escala de cinza + colormap JET (estilo térmico). */
fun filtro5(pixels: IntArray, w: Int, h: Int) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val gr = gray(Color.red(c), Color.green(c), Color.blue(c)).toInt().coerceIn(0, 255)
        pixels[i] = jetLut[gr]
    }
}

/** Igual a filtro_6: sépia + vinheta + ruído. */
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

            // mesma matriz de sépia de geometry.py (aplicada com B,G,R na ordem do OpenCV)
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

/** Blur separável simples (aproxima o efeito do GaussianBlur 35x35 do filtro_blanco). */
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

/** Igual a filtro_blanco: blur + mistura com branco. */
fun filtroBlanco(pixels: IntArray, w: Int, h: Int) {
    val blurred = boxBlur(pixels, w, h, 12)
    for (i in pixels.indices) {
        val c = blurred[i]
        pixels[i] = argb(
            (Color.red(c) * 0.55 + 255 * 0.45).toInt(),
            (Color.green(c) * 0.55 + 255 * 0.45).toInt(),
            (Color.blue(c) * 0.55 + 255 * 0.45).toInt()
        )
    }
}

/** Igual a filtro_rosa: trama de pontos em tons de rosa, célula de 5px. */
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

/** Igual a filtro_grid: sobreposição de grade clara. */
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

/** Igual a FILTROS de filters.py: mesma ordem, para o mesmo ciclo ao fechar o portal. */
val FILTERS: List<FiltroFunc> = listOf(
    ::filtroGrid,
    ::filtro1,
    ::filtro2,
    ::filtro3,
    ::filtro5,
    ::filtro6,
    ::filtroBlanco,
    ::filtroRosa
)
