package com.app.newspaperss.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReflectionTest {
    @Test
    fun editionsTakeTheQuestionsInTurnWithoutRepeatingTheLastOne() {
        val asked = (1L..Reflection.QUESTIONS.size.toLong()).map(Reflection::forEdition)
        assertEquals("a run of editions asks every question once", Reflection.QUESTIONS.toSet(), asked.toSet())
        assertNotEquals(Reflection.forEdition(Reflection.QUESTIONS.size.toLong()), Reflection.forEdition(Reflection.QUESTIONS.size + 1L))
    }
}
