package io.github.yuriimurha.reels.data.media

import android.net.Uri
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType

/** A playable URI for an item, or null when it can't play right now (spec 8). */
fun interface VideoSourceResolver {
    suspend fun resolve(media: MediaEntity): Uri?
}

/** Every fake video plays the bundled synthetic clip. */
class FakeVideoSourceResolver(private val packageName: String) : VideoSourceResolver {
    override suspend fun resolve(media: MediaEntity): Uri? = when (media.type) {
        MediaType.REEL, MediaType.VIDEO -> Uri.parse("android.resource://$packageName/${R.raw.sample_clip}")
        MediaType.IMAGE, MediaType.CAROUSEL -> null
    }
}
