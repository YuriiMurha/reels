package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.StoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionStateTest {
    private val url = "https://www.instagram.com/challenge/x/"

    @Test
    fun challengeNeverPrintsItsUrl() {
        val text = SessionState.Challenge(url, "tester").toString()
        assertEquals("Challenge(challengeUrl=██, handle=tester)", text)
        assertFalse(text.contains("challenge/x"), text)
    }

    @Test
    fun storedSessionNeverPrintsTheChallengeUrl() {
        val text = SessionState.Challenge(url, "tester").toStored().toString()
        assertEquals("StoredSession(kind=CHALLENGE, handle=tester, challengeUrl=██)", text)
        assertFalse(text.contains("challenge/x"), text)
    }

    @Test
    fun anAbsentUrlStillPrintsAsAbsent() {
        assertEquals("Challenge(challengeUrl=null, handle=null)", SessionState.Challenge(null, null).toString())
        assertEquals("StoredSession(kind=VALID, handle=a, challengeUrl=null)", StoredSession("VALID", "a", null).toString())
    }

    @Test
    fun theUrlStillRoundTripsThroughTheStoredForm() {
        val state = SessionState.Challenge(url, "tester")
        assertEquals(state, state.toStored().toState())
    }
}
