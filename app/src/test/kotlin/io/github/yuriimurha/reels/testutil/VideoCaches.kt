package io.github.yuriimurha.reels.testutil

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import io.github.yuriimurha.reels.data.media.VideoCache
import java.io.File

/**
 * Caches the whole of a local file under [pk], the way a player reading it through a [CacheDataSource] would: the content
 * length is recorded because the span is requested without a length. The only way tests put bytes into a [VideoCache].
 */
@UnstableApi
fun VideoCache.cacheWholeFile(pk: String, file: File) {
    val source = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(FileDataSource.Factory())
        .createDataSource()
    CacheWriter(source, DataSpec.Builder().setUri(Uri.fromFile(file)).setKey(pk).build(), ByteArray(4096), null).cache()
}
