# Portal Hands — versão Android

Porta do projeto desktop (OpenCV + MediaPipe Python) para um app Android nativo.
Mesmo efeito: duas mãos formam um "portal" entre os dedos indicador e polegar,
um filtro é pintado dentro dele, e fechar os dedos de cada mão troca de filtro.
Sem interface: é só a câmera em tela cheia com o efeito.

## Por que não é o mesmo código Python?

O pacote `mediapipe` (Python) não roda em Android — não existe wheel para essa
plataforma. Por isso o app foi reescrito em Kotlin, usando:

- **CameraX** para capturar a câmera.
- **MediaPipe Tasks Vision (Hand Landmarker)** — a versão Android oficial do
  mesmo MediaPipe, na mesma versão (`0.10.14`) do `requirements.txt` original.
- Toda a lógica de `geometry.py` e `filters.py` foi traduzida linha a linha
  para Kotlin (`Geometry.kt` e `Filters.kt`), incluindo os 8 filtros e o
  detector de gesto de fechar as mãos.

## Passo obrigatório: baixar o modelo do MediaPipe

O Hand Landmarker precisa de um arquivo de modelo que não pode ir dentro do
código-fonte. Baixe e coloque em `app/src/main/assets/hand_landmarker.task`:

https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

(Pode apagar o arquivo `COLOQUE_O_MODELO_AQUI.txt` que está na mesma pasta,
ele é só um lembrete.)

## Não consegue usar o Android Studio? Compile pela nuvem (GitHub Actions)

Este projeto já vem com um workflow pronto em `.github/workflows/build.yml`
que compila o APK automaticamente no GitHub, sem precisar de Android Studio
nem de um PC potente. Basta subir esta pasta para um repositório no GitHub —
o build roda no servidor do GitHub e gera um APK para você baixar direto no
celular. Peça para o Claude te guiar passo a passo por esse caminho se preferir.

## Como abrir e rodar (caminho tradicional, com Android Studio)

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

## Sobre performance

Os filtros processam pixel a pixel em Kotlin (sem OpenCV/GPU), então em
celulares mais fracos pode não rodar tão fluido quanto no desktop. Se ficar
lento, é possível:
- Reduzir a resolução em `ImageAnalysis.Builder().setTargetResolution(...)`
  em `MainActivity.kt` (já está em 640x480).
- Reduzir o raio do blur em `filtroBlanco` (`Filters.kt`).

## Aviso importante

Este projeto foi escrito e revisado com cuidado, linha a linha espelhando a
versão desktop, mas eu não consegui compilar nem testar em um dispositivo
real aqui (o ambiente onde eu rodo não tem SDK do Android nem acesso aos
repositórios do Google/Maven necessários). Então é possível que apareça algum
erro pequeno de build na primeira vez que você abrir no Android Studio — me
mostre a mensagem de erro que eu ajudo a corrigir.
