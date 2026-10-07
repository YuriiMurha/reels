package io.github.yuriimurha.reels.di

import io.github.yuriimurha.reels.data.media.FakeMediaFetcher
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy

/** Where the library comes from (spec 4.5). Each backend owns the pacing that matches it. */
sealed interface Backend {
    val client: InstagramClient
    val fetcher: MediaFetcher
    val pacer: Pacer

    /**
     * Fixture library for development. Fast pacing with its own in-memory budgets, so fake syncs never
     * touch the real 24 h budget or cooldown, and fast pacing can never reach real traffic.
     */
    class Fake(
        override val client: FakeInstagramClient = FakeInstagramClient(),
        override val fetcher: MediaFetcher = FakeMediaFetcher(),
    ) : Backend {
        override val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore())
    }

    /**
     * Real Instagram. Its pacer is the process's one Conservative `instagramPacer`, never a new one: a second Pacer
     * would have its own gap and its own idea of the budget.
     */
    class Real(
        override val client: InstagramClient,
        override val fetcher: MediaFetcher,
        override val pacer: Pacer,
    ) : Backend
}
