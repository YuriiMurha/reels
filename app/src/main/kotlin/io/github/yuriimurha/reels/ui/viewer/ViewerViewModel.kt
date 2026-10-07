package io.github.yuriimurha.reels.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ViewerViewModel(
    source: MediaSource,
    startIndex: Int,
    private val library: LibraryRepository,
    private val resolver: VideoSourceResolver,
    private val settings: SettingsStore,
) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source, initialIndex = startIndex).cachedIn(viewModelScope)
    val muted: Flow<Boolean> = settings.muted

    fun toggleMute() {
        viewModelScope.launch { settings.setMuted(!settings.muted.first()) }
    }

    suspend fun videoUri(media: MediaEntity): Uri? = resolver.resolve(media)

    suspend fun collectionNames(pk: String): List<String> = library.collectionsOf(pk).map { it.name }
}
