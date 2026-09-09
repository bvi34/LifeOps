package com.operations.vaultkit

import java.security.SecureRandom
import java.util.Random
import kotlin.math.ln
import kotlin.math.pow

/**
 * How the app makes a password when you cannot be bothered to.
 *
 * Two shapes, because they are for two different things:
 *
 *  - a **character password**, for the ninety per cent of sites that will take one and that you will
 *    never type by hand;
 *  - a **passphrase** of real words, for the handful you *do* type — a phone unlock, a wifi key read
 *    aloud to a guest, and the vault's own master passphrase.
 *
 * Both draw from [SecureRandom] by default and both report their entropy, because a generator that
 * does not is asking to be trusted about the one thing it could be measured on.
 *
 * ## About the reported entropy
 *
 * [entropyBits] is `length × log2(pool)`: what the password would be worth against somebody who
 * knows the recipe and nothing else. Two honest caveats, stated here rather than buried:
 *
 *  - [PasswordRecipe.requireEachClass] makes the *actual* set slightly smaller than the pool
 *    formula assumes (it excludes the strings missing a class). The overstatement is fractions of a
 *    bit at any usable length and every generator reports it this way; it is named here so nobody
 *    has to wonder whether it was noticed.
 *  - Dropping ambiguous characters genuinely shrinks the pool, and [entropyBits] uses the pool the
 *    recipe actually draws from, so that one is exact.
 */
data class PasswordRecipe(
    val length: Int = 20,
    val lower: Boolean = true,
    val upper: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    /**
     * Leave out `0 O o 1 l I |` and friends.
     *
     * For a password that lives in the vault and is pasted, this is pure cost. For one that has to
     * be read off a screen and typed into a television, it is the difference between working and
     * not.
     */
    val avoidAmbiguous: Boolean = false,
    /** Guarantee at least one character from every enabled class. See the entropy note above. */
    val requireEachClass: Boolean = true
) {
    companion object {
        /** What the generator screen opens on: long, mixed, and not worth arguing with. */
        val DEFAULT = PasswordRecipe()

        const val MIN_LENGTH = 6
        const val MAX_LENGTH = 128
    }
}

/** A generated password and what it is worth, so the two can never be shown apart. */
data class GeneratedSecret(val value: String, val entropyBits: Double) {

    /** A four-step rating, matching the one [SecretStrength] gives a password somebody typed in. */
    val strength: SecretStrength.Rating get() = SecretStrength.rate(entropyBits)
}

object PasswordGenerator {

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"

    /**
     * The symbol set, chosen to be the one every login form actually accepts.
     *
     * Quotes, backslashes and angle brackets are left out — not because they are weak but because
     * they are the characters that break somebody else's escaping, and a password that a website
     * silently truncates is worse than a shorter one that survives.
     */
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~"

    private const val AMBIGUOUS = "0O1lI|`'\";:,."

    private val secureRandom = SecureRandom()

    /** The pool [recipe] draws from, in a fixed order so a seeded [Random] gives a repeatable result. */
    fun pool(recipe: PasswordRecipe): String {
        val classes = classes(recipe)
        return classes.joinToString("")
    }

    private fun classes(recipe: PasswordRecipe): List<String> {
        val filter: (String) -> String =
            if (recipe.avoidAmbiguous) { set -> set.filterNot { it in AMBIGUOUS } } else { set -> set }
        return buildList {
            if (recipe.lower) add(filter(LOWER))
            if (recipe.upper) add(filter(UPPER))
            if (recipe.digits) add(filter(DIGITS))
            if (recipe.symbols) add(filter(SYMBOLS))
        }.filter { it.isNotEmpty() }
    }

    /**
     * Generate a password to [recipe].
     *
     * A recipe with every class turned off falls back to lower case rather than throwing: the
     * generator screen has four switches and somebody will turn all four off, and the right answer
     * to that is a weaker password with an honest entropy figure beside it, not a crash.
     */
    fun password(recipe: PasswordRecipe, random: Random = secureRandom): GeneratedSecret {
        val effective = if (classes(recipe).isEmpty()) recipe.copy(lower = true) else recipe
        val length = effective.length.coerceIn(PasswordRecipe.MIN_LENGTH, PasswordRecipe.MAX_LENGTH)
        val classes = classes(effective)
        val pool = classes.joinToString("")

        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = pool[random.nextInt(pool.length)]

        if (effective.requireEachClass && length >= classes.size) {
            // Plant one character from each class at distinct positions, then let the rest stand.
            // Distinct positions matter: planting twice in the same slot would drop a class and turn
            // the guarantee into a coin flip.
            val positions = (0 until length).toMutableList()
            classes.forEach { set ->
                val at = positions.removeAt(random.nextInt(positions.size))
                chars[at] = set[random.nextInt(set.length)]
            }
        }

        return GeneratedSecret(String(chars), entropyBits(effective.copy(length = length)))
    }

