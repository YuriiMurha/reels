package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class UserMessageTest {
    @Test
    fun adapterAndPacerMessagesAreFixedStringsAndPassThrough() {
        assertEquals("Instagram is limiting requests", InstagramException.RateLimited().userMessage("fallback"))
        assertEquals("Cooling down after a rate limit", PacerRefusal.CoolingDown(0).userMessage("fallback"))
        assertEquals("The 24-hour request budget is used up", PacerRefusal.DailyBudgetReached(0).userMessage("fallback"))
    }

    @Test
    fun anythingElseGetsTheFallback() {
        assertEquals("fallback", IllegalStateException("/data/user/0/app/files/secret.preferences_pb").userMessage("fallback"))
        assertEquals("fallback", IOException().userMessage("fallback"))
    }
}
