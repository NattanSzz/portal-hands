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
import android.media.MediaPlayer

/**

Versão Android do main.py da versão desktop.


Pipeline:


captura frame da câmera;


corrige rotação e espelha a imagem;


detecta as duas mãos usando MediaPipe em modo VIDEO;


obtém os dedos indicador e polegar;


suaviza os quatro pontos usando PortalTracker;


mantém o último portal durante pequenas falhas de detecção;


calcula o gesto de fechamento usando os pontos suavizados;




renderiza o filtro dentro do portal.
*/
class MainActivity : AppCompatActivity() {

private lateinit var imageView: ImageView
private lateinit var handLandmarker: HandLandmarker

private var backgroundMusic: MediaPlayer? = null

private val cameraExecutor = Executors.newSingleThreadExecutor()

private var filtroIndex = 0

private val closingDetector = ClosingGestureDetector()

/**

Suaviza os quatro pontos do portal e mantém a última posição
válida durante pequenas falhas de detecção.
*/
private val portalTracker = PortalTracker(
maxMissedFrames = 8
)

/**

O modo VIDEO do MediaPipe exige timestamps crescentes.
*/
private var lastTimestampMs = 0L

private val requestPermissionLauncher =
registerForActivityResult(
ActivityResultContracts.RequestPermission()
) { granted ->

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
     requestPermissionLauncher.launch(
         Manifest.permission.CAMERA
     )
 }

 startBackgroundMusic()

}

private fun startBackgroundMusic() {
    if (backgroundMusic != null) {
        return
    }

    backgroundMusic = MediaPlayer.create(
        this,
        R.raw.background_music
    ).apply {
        isLooping = true
        setVolume(0.5f, 0.5f)
        start()
    }
}

/**

Configura o MediaPipe Hand Landmarker.


O modo VIDEO permite que o MediaPipe utilize o rastreamento

temporal entre frames, tornando a detecção mais estável e rápida.
*/
private fun setupHandLandmarker() {

val baseOptions = BaseOptions.builder()
.setModelAssetPath("hand_landmarker.task")
.build()

val options =
HandLandmarker.HandLandmarkerOptions.builder()
.setBaseOptions(baseOptions)
.setNumHands(2)
.setMinHandDetectionConfidence(0.6f)
.setMinTrackingConfidence(0.6f)
.setMinHandPresenceConfidence(0.6f)
.setRunningMode(RunningMode.VIDEO)
.build()

handLandmarker =
HandLandmarker.createFromOptions(
this,
options
)
}

/**

Inicializa a câmera frontal.
*/
private fun startCamera() {

val cameraProviderFuture =
ProcessCameraProvider.getInstance(this)

cameraProviderFuture.addListener({

 val cameraProvider =
     cameraProviderFuture.get()

 @Suppress("DEPRECATION")
 val analysis =
     ImageAnalysis.Builder()
         .setTargetResolution(
             Size(640, 480)
         )
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

}, ContextCompat.getMainExecutor(this))
}

