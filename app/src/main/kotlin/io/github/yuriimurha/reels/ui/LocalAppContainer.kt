package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yuriimurha.reels.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
