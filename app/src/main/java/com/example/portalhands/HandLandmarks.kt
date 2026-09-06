package com.example.portalhands

/**
 * Índices dos pontos de referência da mão, no mesmo esquema do MediaPipe Hands
 * usado na versão desktop (hand_tracking.py). Só INDEX_TIP e THUMB_TIP são
 * usados pelo pipeline principal, mas os demais ficam aqui para referência.
 */
object HandLandmarks {
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val THUMB_MCP = 2
    const val INDEX_TIP = 8
    const val INDEX_MCP = 5
    const val MIDDLE_TIP = 12
    const val MIDDLE_MCP = 9
    const val RING_TIP = 16
    const val RING_MCP = 13
    const val PINKY_TIP = 20
    const val PINKY_MCP = 17
}
