package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal

/**
 * The text to show the owner for a failed session request. Only the adapter's and the Pacer's own exceptions carry
 * fixed, safe messages; anything else (a library, a file path) gets [fallback] so its text never reaches the screen.
 */
fun Exception.userMessage(fallback: String): String = when (this) {
    is InstagramException, is PacerRefusal -> message ?: fallback
    else -> fallback
}
