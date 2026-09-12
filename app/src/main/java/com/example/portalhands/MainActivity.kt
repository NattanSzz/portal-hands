package com.example.portalhands

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/**
 * Versão Android do main.py da versão desktop, otimizada para o máximo de FPS possível:
 *
 * - Desenha direto numa SurfaceView usando canvas de hardware (GPU), pela própria
 *   thread da câmera — sem passar pela UI thread nem pela alocação de Drawable que
 *   o antigo ImageView.setImageBitmap() fazia a cada frame.
 * - Bitmaps intermediários são reciclados assim que deixam de ser necessários,
 *   reduzindo picos de coleta de lixo (a maior causa provável dos engasgos).
 * - A rotação/espelhamento da câmera usa transformação sem interpolação, já que é
 *   sempre um ângulo reto (não precisa de suavização, só custa CPU à toa).
 * - Resolução de análise reduzida (o custo dos filtros cresce com o nº de pixels).
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    @Volatile private var surfaceReady = false
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private lateinit var handLandmarker: HandLandmarker
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var filtroIndex = 0
    private val closingDetector = ClosingGestureDetector()

    private var smoothedP1: PointF? = null
    private var smoothedP2: PointF? = null
    private var smoothedP3: PointF? = null
    private var smoothedP4: PointF? = null
    private var missFrames = 0

    private var mediaPlayer: MediaPlayer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "É necessário conceder acesso à câmera.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.preview_surface)
        surfaceView.holder.addCallback(this)

        setupHandLandmarker()
        setupMusic()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun setupMusic() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.sunflower)?.apply {
                isLooping = true
                setVolume(0.65f, 0.65f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível carregar a trilha sonora", e)
        }
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.let { if (!it.isPlaying) it.start() }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setRunningMode(RunningMode.VIDEO)
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                // Resolução mais baixa = bem menos pixels pros filtros processarem
                // (o custo deles cresce com o número de pixels da região do portal).
                // Se ainda estiver lento, tente 320x240. Se sobrar desempenho e
                // quiser mais nitidez, pode subir pra 640x480.
                .setTargetResolution(Size(480, 360))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(imageProxy) }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar a câmera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(imageProxy: ImageProxy) {
        try {
            val rawBitmap = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateAndMirror(rawBitmap, rotation, mirror = true)
            rawBitmap.recycle()

            val w = bitmap.width
            val h = bitmap.height

            val mpImage = BitmapImageBuilder(bitmap).build()
            val frameTimeMs = SystemClock.uptimeMillis()
            val result = handLandmarker.detectForVideo(mpImage, frameTimeMs)

            val landmarksList = result.landmarks()
            val handednessList = result.handednesses()

            var leftHand: List<NormalizedLandmark>? = null
            var rightHand: List<NormalizedLandmark>? = null

            for (idx in landmarksList.indices) {
                val rawLabel = handednessList[idx][0].categoryName()
                // Mesma inversão Left/Right do main.py: a imagem já está espelhada,
                // então o rótulo bruto do MediaPipe precisa ser invertido.
                val label = if (rawLabel == "Left") "Right" else "Left"
                if (label == "Left") leftHand = landmarksList[idx] else rightHand = landmarksList[idx]
            }

            if (leftHand != null && rightHand != null) {
                val rawP1 = PointF(
                    leftHand[HandLandmarks.INDEX_TIP].x() * w,
                    leftHand[HandLandmarks.INDEX_TIP].y() * h
                )
                val rawP2 = PointF(
                    leftHand[HandLandmarks.THUMB_TIP].x() * w,
                    leftHand[HandLandmarks.THUMB_TIP].y() * h
                )
                val rawP3 = PointF(
                    rightHand[HandLandmarks.INDEX_TIP].x() * w,
                    rightHand[HandLandmarks.INDEX_TIP].y() * h
                )
                val rawP4 = PointF(
                    rightHand[HandLandmarks.THUMB_TIP].x() * w,
                    rightHand[HandLandmarks.THUMB_TIP].y() * h
                )

                smoothedP1 = smooth(smoothedP1, rawP1)
                smoothedP2 = smooth(smoothedP2, rawP2)
                smoothedP3 = smooth(smoothedP3, rawP3)
                smoothedP4 = smooth(smoothedP4, rawP4)
                missFrames = 0
            } else {
                missFrames++
            }

            val p1 = smoothedP1
            val p2 = smoothedP2
            val p3 = smoothedP3
            val p4 = smoothedP4

            if (p1 != null && p2 != null && p3 != null && p4 != null) {
                if (missFrames <= MAX_MISS_FRAMES) {
                    val width = portalWidth(p1, p2, p3, p4)
                    if (closingDetector.update(width, w)) {
                        filtroIndex = (filtroIndex + 1) % FILTERS.size
                    }
                    val filtroAtual = FILTERS[filtroIndex]
                    renderPortal(bitmap, p1, p2, p3, p4, filtroAtual.aplicar, filtroAtual.corGlow, frameTimeMs)
                } else {
                    smoothedP1 = null
                    smoothedP2 = null
                    smoothedP3 = null
                    smoothedP4 = null
                }
            }

            drawToSurface(bitmap)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Desenha direto na SurfaceView a partir da própria thread da câmera — sem passar
     * pela UI thread. Usa canvas de hardware (GPU) quando disponível, que é bem mais
     * rápido pra essa operação de escalar+desenhar um bitmap grande a cada frame.
     */
    private fun drawToSurface(bitmap: Bitmap) {
        if (!surfaceReady) return
        val holder = surfaceView.holder

        val canvas: Canvas = (try {
            holder.lockHardwareCanvas()
        } catch (e: Exception) {
            null
        }) ?: (try {
            holder.lockCanvas()
        } catch (e: Exception) {
            null
        }) ?: return

        try {
            val dst = centerCropRect(bitmap.width, bitmap.height, canvas.width, canvas.height)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, dst, drawPaint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Calcula o retângulo de destino equivalente ao scaleType="centerCrop" do ImageView. */
    private fun centerCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Rect(0, 0, dstW, dstH)
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            val scaledW = (dstH * srcRatio).toInt()
            val left = (dstW - scaledW) / 2
            Rect(left, 0, left + scaledW, dstH)
        } else {
            val scaledH = (dstW / srcRatio).toInt()
            val top = (dstH - scaledH) / 2
            Rect(0, top, dstW, top + scaledH)
        }
    }

    /** Média móvel exponencial simples para suavizar um ponto entre frames. */
    private fun smooth(previous: PointF?, raw: PointF): PointF {
        if (previous == null) return raw
        return PointF(
            previous.x + (raw.x - previous.x) * SMOOTHING_ALPHA,
            previous.y + (raw.y - previous.y) * SMOOTHING_ALPHA
        )
    }

    /** Converte um ImageProxy no formato RGBA_8888 em Bitmap, sem cópias extras desnecessárias. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Bitmap.createBitmap(w, h, config) já é mutável por padrão — não precisa
        // de .copy() depois, isso só duplicava o trabalho em todo frame.
        val paddedBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        if (rowPadding == 0) return paddedBitmap

        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        paddedBitmap.recycle()
        return cropped
    }

    /** Rotaciona conforme a orientação do sensor e espelha horizontalmente. */
    private fun rotateAndMirror(bitmap: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) {
            matrix.postScale(-1f, 1f)
        }
        // filter=false: a rotação é sempre em múltiplos de 90° e o espelhamento é um
        // flip simples — ambas são transformações "alinhadas ao pixel", então a
        // interpolação bilinear (filter=true) não muda o resultado, só custa mais CPU.
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker.close()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "PortalHands"

        // Quanto menor, mais suave (e com mais "atraso"); quanto maior, mais fiel
        // ao movimento bruto (e com mais tremor). 0.5 é um meio-termo.
        private const val SMOOTHING_ALPHA = 0.5f

        // Quantos frames seguidos sem detectar as duas mãos ainda toleramos antes
        // de esconder o portal de verdade.
        private const val MAX_MISS_FRAMES = 6
    }
}
