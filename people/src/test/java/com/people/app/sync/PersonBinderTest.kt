package com.people.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonBinderTest {

    private fun candidate(id: String, key: String?, name: String, email: String? = null) =
        PersonBinder.Candidate(id, key, name, email)

    @Test
    fun `the shared key wins over everything else`() {
        val local = listOf(
            candidate("a", "key-1", "Ellie", "ellie@example.com"),
            candidate("b", "key-2", "Ellie", "ellie@example.com")
        )
        val decision = PersonBinder.bind(
            PersonPacket(personKey = "key-2", name = "Someone Else"),
            local
        )
        assertEquals(PersonBinder.Decision.Bind("b", PersonBinder.Reason.KEY), decision)
    }

    @Test
    fun `email binds across differently-typed names`() {
        val local = listOf(candidate("a", "key-1", "Rob", "ROB@Example.com"))
        val decision = PersonBinder.bind(
            PersonPacket(personKey = "key-9", name = "Robert Smith", email = "rob@example.com"),
            local
        )
        assertEquals(PersonBinder.Decision.Bind("a", PersonBinder.Reason.EMAIL), decision)
    }

    @Test
    fun `a name binds through case, punctuation and accents`() {
        val local = listOf(candidate("a", "key-1", "Dr. José  García-López"))
        val decision = PersonBinder.bind(
            PersonPacket(personKey = "key-9", name = "dr jose garcia lopez"),
            local
        )
        assertEquals(PersonBinder.Decision.Bind("a", PersonBinder.Reason.NAME), decision)
    }

    @Test
    fun `a name never binds over a contradicting email`() {
        // Two Alexes in one household: the local one has an email, the arriving one has a different
        // email. Same name is not enough to call them the same human.
        val local = listOf(candidate("a", "key-1", "Alex", "alex.senior@example.com"))
        val decision = PersonBinder.bind(
            PersonPacket(personKey = "key-9", name = "alex", email = "alex.junior@example.com"),
            local
        )
        assertEquals(PersonBinder.Decision.Create, decision)
    }

    @Test
    fun `a name does bind when neither side has an email to disagree with`() {
        val local = listOf(candidate("a", "key-1", "Alex"))
        val decision = PersonBinder.bind(PersonPacket(personKey = "key-9", name = "alex"), local)
        assertEquals(PersonBinder.Decision.Bind("a", PersonBinder.Reason.NAME), decision)
    }

    @Test
    fun `nobody matching means create`() {
        val local = listOf(candidate("a", "key-1", "Ellie", "ellie@example.com"))
        val decision = PersonBinder.bind(PersonPacket(personKey = "key-9", name = "Marta"), local)
        assertEquals(PersonBinder.Decision.Create, decision)
    }

    @Test
    fun `an empty name cannot bind to anything`() {
        val local = listOf(candidate("a", "key-1", ""))
        assertEquals(
            PersonBinder.Decision.Create,
            PersonBinder.bind(PersonPacket(personKey = "key-9", name = "   "), local)
        )
    }
}
