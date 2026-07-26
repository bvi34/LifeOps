package com.citation.core.sync

import java.io.File

/**
 * A **file-drop transport** for the sync seam: the mailbox as two JSON files in a shared directory.
 *
 * This is the simplest possible realisation of "Citation is a peer on the spine" for an offline,
 * single-user system — no server, no daemon, just a folder both apps can see (local dir, synced
 * folder, etc.). Citation writes its [OutboundEnvelope] to `citation-outbound.json` for LifeOps to
 * read, and reads LifeOps' [InboundEnvelope] from `lifeops-inbound.json`. Being plain `java.io`, it
 * is JVM-testable end to end (see the tests) and the Android app just points it at a real directory.
 */
class FileEnvelopeStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private val outboundFile get() = File(dir, OUTBOUND)
    private val inboundFile get() = File(dir, INBOUND)

    /** Write Citation's outbound envelope for LifeOps to pick up. */
    fun writeOutbound(env: OutboundEnvelope) {
        outboundFile.writeText(SyncCodec.encodeOutbound(env))
    }

    /** Read LifeOps' inbound envelope, or `null` if it hasn't written one yet. */
    fun readInbound(): InboundEnvelope? {
        if (!inboundFile.exists()) return null
        return runCatching { SyncCodec.decodeInbound(inboundFile.readText()) }.getOrNull()
    }

    // The following two mirror the LifeOps side; useful for tests and for a local loopback.

    /** (LifeOps side) read what Citation wrote. */
    fun readOutbound(): OutboundEnvelope? {
        if (!outboundFile.exists()) return null
        return runCatching { SyncCodec.decodeOutbound(outboundFile.readText()) }.getOrNull()
    }

    /** (LifeOps side) write the response Citation will read. */
    fun writeInbound(env: InboundEnvelope) {
        inboundFile.writeText(SyncCodec.encodeInbound(env))
    }

    private companion object {
        const val OUTBOUND = "citation-outbound.json"
        const val INBOUND = "lifeops-inbound.json"
    }
}
