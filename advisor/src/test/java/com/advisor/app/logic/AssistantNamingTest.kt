package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantNamingTest {

    @Test
    fun detects_call_yourself_even_as_a_question() {
        // The screenshot case: phrased as a question, so WriteIntent skips it — this must still fire.
        assertEquals("Ava", AssistantNaming.detect("can you call yourself Ava instead of advisor?"))
        assertEquals("Ava", AssistantNaming.detect("call yourself Ava"))
    }

    @Test
    fun detects_the_common_naming_phrasings() {
        assertEquals("Ava", AssistantNaming.detect("I'll call you Ava"))
        assertEquals("Ava", AssistantNaming.detect("can I call you Ava?"))
        assertEquals("Ava", AssistantNaming.detect("let me call you Ava"))
        assertEquals("Ava", AssistantNaming.detect("go by Ava from now on"))
        assertEquals("Ava", AssistantNaming.detect("you should go by the name Ava"))
        assertEquals("Ava", AssistantNaming.detect("rename yourself to Ava"))
        assertEquals("Ava", AssistantNaming.detect("name yourself Ava"))
        assertEquals("Ava", AssistantNaming.detect("your name is Ava now"))
        assertEquals("Ava", AssistantNaming.detect("your name should be Ava"))
    }

    @Test
    fun keeps_the_name_casing_the_user_gave() {
        assertEquals("ava", AssistantNaming.detect("call yourself ava"))
        assertEquals("Nova", AssistantNaming.detect("Can you go by Nova?"))
    }

    @Test
    fun ignores_non_names_and_plain_questions() {
        // "what should I call you" is an identity question, not a rename — no name follows.
        assertNull(AssistantNaming.detect("what should I call you?"))
        assertNull(AssistantNaming.detect("call yourself something else"))
        assertNull(AssistantNaming.detect("your name is nice"))
        assertNull(AssistantNaming.detect("what am I reading?"))
        assertNull(AssistantNaming.detect("who are you?"))
        assertNull(AssistantNaming.detect(""))
    }

    @Test
    fun does_not_treat_the_default_name_as_a_rename() {
        assertNull(AssistantNaming.detect("your name is Advisor"))
    }
}
