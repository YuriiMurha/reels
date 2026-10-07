package io.github.yuriimurha.reels.sync.pacing

/** The Pacer refused to make a request. No request was sent. */
sealed class PacerRefusal(message: String) : Exception(message) {
    class CoolingDown(val until: Long) : PacerRefusal("Cooling down after a rate limit")

    class RunBudgetReached : PacerRefusal("This run used its request budget")

    class DailyBudgetReached(val freesAt: Long) : PacerRefusal("The 24-hour request budget is used up")
}
