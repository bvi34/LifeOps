package com.utilities.app.messages

import com.utilities.app.look.UtilityLook
import com.utilities.app.look.UtilityPalettes
import com.utilities.app.look.UtilityTheme
import com.utilities.app.messages.logic.ChatLook
import com.utilities.app.messages.logic.ChatPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubbles' colours.
 *
 * One property does nearly all the work here and it is the one a designer would check by eye and a
 * test has to check on every surface: **a received bubble must be visible against the surface it
 * sits on.** The default is derived from the surface rather than chosen, which is right — nobody
 * wants to pick it — and derivation is exactly what fails on a surface somebody tinted mid-grey.
 */
class ChatPalettesTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun on(surface: Int): com.utilities.app.messages.logic.ChatPalette {
        val look = ChatLook(
            look = UtilityLook(
                theme = UtilityTheme.CUSTOM,
                surfaceColor = surface,
                textColor = UtilityPalettes.contrastOn(surface)
            )
        )
        return ChatPalettes.resolve(look, white, black)
    }

    @Test
    fun `a received bubble stands off every surface, including the awkward ones`() {
        listOf(
            white,
            black,
            0xFF808080.toInt(),
            0xFF1A2B1A.toInt(),
            0xFFF4E6CE.toInt(),
            UtilityPalettes.BLACK
        ).forEach { surface ->
            val palette = on(surface)
            assertTrue(
                "a received bubble vanishes into ${Integer.toHexString(surface)}",
                UtilityPalettes.contrast(palette.received, palette.base.surface) >= ChatPalettes.MIN_BUBBLE_CONTRAST
            )
            assertNotEquals(palette.received, palette.base.surface)
        }
    }

    @Test
    fun `text is readable in both bubbles, everywhere`() {
        listOf(white, black, 0xFF808080.toInt(), 0xFF203040.toInt()).forEach { surface ->
            val palette = on(surface)
            assertTrue(
                "sent bubble text unreadable on ${Integer.toHexString(surface)}",
                UtilityPalettes.contrast(palette.onSent, palette.sent) >= 4.0f
            )
            assertTrue(
                "received bubble text unreadable on ${Integer.toHexString(surface)}",
                UtilityPalettes.contrast(palette.onReceived, palette.received) >= 4.0f
            )
        }
    }

    @Test
    fun `a bubble colour somebody picked is used, and made opaque`() {
        val look = ChatLook(sentColor = 0x40123456, receivedColor = 0x80654321.toInt())
        val clean = look.sanitized()
        assertEquals(0xFF, (clean.sentColor!! ushr 24) and 0xFF)
        assertEquals(0xFF, (clean.receivedColor!! ushr 24) and 0xFF)
    }

    @Test
    fun `an unreadable sent colour is rescued like any other accent`() {
        val look = ChatLook(
            look = UtilityLook(theme = UtilityTheme.CUSTOM, surfaceColor = white, textColor = black),
            sentColor = 0xFFFFF7B0.toInt()
        )
        val palette = ChatPalettes.resolve(look, white, black)
        assertTrue(
            UtilityPalettes.contrast(palette.sent, palette.base.surface) >= UtilityPalettes.MIN_CONTRAST
        )
    }

    @Test
    fun `an avatar is the same colour for the same person however their number is written`() {
        val palette = on(white)
        assertEquals(
            ChatPalettes.avatar("+1 (555) 010-9999", palette.base),
            ChatPalettes.avatar("5550109999", palette.base)
        )
        assertNotEquals(
            ChatPalettes.avatar("5550109999", palette.base),
            ChatPalettes.avatar("5550108888", palette.base)
        )
    }

    @Test
    fun `an avatar is always drawn with something readable on it`() {
        val palette = on(white)
        listOf("5550109999", "22395", "VERIZON", "+442079460000").forEach { address ->
            val colour = ChatPalettes.avatar(address, palette.base)
            assertTrue(
                "$address's avatar has no readable initial",
                UtilityPalettes.contrast(UtilityPalettes.contrastOn(colour), colour) >= 3.0f
            )
        }
    }

    @Test
    fun `bubble roundness is clamped to something a bubble can be`() {
        assertEquals(0f, ChatLook(bubbleCornerDp = -5f).sanitized().bubbleCornerDp, 0f)
        assertEquals(28f, ChatLook(bubbleCornerDp = 500f).sanitized().bubbleCornerDp, 0f)
    }

    @Test
    fun `the shared look is sanitized along with the chat's own settings`() {
        val clean = ChatLook(look = UtilityLook(warmth = 12f)).sanitized()
        assertEquals(1f, clean.look.warmth, 0f)
    }
}
