package com.operations.vaultkit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionTransferTest {
    @Test fun `request and complete transfer round trip`() {
        val request = ExtensionTransfer.createRequest()
        val parsed = ExtensionTransfer.parseRequest(ExtensionTransfer.requestQr(request))!!
        val vault = VaultCrypto.randomBytes(4_000)
        val frames = ExtensionTransfer.frames(parsed, vault)
        assertTrue(frames.size > 1)
        assertArrayEquals(vault, ExtensionTransfer.open(request, frames.reversed()))
    }

    @Test fun `missing or altered frames never open`() {
        val request = ExtensionTransfer.createRequest()
        val frames = ExtensionTransfer.frames(request, byteArrayOf(1, 2, 3))
        assertNull(ExtensionTransfer.open(request, frames.drop(1)))
        assertNull(ExtensionTransfer.open(request, frames.mapIndexed { i, f ->
            if (i == 0) f.dropLast(1) + (if (f.last() == 'A') "B" else "A") else f
        }))
        assertNull(ExtensionTransfer.open(request, frames + "not a transfer frame"))
    }
}
