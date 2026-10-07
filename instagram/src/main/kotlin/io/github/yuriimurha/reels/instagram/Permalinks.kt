package io.github.yuriimurha.reels.instagram

object Permalinks {
    fun of(type: MediaType, code: String): String = when (type) {
        MediaType.REEL -> "https://www.instagram.com/reel/$code/"
        else -> "https://www.instagram.com/p/$code/"
    }
}
