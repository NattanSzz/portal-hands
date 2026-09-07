package com.example.portalhands

import android.Manifest
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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/**
 * Versão Android do main.py da versão desktop.
 *
 * O pipeline é o mesmo: captura um frame da câmera, espelha, detecta as duas mãos,
 * calcula o polígono do "portal" entre os dedos indicador e polegar de cada mão,
 * pinta o filtro atual dentro do polígono e mostra o resultado em tela cheia.
 * Aproximar/fechar os dedos de cada mão (gesto de "fechar o portal") avança para
 * o próximo filtro, exatamente como no ClosingGestureDetector do desktop.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var handLandmarker: HandLandmarker
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var filtroIndex = 0
    private val closingDetector = ClosingGestureDetector()

    // Estado usado para suavizar a posição do portal e tolerar falhas
    // pontuais de detecção sem o efeito "piscar" ou sumir por um instante.
    private var smoothedP1: PointF? = null
    private var smoothedP2: PointF? = null
    private var smoothedP3: PointF? = null
    private var smoothedP4: PointF? = null
    private var missFrames = 0

    // Trilha sonora em loop (arquivo em res/raw/sunflower.mp3).
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
        imageView = findViewById(R.id.preview_image)

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
        // O modelo hand_landmarker.task precisa estar em app/src/main/assets/ (veja o README).
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            // VIDEO ativa o rastreamento entre frames (como o static_image_mode=False
            // do MediaPipe Python usado no desktop), em vez de tratar cada frame como
            // uma foto isolada. É o que elimina o "piscar" e o sumiço da detecção.
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
            val rawBitmap = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateAndMirror(rawBitmap, rotation, mirror = true)

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

                // Suaviza a posição (média móvel exponencial) para tirar o
                // tremor natural da detecção, frame a frame.
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
                    // Mesmo que este frame específico não tenha detectado as mãos,
                    // segura a última posição conhecida por alguns frames em vez de
                    // sumir na hora — evita o efeito de "piscar" em falhas pontuais.
                    val width = portalWidth(p1, p2, p3, p4)
                    if (closingDetector.update(width, w)) {
                        filtroIndex = (filtroIndex + 1) % FILTERS.size
                    }
                    val filtroAtual = FILTERS[filtroIndex]
                    renderPortal(bitmap, p1, p2, p3, p4, filtroAtual.aplicar, filtroAtual.corGlow, frameTimeMs)
                } else {
                    // As mãos realmente sumiram por tempo suficiente: descarta o
                    // estado suavizado para não "puxar" de uma posição antiga
                    // quando elas voltarem a aparecer.
                    smoothedP1 = null
                    smoothedP2 = null
                    smoothedP3 = null
                    smoothedP4 = null
                }
            }

            runOnUiThread { imageView.setImageBitmap(bitmap) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame", e)
        } finally {
            imageProxy.close()
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

    /** Converte um ImageProxy no formato RGBA_8888 (configurado no ImageAnalysis) em Bitmap. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    /** Rotaciona conforme a orientação do sensor e espelha horizontalmente (equivalente a cv2.flip(frame, 1)). */
    private fun rotateAndMirror(bitmap: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (mirror) {
            matrix.postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
        // de esconder o portal de verdade. Em ~20-30 fps, 6 frames é só uma
        // fração de segundo — suficiente para engolir falhas pontuais do tracker.
        private const val MAX_MISS_FRAMES = 6
    }
}
