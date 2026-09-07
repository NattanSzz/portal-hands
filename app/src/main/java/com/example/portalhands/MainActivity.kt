package com.example.portalhands

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
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
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setRunningMode(RunningMode.IMAGE)
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
            val result = handLandmarker.detect(mpImage)

            val landmarksList = result.landmarks()
            val handednessList = result.handednesses()

            var leftHand: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null
            var rightHand: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null

            for (idx in landmarksList.indices) {
                val rawLabel = handednessList[idx][0].categoryName()
                // Mesma inversão Left/Right do main.py: a imagem já está espelhada,
                // então o rótulo bruto do MediaPipe precisa ser invertido.
                val label = if (rawLabel == "Left") "Right" else "Left"
                if (label == "Left") leftHand = landmarksList[idx] else rightHand = landmarksList[idx]
            }

            if (leftHand != null && rightHand != null) {
                val p1 = PointF(
                    leftHand[HandLandmarks.INDEX_TIP].x() * w,
                    leftHand[HandLandmarks.INDEX_TIP].y() * h
                )
                val p2 = PointF(
                    leftHand[HandLandmarks.THUMB_TIP].x() * w,
                    leftHand[HandLandmarks.THUMB_TIP].y() * h
                )
                val p3 = PointF(
                    rightHand[HandLandmarks.INDEX_TIP].x() * w,
                    rightHand[HandLandmarks.INDEX_TIP].y() * h
                )
                val p4 = PointF(
                    rightHand[HandLandmarks.THUMB_TIP].x() * w,
                    rightHand[HandLandmarks.THUMB_TIP].y() * h
                )

                val width = portalWidth(p1, p2, p3, p4)
                if (closingDetector.update(width, w)) {
                    filtroIndex = (filtroIndex + 1) % FILTERS.size
                }

                renderPortal(bitmap, p1, p2, p3, p4, FILTERS[filtroIndex])
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
    }

    companion object {
        private const val TAG = "PortalHands"
    }
}
