package com.operations.vaultkit

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {

    /** A seeded [Random] so the assertions below are about the generator, not about luck. */
    private fun seeded(seed: Long = 42) = Random(seed)

    @Test
    fun `a generated password is the length asked for and drawn from the pool`() {
        val recipe = PasswordRecipe(length = 24)

        val generated = PasswordGenerator.password(recipe, seeded())

        assertEquals(24, generated.value.length)
        assertTrue(generated.value.all { it in PasswordGenerator.pool(recipe) })
    }

    @Test
    fun `every enabled class is present, every disabled one absent`() {
        val recipe = PasswordRecipe(length = 12, lower = true, upper = true, digits = true, symbols = false)

        repeat(50) { seed ->
            val value = PasswordGenerator.password(recipe, seeded(seed.toLong())).value
            assertTrue(value, value.any { it in 'a'..'z' })
            assertTrue(value, value.any { it in 'A'..'Z' })
            assertTrue(value, value.any { it.isDigit() })
            assertTrue(value, value.none { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun `two calls do not produce the same password`() {
        val recipe = PasswordRecipe(length = 16)

        val first = PasswordGenerator.password(recipe).value
        val second = PasswordGenerator.password(recipe).value

        assertNotEquals(first, second)
    }

    @Test
    fun `avoiding ambiguous characters really removes them, and lowers the reported entropy`() {
        val plain = PasswordRecipe(length = 20)
        val unambiguous = plain.copy(avoidAmbiguous = true)

        val value = PasswordGenerator.password(unambiguous, seeded()).value

        assertTrue(value.none { it in "0O1lI|" })
        assertTrue(PasswordGenerator.entropyBits(unambiguous) < PasswordGenerator.entropyBits(plain))
    }

    @Test
    fun `turning every class off yields a password rather than a crash`() {
        val recipe = PasswordRecipe(length = 10, lower = false, upper = false, digits = false, symbols = false)

        val generated = PasswordGenerator.password(recipe, seeded())

        assertEquals(10, generated.value.length)
        assertTrue(generated.value.all { it in 'a'..'z' })
    }

    @Test
    fun `an absurd length is clamped at both ends`() {
        assertEquals(
            PasswordRecipe.MIN_LENGTH,
            PasswordGenerator.password(PasswordRecipe(length = 1), seeded()).value.length
        )
        assertEquals(
            PasswordRecipe.MAX_LENGTH,
            PasswordGenerator.password(PasswordRecipe(length = 5_000), seeded()).value.length
        )
    }

    @Test
    fun `entropy is length times log2 of the pool`() {
        val recipe = PasswordRecipe(length = 16, lower = true, upper = true, digits = true, symbols = false)

        // 26 + 26 + 10 = 62 characters, so just under 6 bits each.
        assertEquals(16 * 5.954, PasswordGenerator.entropyBits(recipe), 0.01)
    }

    @Test
    fun `a passphrase is words from the list, joined, and worth ten bits a word`() {
        val generated = PasswordGenerator.passphrase(words = 5, random = seeded())

        val words = generated.value.split("-")
        assertEquals(5, words.size)
        assertTrue(words.all { it in VaultWords.list })
        assertEquals(50.1, generated.entropyBits, 0.2)
        assertEquals(SecretStrength.Rating.FAIR, generated.strength)
    }

    @Test
    fun `a passphrase can be capitalised and given the digit some forms insist on`() {
        val generated = PasswordGenerator.passphrase(
            words = 4,
            separator = ".",
            capitalise = true,
            appendNumber = true,
            random = seeded()
        )

        val parts = generated.value.split(".")
        assertEquals(5, parts.size)
        assertTrue(parts.take(4).all { it.first().isUpperCase() })
        assertTrue(parts.last().all { it.isDigit() })
        assertEquals(3, parts.last().length)
    }

    @Test
    fun `the word list is big enough to mean what the entropy figure says`() {
        assertTrue("a short list would make every passphrase weaker than advertised", VaultWords.list.size >= 1_000)
        assertEquals("duplicates would skew the odds", VaultWords.list.size, VaultWords.list.toSet().size)
        assertTrue(VaultWords.list.all { word -> word.all { it in 'a'..'z' } })
        assertTrue(VaultWords.list.all { it.length in 3..12 })
        assertEquals("the list is sorted, so a diff to it is readable", VaultWords.list.sorted(), VaultWords.list)
    }

    @Test
    fun `strength rates the obviously bad as bad and the generated as good`() {
        assertEquals(SecretStrength.Rating.EMPTY, SecretStrength.rate(""))
        assertEquals(SecretStrength.Rating.WEAK, SecretStrength.rate("hunter2"))
        assertEquals(SecretStrength.Rating.WEAK, SecretStrength.rate("aaaaaaaaaaaaaaaaaaaa"))
        assertEquals(SecretStrength.Rating.WEAK, SecretStrength.rate("abcdefghijklmnop"))
        assertEquals(SecretStrength.Rating.WEAK, SecretStrength.rate("123456"))

        val generated = PasswordGenerator.password(PasswordRecipe(length = 20)).value
        assertEquals(SecretStrength.Rating.EXCELLENT, SecretStrength.rate(generated))
    }

    @Test
    fun `crack time is a phrase, and the bar has somewhere to end`() {
        assertEquals("instantly", SecretStrength.crackTime(0.0))
        assertEquals("longer than anyone will wait", SecretStrength.crackTime(200.0))
        assertEquals(1f, SecretStrength.fraction(150.0), 0.001f)
        assertEquals(0f, SecretStrength.fraction(0.0), 0.001f)
    }
}
