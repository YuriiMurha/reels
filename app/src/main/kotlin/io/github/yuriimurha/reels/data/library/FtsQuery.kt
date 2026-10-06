package io.github.yuriimurha.reels.data.library

/** Turns free text into a safe FTS4 MATCH expression: every word becomes a lowercase prefix term, all must match. */
object FtsQuery {
    private val word = Regex("[\\p{L}\\p{N}]+")

    fun from(input: String): String? {
        val terms = word.findAll(input.lowercase()).map { it.value }.toList()
        if (terms.isEmpty()) return null
        // Lowercasing turns FTS operators (OR, AND, NOT, NEAR) into plain terms; quotes, '-', '*', '(' are dropped.
        return terms.joinToString(" ") { "$it*" }
    }
}
