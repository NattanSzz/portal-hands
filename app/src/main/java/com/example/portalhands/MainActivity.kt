package com.example.portalhands

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.ImageView
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
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/**
 * Versão Android do main.py da versão desktop.
 *
 * Pipeline: captura um frame da câmera, espelha, detecta as duas mãos em modo VÍDEO
 * (com tracking real entre frames — não trata cada frame como uma imagem isolada),
 * suaviza os pontos do portal com um filtro 1€ e "segura" a última posição válida por
 * alguns frames quando a detecção falha momentaneamente (evitando o portal sumir a
 * cada falha isolada), calcula o polígono do "portal" entre os dedos indicador e
 * polegar de cada mão, pinta o filtro atual dentro do polígono, desenha o glow-up
 * (anel de energia neon animado + brilho pulsante) e mostra o resultado em tela
 * cheia. Aproximar/fechar os dedos de cada mão avança para o próximo filtro. Toca
 * uma trilha sonora em loop enquanto o app está em primeiro plano.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var handLandmarker: HandLandmarker
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var filtroIndex = 0
    private val closingDetector = ClosingGestureDetector()
    private val portalTracker = PortalTracker()
    private val appStartMs = SystemClock.uptimeMillis()

    private var mediaPlayer: MediaPlayer? = null
    private var musicFadedIn = false

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
        imageView = findViewById(R.id.preview_image)

        setupHandLandmarker()
        setupBackgroundMusic()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupHandLandmarker() {
        // O modelo hand_landmarker.task precisa estar em app/src/main/assets/ (veja o README).
        // Tenta GPU primeiro (bem mais rápido em aparelhos compatíveis); se falhar, cai pra CPU.
        handLandmarker = try {
            createHandLandmarker(Delegate.GPU)
        } catch (e: Exception) {
            Log.w(TAG, "Delegate GPU indisponível neste aparelho, usando CPU", e)
            createHandLandmarker(Delegate.CPU)
        }
    }

    private fun createHandLandmarker(delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            // RunningMode.VIDEO é a mudança principal: o MediaPipe passa a reaproveitar o
            // rastreamento do frame anterior em vez de tratar cada frame como uma imagem
            // isolada. Resultado: muito menos jitter e detecção muito mais estável.
            .setRunningMode(RunningMode.VIDEO)
            .build()

        return HandLandmarker.createFromOptions(this, options)
    }

    /** Carrega res/raw/sunflower.mp3 e prepara pra tocar em loop. */
    private fun setupBackgroundMusic() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.sunflower)?.apply {
                isLooping = true
                setVolume(0f, 0f) // começa mudo; fadeInMusic() sobe o volume suavemente
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível carregar a trilha sonora", e)
        }
    }

    private fun fadeInMusic() {
        val player = mediaPlayer ?: return
        ValueAnimator.ofFloat(0f, 0.55f).apply {
            duration = 1500
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                player.setVolume(v, v)
            }
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                if (!musicFadedIn) {
                    musicFadedIn = true
                    fadeInMusic()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(imageProxy) }

            // Câmera frontal, igual à experiência de "espelho" da versão desktop
            // (cv2.flip(frame, 1)). Troque para CameraSelector.DEFAULT_BACK_CAMERA
            // se preferir a câmera traseira.
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
            val padded = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateAndMirror(padded, imageProxy.width, imageProxy.height, rotation, mirror = true)

            val w = bitmap.width
            val h = bitmap.height

            val mpImage = BitmapImageBuilder(bitmap).build()
            // Timestamp monotonicamente crescente exigido pelo modo VIDEO do MediaPipe.
            val timestampMs = SystemClock.uptimeMillis()
            val result = handLandmarker.detectForVideo(mpImage, timestampMs)

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

            val rawPoints: PortalPoints? = if (leftHand != null && rightHand != null) {
                PortalPoints(
                    PointF(leftHand[HandLandmarks.INDEX_TIP].x() * w, leftHand[HandLandmarks.INDEX_TIP].y() * h),
                    PointF(leftHand[HandLandmarks.THUMB_TIP].x() * w, leftHand[HandLandmarks.THUMB_TIP].y() * h),
                    PointF(rightHand[HandLandmarks.INDEX_TIP].x() * w, rightHand[HandLandmarks.INDEX_TIP].y() * h),
                    PointF(rightHand[HandLandmarks.THUMB_TIP].x() * w, rightHand[HandLandmarks.THUMB_TIP].y() * h)
                )
            } else null

            // Suaviza (filtro 1€) + segura a última posição válida por alguns frames
            // em vez de sumir a cada falha isolada de detecção.
            val points = portalTracker.update(rawPoints, timestampMs)

            if (points != null) {
                val width = portalWidth(points.p1, points.p2, points.p3, points.p4)
                if (closingDetector.update(width, w)) {
                    filtroIndex = (filtroIndex + 1) % FILTERS.size
                }
                val elapsedSeconds = (timestampMs - appStartMs) / 1000f
                renderPortal(bitmap, points.p1, points.p2, points.p3, points.p4, FILTERS[filtroIndex], elapsedSeconds)
            }

            runOnUiThread { imageView.setImageBitmap(bitmap) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /** Converte um ImageProxy no formato RGBA_8888 (configurado no ImageAnalysis) em Bitmap. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Recorta o padding de linha (se houver), rotaciona conforme o sensor e espelha
     * horizontalmente (equivalente a cv2.flip(frame, 1)) — tudo numa única
     * transformação, em vez de vários bitmaps intermediários como antes. Isso reduz
     * bastante a pressão de GC por frame e ajuda a evitar engasgos no preview.
     */
    private fun rotateAndMirror(
        padded: Bitmap,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        mirror: Boolean
    ): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) {
            matrix.postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(padded, 0, 0, cropWidth, cropHeight, matrix, true)
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
    }
}
