package io.github.yuriimurha.reels.testutil

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.instagram.MediaType

fun inMemoryDb(): ReelsDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReelsDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun mediaEntity(
    pk: String,
    type: MediaType = MediaType.REEL,
    author: String = "author_$pk",
    caption: String? = "caption $pk",
    thumbPath: String? = "/thumbs/$pk.jpg",
    removedAt: Long? = null,
) = MediaEntity(
    pk = pk, code = "C$pk", type = type, author = author, caption = caption, takenAt = 0,
    width = 1080, height = 1920, carouselCount = null, thumbPath = thumbPath, thumbUrl = "fake://thumb/$pk",
    videoUrl = null, videoUrlExpiresAt = null, collectionNames = "", firstSeenAt = 0, lastSeenAt = 0,
    removedAt = removedAt,
)

suspend fun <T : Any> PagingSource<Int, T>.loadAll(): List<T> {
    val result = load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10_000, placeholdersEnabled = false))
    return (result as PagingSource.LoadResult.Page).data
}
