package com.app.newspaperss.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReflectionTest {
    @Test
    fun editionsInARowGetDifferentQuestionsAndEveryQuestionComesUp() {
        val n = Reflection.QUESTIONS.size.toLong()
        assertEquals("no question listed twice", Reflection.QUESTIONS.size, Reflection.QUESTIONS.distinct().size)
        (1L..n).forEach { assertNotEquals(Reflection.forEdition(it), Reflection.forEdition(it + 1)) }
        assertEquals(Reflection.QUESTIONS.toSet(), (1L..n).map(Reflection::forEdition).toSet())
    }
}