/**

Processa um frame da câmera.
*/
private fun analyze(imageProxy: ImageProxy) {

try {

 val rawBitmap =
     imageProxyToBitmap(imageProxy)

 val rotation =
     imageProxy.imageInfo.rotationDegrees

 val bitmap =
     rotateAndMirror(
         rawBitmap,
         rotation,
         mirror = true
     )

 /*
  * Se a rotação/espelhamento criou outro Bitmap,
  * o bitmap original não é mais necessário.
  */
 if (bitmap !== rawBitmap) {
     rawBitmap.recycle()
 }

 val imageWidth = bitmap.width
 val imageHeight = bitmap.height

 val mpImage =
     BitmapImageBuilder(bitmap).build()

 /*
  * O MediaPipe VIDEO exige timestamp crescente.
  *
  * nanoTime é monotônico e, portanto, adequado para
  * construir um timestamp em milissegundos.
  */
 val currentTimestampMs =
     System.nanoTime() / 1_000_000L

 val timestampMs =
     if (currentTimestampMs <= lastTimestampMs) {
         lastTimestampMs + 1L
     } else {
         currentTimestampMs
     }

 lastTimestampMs = timestampMs

 /*
  * IMPORTANTE:
  *
  * Como o RunningMode é VIDEO, usamos detectForVideo()
  * em vez de detect().
  */
 val result =
     handLandmarker.detectForVideo(
         mpImage,
         timestampMs
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

 /*
  * Identifica as mãos detectadas.
  *
  * Como a imagem já foi espelhada para funcionar como
  * um espelho, invertimos Left/Right.
  */
 for (index in landmarksList.indices) {

     if (handednessList[index].isEmpty()) {
         continue
     }

     val rawLabel =
         handednessList[index][0].categoryName()

     val label =
         if (rawLabel == "Left") {
             "Right"
         } else {
             "Left"
         }

     if (label == "Left") {
         leftHand = landmarksList[index]
     } else {
         rightHand = landmarksList[index]
     }
 }

 /*
  * Cria os quatro pontos crus do portal.
  *
  * Caso uma das mãos não seja detectada, rawPortal será null.
  * O PortalTracker tratará essa falha.
  */
 val rawPortal: PortalPoints? =
     if (leftHand != null && rightHand != null) {

         val p1 =
             PointF(
                 leftHand[
                     HandLandmarks.INDEX_TIP
                 ].x() * imageWidth,
                 leftHand[
                     HandLandmarks.INDEX_TIP
                 ].y() * imageHeight
             )

         val p2 =
             PointF(
                 leftHand[
                     HandLandmarks.THUMB_TIP
                 ].x() * imageWidth,
                 leftHand[
                     HandLandmarks.THUMB_TIP
                 ].y() * imageHeight
             )

         val p3 =
             PointF(
                 rightHand[
                     HandLandmarks.INDEX_TIP
                 ].x() * imageWidth,
                 rightHand[
                     HandLandmarks.INDEX_TIP
                 ].y() * imageHeight
             )

         val p4 =
             PointF(
                 rightHand[
                     HandLandmarks.THUMB_TIP
                 ].x() * imageWidth,
                 rightHand[
                     HandLandmarks.THUMB_TIP
                 ].y() * imageHeight
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

 /*
  * Aplica:
  *
  * 1. One Euro Filter nos quatro pontos;
  * 2. persistência durante falhas momentâneas.
  */
 val portal =
     portalTracker.update(
         rawPortal,
         timestampMs
     )

 if (portal != null) {

     /*
      * Estes quatro pontos já estão suavizados.
      */
     val p1 = portal.p1
     val p2 = portal.p2
     val p3 = portal.p3
     val p4 = portal.p4

     /*
      * Calcula a largura usando os pontos suavizados.
      *
      * Isso também deixa o ClosingGestureDetector
      * muito menos sensível ao ruído.
      */
     val portalWidthValue =
         portalWidth(
             p1,
             p2,
             p3,
             p4
         )

     /*
      * O gesto de fechamento agora trabalha com a
      * distância suavizada.
      */
     if (
         closingDetector.update(
             portalWidthValue,
             imageWidth
         )
     ) {

         filtroIndex =
             (filtroIndex + 1) % FILTERS.size
     }

     /*
      * Desenha usando os pontos suavizados.
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

 /*
  * Atualiza o ImageView na UI thread.
  */
 runOnUiThread {
     imageView.setImageBitmap(bitmap)
 }

} catch (e: Exception) {

 Log.e(
     TAG,
     "Erro ao processar frame",
     e
 )

} finally {

 imageProxy.close()

}
}

/**

Converte um ImageProxy RGBA_8888 para Bitmap.



Evita uma cópia adicional quando não existe padding.
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

/*

Sem padding: podemos reutilizar diretamente o Bitmap.
*/
if (rowPadding == 0) {
return bitmap
}

/*

Com padding: recorta somente a região útil.
*/
val cropped =
Bitmap.createBitmap(
bitmap,
0,
0,
image.width,
image.height
)

bitmap.recycle()

return cropped
}

/**

Rotaciona a imagem de acordo com a orientação da câmera

e espelha horizontalmente.
*/
private fun rotateAndMirror(
bitmap: Bitmap,
rotationDegrees: Int,
mirror: Boolean
): Bitmap {

/*

Se não há transformação, reutiliza o Bitmap original.
*/
if (
rotationDegrees == 0 &&
!mirror
) {
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
    super.onDestroy()

    cameraExecutor.shutdown()

    if (::handLandmarker.isInitialized) {
        handLandmarker.close()
    }

    backgroundMusic?.release()
    backgroundMusic = null
}

companion object {

 private const val TAG =
     "PortalHands"

}
}
