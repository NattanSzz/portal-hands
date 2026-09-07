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
* Pipeline:
* 1. Captura frame da câmera.
* 2. Converte para Bitmap.
* 3. Corrige rotação e espelha a imagem.
* 4. Detecta as duas mãos usando MediaPipe em modo VIDEO.
* 5. Extrai os dedos indicador/polegar.
* 6. Suaviza os quatro pontos com PortalTracker.
* 7. Mantém o último portal por alguns frames quando a detecção falha.
* 8. Calcula o gesto de fechamento usando os pontos suavizados.
* 9. Renderiza o filtro dentro do portal.
     */
     class MainActivity : AppCompatActivity() {

  private lateinit var imageView: ImageView
  private lateinit var handLandmarker: HandLandmarker

  private val cameraExecutor = Executors.newSingleThreadExecutor()

  private var filtroIndex = 0

  private val closingDetector = ClosingGestureDetector()

  /**

  * Suaviza os pontos do portal e mantém a última posição válida
  * durante pequenas falhas de detecção.
    */
    private val portalTracker = PortalTracker(maxMissedFrames = 8)

  /**

  * Timestamp usado pelo MediaPipe no modo VIDEO.
  *
  * Precisa ser monotonicamente crescente.
    */
    private var lastTimestampMs = 0L

  private val requestPermissionLauncher =
  registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if (granted) {
  startCamera()
  } else {
  Toast.makeText(
  this,
  "É necessário conceder acesso à câmera.",
  Toast.LENGTH_LONG
  ).show()
  }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)

  ```
   setContentView(R.layout.activity_main)

   imageView = findViewById(R.id.preview_image)

   setupHandLandmarker()

   if (
       ContextCompat.checkSelfPermission(
           this,
           Manifest.permission.CAMERA
       ) == PackageManager.PERMISSION_GRANTED
   ) {
       startCamera()
   } else {
       requestPermissionLauncher.launch(Manifest.permission.CAMERA)
   }
  ```

  }

  /**

  * Configura o MediaPipe Hand Landmarker.
  *
  * A principal mudança em relação à versão anterior é:
  *
  * RunningMode.IMAGE
  * ```
       ↓
    ```
  * RunningMode.VIDEO
  *
  * Isso permite que o MediaPipe utilize o rastreamento temporal
  * entre os frames, reduzindo custo e instabilidade.
    */
    private fun setupHandLandmarker() {

    val baseOptions = BaseOptions.builder()
    .setModelAssetPath("hand_landmarker.task")
    .build()

    val options = HandLandmarker.HandLandmarkerOptions.builder()
    .setBaseOptions(baseOptions)
    .setNumHands(2)
    .setMinHandDetectionConfidence(0.6f)
    .setMinTrackingConfidence(0.6f)
    .setMinHandPresenceConfidence(0.6f)
    .setRunningMode(RunningMode.VIDEO)
    .build()

    handLandmarker = HandLandmarker.createFromOptions(
    this,
    options
    )
    }

  /**

  * Inicializa a câmera.
    */
    private fun startCamera() {

    val cameraProviderFuture =
    ProcessCameraProvider.getInstance(this)

    cameraProviderFuture.addListener({

    ```
     val cameraProvider = cameraProviderFuture.get()

     @Suppress("DEPRECATION")
     val analysis = ImageAnalysis.Builder()
         .setTargetResolution(Size(640, 480))
         .setOutputImageFormat(
             ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
         )
         .setBackpressureStrategy(
             ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
         )
         .build()

     analysis.setAnalyzer(cameraExecutor) { imageProxy ->
         analyze(imageProxy)
     }

     /**
      * Câmera frontal para comportamento de espelho.
      */
     val cameraSelector =
         CameraSelector.DEFAULT_FRONT_CAMERA

     try {

         cameraProvider.unbindAll()

         cameraProvider.bindToLifecycle(
             this,
             cameraSelector,
             analysis
         )

     } catch (e: Exception) {

         Log.e(
             TAG,
             "Falha ao iniciar a câmera",
             e
         )
     }
    ```

    }, ContextCompat.getMainExecutor(this))
    }

  /**

  * Processa um frame da câmera.
    */
    private fun analyze(imageProxy: ImageProxy) {

    try {

    ```
     val rawBitmap = imageProxyToBitmap(imageProxy)

     val rotation =
         imageProxy.imageInfo.rotationDegrees

     /**
      * Corrige orientação e espelha a câmera frontal.
      */
     val bitmap = rotateAndMirror(
         rawBitmap,
         rotation,
         mirror = true
     )

     /**
      * O Bitmap original não é mais necessário depois
      * da transformação.
      */
     if (bitmap !== rawBitmap) {
         rawBitmap.recycle()
     }

     val width = bitmap.width
     val height = bitmap.height

     val mpImage =
         BitmapImageBuilder(bitmap).build()

     /**
      * Garante timestamp crescente.
      *
      * O ImageProxy não fornece necessariamente um timestamp
      * adequado para o requisito do modo VIDEO, portanto usamos
      * o timestamp do sistema.
      */
     val timestampMs = System.nanoTime() / 1_000_000L

     val safeTimestamp =
         if (timestampMs <= lastTimestampMs) {
             lastTimestampMs + 1L
         } else {
             timestampMs
         }

     lastTimestampMs = safeTimestamp

     /**
      * IMPORTANTE:
      *
      * Em RunningMode.VIDEO não usamos detect().
      *
      * Usamos detectForVideo() para permitir que o MediaPipe
      * aproveite o rastreamento entre frames.
      */
     val result =
         handLandmarker.detectForVideo(
             mpImage,
             safeTimestamp
         )

     val landmarksList =
         result.landmarks()

     val handednessList =
         result.handednesses()

     var leftHand:
         List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? =
         null

     var rightHand:
         List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? =
         null

     /**
      * Identifica as duas mãos.
      *
      * Como a imagem já foi espelhada, invertemos Left/Right
      * fornecido pelo MediaPipe para manter a mesma lógica
      * utilizada anteriormente.
      */
     for (idx in landmarksList.indices) {

         if (handednessList[idx].isEmpty()) {
             continue
         }

         val rawLabel =
             handednessList[idx][0].categoryName()

         val label =
             if (rawLabel == "Left") {
                 "Right"
             } else {
                 "Left"
             }

         if (label == "Left") {
             leftHand = landmarksList[idx]
         } else {
             rightHand = landmarksList[idx]
         }
     }

     /**
      * Obtém os pontos crus.
      *
      * Se uma das mãos não foi encontrada, rawPortal será null.
      *
      * O PortalTracker cuidará da persistência e poderá manter
      * o último portal durante alguns frames.
      */
     val rawPortal =
         if (leftHand != null && rightHand != null) {

             val p1 = PointF(
                 leftHand[
                     HandLandmarks.INDEX_TIP
                 ].x() * width,
                 leftHand[
                     HandLandmarks.INDEX_TIP
                 ].y() * height
             )

             val p2 = PointF(
                 leftHand[
                     HandLandmarks.THUMB_TIP
                 ].x() * width,
                 leftHand[
                     HandLandmarks.THUMB_TIP
                 ].y() * height
             )

             val p3 = PointF(
                 rightHand[
                     HandLandmarks.INDEX_TIP
                 ].x() * width,
                 rightHand[
                     HandLandmarks.INDEX_TIP
                 ].y() * height
             )

             val p4 = PointF(
                 rightHand[
                     HandLandmarks.THUMB_TIP
                 ].x() * width,
                 rightHand[
                     HandLandmarks.THUMB_TIP
                 ].y() * height
             )

             PortalPoints(
                 p1 = p1,
                 p2 = p2,
                 p3 = p3,
                 p4 = p4
             )

         } else {
             null
         }

     /**
      * Suaviza os pontos e mantém o portal durante pequenas
      * falhas de detecção.
      */
     val portal =
         portalTracker.update(
             rawPortal,
             safeTimestamp
         )

     if (portal != null) {

         /**
          * Os pontos abaixo já são suavizados.
          */
         val p1 = portal.p1
         val p2 = portal.p2
         val p3 = portal.p3
         val p4 = portal.p4

         /**
          * Calcula a largura do portal usando pontos suavizados.
          *
          * Isso também estabiliza o ClosingGestureDetector.
          */
         val widthValue =
             portalWidth(
                 p1,
                 p2,
                 p3,
                 p4
             )

         /**
          * O gesto agora recebe uma distância muito menos
          * ruidosa, evitando oscilações próximas ao threshold.
          */
         if (
             closingDetector.update(
                 widthValue,
                 width
             )
         ) {

             filtroIndex =
                 (filtroIndex + 1) % FILTERS.size
         }

         /**
          * Renderiza o filtro usando os pontos suavizados.
          */
         renderPortal(
             bitmap,
             p1,
             p2,
             p3,
             p4,
             FILTERS[filtroIndex]
         )
     }

     /**
      * Atualiza a UI somente na thread principal.
      */
     runOnUiThread {

         imageView.setImageBitmap(bitmap)
     }
    ```

    } catch (e: Exception) {

    ```
     Log.e(
         TAG,
         "Erro ao processar frame",
         e
     )
    ```

    } finally {

    ```
     imageProxy.close()
    ```

    }
    }

  /**

  * Converte ImageProxy RGBA_8888 para Bitmap.
  *
  * Evita o .copy() adicional quando o buffer já possui exatamente
  * o tamanho necessário.
    */
    private fun imageProxyToBitmap(
    image: ImageProxy
    ): Bitmap {

    val plane = image.planes[0]

    val buffer = plane.buffer

    buffer.rewind()

    val pixelStride =
    plane.pixelStride

    val rowStride =
    plane.rowStride

    val rowPadding =
    rowStride -
    pixelStride * image.width

    /**

    * Normalmente, com RGBA_8888, o pixelStride é 4.
      */
      val bitmapWidth =
      image.width +
      rowPadding / pixelStride

    val bitmap =
    Bitmap.createBitmap(
    bitmapWidth,
    image.height,
    Bitmap.Config.ARGB_8888
    )

    bitmap.copyPixelsFromBuffer(buffer)

    /**

    * Se não existe padding, podemos retornar diretamente.
    *
    * Caso exista padding, precisamos recortar a área útil.
      */
      return if (rowPadding == 0) {

      bitmap

    } else {

    ```
     val cropped =
         Bitmap.createBitmap(
             bitmap,
             0,
             0,
             image.width,
             image.height
         )

     bitmap.recycle()

     cropped
    ```

    }
    }

  /**

  * Rotaciona a imagem conforme a orientação da câmera
  * e espelha horizontalmente.
    */
    private fun rotateAndMirror(
    bitmap: Bitmap,
    rotationDegrees: Int,
    mirror: Boolean
    ): Bitmap {

    /**

    * Não precisamos criar uma matriz quando não há transformação.
      */
      if (rotationDegrees == 0 && !mirror) {
      return bitmap
      }

    val matrix = Matrix()

    if (rotationDegrees != 0) {
    matrix.postRotate(
    rotationDegrees.toFloat()
    )
    }

    if (mirror) {
    matrix.postScale(
    -1f,
    1f
    )
    }

    return Bitmap.createBitmap(
    bitmap,
    0,
    0,
    bitmap.width,
    bitmap.height,
    matrix,
    true
    )
    }

  override fun onDestroy() {

  ```
   super.onDestroy()

   cameraExecutor.shutdown()

   if (::handLandmarker.isInitialized) {
       handLandmarker.close()
   }
  ```

  }

  companion object {

  ```
   private const val TAG =
       "PortalHands"
  ```

  }
  }
