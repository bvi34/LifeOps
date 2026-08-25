package com.people.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeopleSyncCodecTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val envelope = PeerEnvelope(
        peer = Peers.PEOPLE,
        packets = listOf(
            VersionedPacket(1L, PersonPacket("key-1", "Ellie", birthDate = "2019-04-02", updatedAt = 1_000L)),
            VersionedPacket(2L, PersonPacket("key-2", "Rob", email = "rob@example.com", deleted = true, updatedAt = 2_000L))
        ),
        acks = mapOf(Peers.LIFEOPS to 7L, Peers.HEALTH to 3L)
    )

    @Test
    fun `an envelope round-trips`() {
        val decoded = PeopleSyncCodec.decode(PeopleSyncCodec.encode(envelope))
        assertEquals(envelope, decoded)
    }

    @Test
    fun `unknown fields are ignored and missing ones fall back`() {
        val json = """
            {"peer":"lifeops","packets":[{"version":4,"person":{"key":"k","name":"Ada","favourite":"tea"}}]}
        """.trimIndent()
        val decoded = PeopleSyncCodec.decode(json)!!
        val person = decoded.packets.single().payload
        assertEquals("Ada", person.name)
        assertEquals(0L, person.updatedAt)
        assertTrue(decoded.acks.isEmpty())
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(PeopleSyncCodec.decode("not json"))
        assertNull(PeopleSyncCodec.decode(""))
    }

    @Test
    fun `the store writes a file per peer and reads the others back`() {
        val store = PeopleEnvelopeStore(folder.newFolder("sync"))
        assertNull(store.read(Peers.LIFEOPS))

        store.write(envelope)
        assertEquals(envelope, store.read(Peers.PEOPLE))
        assertNull(store.read(Peers.LIFEOPS))
    }

    @Test
    fun `rewriting a peer's envelope replaces it rather than appending`() {
        val store = PeopleEnvelopeStore(folder.newFolder("sync"))
        store.write(envelope)
        val later = PeerEnvelope(Peers.PEOPLE, emptyList(), mapOf(Peers.LIFEOPS to 99L))
        store.write(later)

        val read = store.read(Peers.PEOPLE)!!
        assertTrue(read.packets.isEmpty())
        assertEquals(99L, read.ackFor(Peers.LIFEOPS))
    }

    @Test
    fun `a temp file is never left behind for a reader to trip over`() {
        val dir = folder.newFolder("sync")
        PeopleEnvelopeStore(dir).write(envelope)
        assertEquals(listOf("people-people.json"), dir.list()!!.sorted())
    }
}