    /**
     * Generate a passphrase: [words] words from [VaultWords], joined by [separator].
     *
     * The optional trailing number is not there for entropy — it is worth three and a bit bits and
     * the words are worth ten each — it is there because a depressing number of forms insist on a
     * digit, and a passphrase that fails validation gets replaced by `Summer2024!`.
     */
    fun passphrase(
        words: Int = 5,
        separator: String = "-",
        capitalise: Boolean = false,
        appendNumber: Boolean = false,
        random: Random = secureRandom
    ): GeneratedSecret {
        val count = words.coerceIn(MIN_WORDS, MAX_WORDS)
        val list = VaultWords.list
        val picked = (0 until count).map { list[random.nextInt(list.size)] }
            .map { if (capitalise) it.replaceFirstChar(Char::uppercaseChar) else it }
        val tail = if (appendNumber) separator + random.nextInt(1000).toString().padStart(3, '0') else ""
        val bits = passphraseEntropyBits(count) + if (appendNumber) log2(1000.0) else 0.0
        return GeneratedSecret(picked.joinToString(separator) + tail, bits)
    }

    /** `length × log2(pool)`. See the caveats on [PasswordRecipe]. */
    fun entropyBits(recipe: PasswordRecipe): Double {
        val poolSize = pool(recipe).length
        if (poolSize <= 1) return 0.0
        val length = recipe.length.coerceIn(PasswordRecipe.MIN_LENGTH, PasswordRecipe.MAX_LENGTH)
        return length * log2(poolSize.toDouble())
    }

    /**
     * `words × log2(list size)`.
     *
     * Exactly right, and worth saying why: the word list is public — it is in this file's sibling —
     * so an attacker knows the pool, and the entropy is entirely in the dice. A word list kept
     * secret would be worth more bits and be worth nothing at all the first time somebody read the
     * APK.
     */
    fun passphraseEntropyBits(words: Int): Double =
        words.coerceIn(MIN_WORDS, MAX_WORDS) * log2(VaultWords.list.size.toDouble())

    const val MIN_WORDS = 3
    const val MAX_WORDS = 12

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}

/**
 * How good is a password somebody typed in?
 *
 * This is an *estimate of an estimate* and it says so. Real strength meters (zxcvbn and its kin)
 * carry dictionaries, keyboard-adjacency graphs and a list of the ten thousand most leaked
 * passwords, which is megabytes this app is not going to ship for the sake of a coloured bar. What
 * is here instead:
 *
 *  - the pool implied by the characters actually used, times the length, as a ceiling;
 *  - a penalty for a password that is one repeated character or a straight run (`aaaaaa`, `abcdef`,
 *    `123456`), which is what the cheap end of the real world looks like;
 *  - a floor of zero, and no credit for anything clever.
 *
 * It will call `Tr0ub4dor&3` strong, and it is wrong about that, and every meter of this kind is.
 * What it is reliably right about is the case the audit screen exists for: a six-character
 * all-lower-case password is weak, and so is the one you have used on four other sites.
 */
object SecretStrength {

    enum class Rating { EMPTY, WEAK, FAIR, STRONG, EXCELLENT }

    fun bits(secret: String): Double {
        if (secret.isEmpty()) return 0.0
        var pool = 0
        if (secret.any { it in 'a'..'z' }) pool += 26
        if (secret.any { it in 'A'..'Z' }) pool += 26
        if (secret.any { it.isDigit() }) pool += 10
        if (secret.any { !it.isLetterOrDigit() }) pool += 25
        if (pool == 0) pool = 26
        val raw = secret.length * (ln(pool.toDouble()) / ln(2.0))
        return (raw * repetitionFactor(secret)).coerceAtLeast(0.0)
    }

    /**
     * 1.0 for anything with variety in it, dropping towards 0.2 for a string that is one character
     * repeated or a straight alphabetic/numeric run. Blunt on purpose — it is a cliff for the
     * obviously bad rather than a slope everybody slides down a little.
     */
    private fun repetitionFactor(secret: String): Double {
        if (secret.length < 3) return 1.0
        val distinct = secret.toSet().size
        val runs = secret.zipWithNext().count { (a, b) -> b - a == 1 || b == a }
        val runFraction = runs.toDouble() / (secret.length - 1)
        val distinctFraction = distinct.toDouble() / secret.length
        return when {
            distinct == 1 -> 0.2
            runFraction > 0.8 -> 0.3
            distinctFraction < 0.4 -> 0.6
            else -> 1.0
        }
    }

    fun rate(bits: Double): Rating = when {
        bits <= 0.0 -> Rating.EMPTY
        bits < 40 -> Rating.WEAK
        bits < 60 -> Rating.FAIR
        bits < 80 -> Rating.STRONG
        else -> Rating.EXCELLENT
    }

    fun rate(secret: String): Rating = if (secret.isEmpty()) Rating.EMPTY else rate(bits(secret))

    /** A 0..1 figure for a progress bar, saturating at 100 bits so the bar has somewhere to end. */
    fun fraction(bits: Double): Float = (bits / 100.0).coerceIn(0.0, 1.0).toFloat()

    /** Roughly how long an offline attacker takes at [guessesPerSecond], as a phrase. */
    fun crackTime(bits: Double, guessesPerSecond: Double = 1e11): String {
        if (bits <= 0) return "instantly"
        val seconds = 2.0.pow(bits - 1) / guessesPerSecond
        return when {
            seconds < 1 -> "instantly"
            seconds < 60 -> "seconds"
            seconds < 3_600 -> "minutes"
            seconds < 86_400 -> "hours"
            seconds < 2_592_000 -> "days"
            seconds < 31_536_000 -> "months"
            seconds < 31_536_000 * 100.0 -> "years"
            seconds < 31_536_000 * 1e6 -> "centuries"
            else -> "longer than anyone will wait"
        }
    }
}
