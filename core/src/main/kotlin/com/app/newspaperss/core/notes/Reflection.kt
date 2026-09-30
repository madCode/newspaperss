package com.app.newspaperss.core.notes

/**
 * The question on an edition's closing page, also first in its notes, so what the reader thought
 * about on the e-reader is waiting for them in their notes app.
 */
object Reflection {
    // Open and short: something to turn over on the walk to work, not an assignment. None
    // assumes a kind of article, since a paper can be all essays or all comics.
    val QUESTIONS = listOf(
        "Which piece would you tell a friend about, and what would you say?",
        "Did anything change your mind, even a little?",
        "Which two pieces would argue with each other?",
        "What surprised you?",
        "Whose point of view was missing?",
        "Which sentence stayed with you?",
        "What would you ask one of the writers?",
        "What would you like to read more about?",
        "What did you enjoy most, and why?",
        "Is there something here you want to act on?",
        "Where did you disagree, and why?",
        "What would you have written differently?",
    )

    /** One question per edition, in turn: consecutive editions never repeat. */
    fun forEdition(editionId: Long): String = QUESTIONS[Math.floorMod(editionId, QUESTIONS.size.toLong()).toInt()]
}
