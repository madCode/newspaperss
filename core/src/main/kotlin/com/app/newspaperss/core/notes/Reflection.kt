package com.app.newspaperss.core.notes

/**
 * The question on an edition's closing page, also at the top of its notes, so what the reader
 * thought about on the e-reader is waiting for them in their notes app.
 */
object Reflection {
    // Open and short: something to turn over on the walk to work, not an assignment. Each has to
    // make sense after any paper, including one long read, or only comics and fun.
    val QUESTIONS = listOf(
        "Which piece would you tell a friend about, and what would you say?",
        "What surprised you?",
        "What did you enjoy most, and why?",
        "What would you like to read more about?",
        "Is there something here you want to act on?",
        "What will you still be thinking about tonight?",
        "Did anything change how you see something, even a little?",
        "Which piece felt closest to your own life?",
        "What made you smile?",
        "If you could ask one of the writers or artists something, what would it be?",
        "What do you want to remember from today's paper?",
        "Which piece was hardest to put down?",
    )

    /**
     * Picked by edition id, so an edition's notes ask what its book asked. Ids also go to builds
     * that found nothing, so the questions vary from one paper to the next but can repeat.
     */
    fun forEdition(editionId: Long): String = QUESTIONS[Math.floorMod(editionId, QUESTIONS.size.toLong()).toInt()]
}
