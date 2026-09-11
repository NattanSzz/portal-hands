# Portal Hands

Port de um projeto desktop (OpenCV + MediaPipe Python) para um app Android nativo.
efeito: duas mãos formam um "portal" entre os dedos indicador e polegar,
um filtro é pintado dentro dele, e fechar os dedos de cada mão troca de filtro.

## Como abrir e rodar (com Android Studio)

1. Abra a pasta `PortalHandsAndroid` no Android Studio (Open → selecione a pasta).
2. Deixe o Gradle sincronizar (primeira vez baixa as dependências do CameraX e
   do MediaPipe — precisa de internet).
3. Conecte um celular Android real por USB com a depuração USB ativada
   (a câmera não funciona bem em emulador). Android 7.0 (API 24) ou superior.
4. Clique em Run ▶.
5. Conceda a permissão de câmera quando o app pedir.

O app usa a câmera frontal por padrão (efeito espelho, como na versão desktop).
Para usar a traseira, troque `CameraSelector.DEFAULT_FRONT_CAMERA` por
`CameraSelector.DEFAULT_BACK_CAMERA` em `MainActivity.kt`.

## performance

Os filtros processam pixel a pixel em Kotlin (sem OpenCV/GPU), então em
celulares mais fracos pode não rodar tão fluido quanto no desktop. Se ficar
lento, é possível:
- Reduzir a resolução em `ImageAnalysis.Builder().setTargetResolution(...)`
  em `MainActivity.kt` (já está em 640x480).
- Reduzir o raio do blur em `filtroBlanco` (`Filters.kt`).
