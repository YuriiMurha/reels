package io.github.yuriimurha.reels.ui.sync

import io.github.yuriimurha.reels.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PasteOutcomeTest {
    @Test
    fun onlyValidCountsAsAccepted() {
        assertEquals(PasteOutcome.Accepted, pasteOutcome(SessionState.Valid("tester")))
    }

    @Test
    fun nullMeansItWasNotASessionId() {
        assertEquals(PasteOutcome.Rejected("That doesn't look like a sessionid"), pasteOutcome(null))
    }

    @Test
    fun anInstagramRejectionIsNotAcceptanceAndNeverNamesAnAccount() {
        val rejections = listOf(
            SessionState.Expired("previous_account"),
            SessionState.Expired(null),
            SessionState.Challenge("https://www.instagram.com/challenge/x/", "previous_account"),
            SessionState.Challenge(null, null),
        )
        for (result in rejections) {
            val outcome = pasteOutcome(result)
            assertEquals(PasteOutcome.Rejected("Instagram rejected that session; your current login is unchanged"), outcome)
            assertFalse((outcome as PasteOutcome.Rejected).message.contains("previous_account"))
        }
    }

    @Test
    fun loggedOutIsNeverAcceptance() {
        assertEquals(PasteOutcome.Rejected("Couldn't check that session"), pasteOutcome(SessionState.LoggedOut))
    }
}
