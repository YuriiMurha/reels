package io.github.yuriimurha.reels.instagram.web

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaLinksTest {
    private fun c(width: Int) = ImageCandidate("https://cdn.example.invalid/$width.jpg", width, width)

    @Test fun choosesTheNarrowestCandidateAtLeast720Wide() =
        assertEquals(720, MediaLinks.chooseThumbnail(listOf(c(1080), c(720), c(480)))?.width)

    @Test fun fallsBackToTheWidestWhenAllAreSmall() =
        assertEquals(640, MediaLinks.chooseThumbnail(listOf(c(320), c(640)))?.width)

    @Test fun noCandidatesMeansNoThumbnail() = assertNull(MediaLinks.chooseThumbnail(emptyList()))

    @Test fun readsTheHexExpiryInOe() =
        assertEquals(Instant.ofEpochSecond(0x6720A3F0), MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4?x=1&oe=6720A3F0"))

    @Test fun missingOrMalformedOeIsNull() {
        assertNull(MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4"))
        assertNull(MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4?oe=zz"))
        assertNull(MediaLinks.expiresAt("not a url"))
    }
}
