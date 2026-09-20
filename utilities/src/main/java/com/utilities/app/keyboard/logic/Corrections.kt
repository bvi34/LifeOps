package com.utilities.app.keyboard.logic

/** A word the keyboard changed, and what it changed it from. Kept so backspace can put it back. */
data class Correction(val from: String, val to: String)

/**
 * Autocorrect: the one feature of a keyboard that is famous for being wrong.
 *
 * ## What it will not do
 *
 * The refusals are the design. Everything here is in service of the rule that a keyboard which
 * changes a word somebody meant is far worse than one that leaves a typo alone, because the typo is
 * visible and the correction is not — it is read back as what you meant until somebody else reads
 * it as what you said.
 *
 * So it will not touch:
 *
 *  - **a word it knows.** Anything in the dictionary, and anything this household has ever typed,
 *    is left exactly as it is. There is no "did you really mean `its`" here: both are words;
 *  - **a capitalised word**, unless it starts the sentence. `Ada`, `Wetherby`, `Lidl` are how names
 *    arrive, and a keyboard that corrects them is one people turn off within the hour. At the start
 *    of a sentence a capital says nothing, so `Teh` is fair game and comes back as `The`;
 *  - **a word with capitals inside it** — `McGrath`, `iPhone`, `PIN` — which is somebody being
 *    deliberate;
 *  - **anything shorter than [MIN_LENGTH]**. Two letters is not enough signal to overrule a thumb;
 *  - **a plural possessive.** `dogs'` is left alone whenever `dogs` is a word, because the
 *    cheapest-looking repair of it is `dog's`, which is a different thing to have said;
 *  - **a compound noun.** `username` and `facebook` read as two ordinary words, exactly as `alot`
 *    does, and the only thing that separates them is the word at the front — see [GLUED];
 *  - **anything that does not clear [ACCEPT]**, which is most of what gets this far.
 *
 * ## How far a typo is allowed to travel
 *
 * Every candidate is one edit away from what was typed — or is it read as two words, which is the
 * one candidate that adds something rather than repairing something; see [SPLIT]. Each is scored as
 * *how good a word it is* minus *how unlikely that edit is* — the word's standing from [Vocabulary],
 * the edit's cost from the constants below. Because the two are on one scale, the ladder falls out
 * of the arithmetic rather than having to be written down as rules:
 *
 *  - an ordinary slip — two letters swapped, a missing apostrophe, a doubled letter, the key next
 *    door — reaches any ordinary word;
 *  - a letter from the other side of the keyboard, which is somebody spelling a word wrong rather
 *    than mistyping it, reaches only the few thousand commonest words in the language;
 *  - an extra letter that is neither doubled nor next door reaches nothing but a word this
 *    household has typed itself;
 *  - and the long tail of the dictionary is never reached at all. It is recognised, so it is never
 *    corrected; it is just never a destination. See [Vocabulary.RARE].
 *
 * A word this household has typed sits above every word in the dictionary and can absorb any edit
 * on the list.
 *
 * Which edits are cheap is not a guess about spelling, it is a guess about thumbs — see
 * [KeyNeighbours]. `sog` is `dog` because `s` is next to `d`; `sog` is not `log` even though `log`
 * is the commoner word.
 */
object Corrections {

    /** Below this, a word is too short to be worth overruling. */
    const val MIN_LENGTH = 3

    /** Above this it is a token, a licence key or a cat on the keyboard, not a word. */
    const val MAX_LENGTH = 20

    /** What a candidate has to score before it is allowed to change anything. */
    const val ACCEPT = 40

    /**
     * What it has to score when the word was capitalised.
     *
     * Higher, and this is the single most valuable number in the file. A capital at the start of a
     * sentence says nothing by itself, so `Teh` has to be correctable — but a great many capitalised
     * words are names, and a name is the thing people most hate having changed. Setting the bar here
     * means a capitalised word is only corrected when the repair is a cheap one: `Teh` is two letters
     * the wrong way round and becomes `The`, while `Ada` is one far-away letter from `Add` and is
     * left exactly as it was.
     */
    const val CAPITALISED_ACCEPT = 52

    /** Two letters in the wrong order — the commonest way a word comes out wrong. */
    const val TRANSPOSED = 2

    /** A missing apostrophe. `dont`, `cant`, `wont`: the one letter people leave out on purpose. */
    const val APOSTROPHE = 0

    /** A doubled letter typed once, or once too often. `helo`, `belive`, `occured`, `helllo`. */
    const val DOUBLED = 4

    /** The key next door. */
    const val NEAR = 6

    /** A letter missed. */
    const val MISSING = 10

    /** A letter too many, where it is the key next to one of its neighbours: two keys hit at once. */
    const val SLIP = 8

    /**
     * A letter too many that is neither doubled nor next door.
     *
     * Expensive, and the reason is `alot`. The cheap repair of it is `lot`, which is a different
     * thing to have said, and a keyboard is not in a position to offer `a lot` because that is two
     * words and this is a one-word correction. Pricing an inexplicable extra letter out of the
     * market leaves `alot` alone, which is the right answer when the alternative is changing what
     * somebody said.
     */
    const val EXTRA = 22

    /** A letter from somewhere else entirely: a misspelling rather than a slip. */
    const val FAR = 18

    /**
     * On top of everything else, for a candidate that starts with a different letter.
     *
     * The first letter of a word is the one people do not get wrong. It is typed deliberately,
     * after a space, with the whole word still in mind — the slips come later, in the middle,
     * where the thumb is moving fastest. So a correction that rewrites it is nearly always the
     * keyboard having a better idea than the person, and the surcharge is set so that only a word
     * this household has typed itself can afford one. It is what leaves `alot` alone rather than
     * making it `slot`, and `kat` alone rather than `cat`.
     *
     * A transposition of the first two letters can still pay it, which is the one case that
     * matters: `hte` really is `the`.
     */
    const val FIRST_LETTER = 15

    /**
     * A space put back between two words that were typed as one: `alot`, `infact`, `atleast`.
     *
     * The one correction here that adds a word rather than repairing one, and the one that needed a
     * second idea before it was safe. Splitting anything that reads as two ordinary words turns
     * `alot` into `a lot` — and `username` into `user name`, `runtime` into `run time` and
     * `facebook` into `face book`, because a compound noun is also two ordinary words and no
     * dictionary can tell the two cases apart.
     *
     * What tells them apart is *which word comes first*. The words people glue to the front of the
     * next one are a closed class of little ones — `a`, `in`, `at`, `of`, `no`, `each`, `every`,
     * `thank` — and they are the same handful every time, so they are written down in [GLUED]. The
     * first halves of the compounds above are `user`, `run`, `face`: content words, and not on the
     * list. That one rule is the difference between a feature and a nuisance.
     *
     * The rest of the hedging: the second half has to be an ordinary word in its own right and at
     * least two letters, and the arithmetic here keeps a split under [CAPITALISED_ACCEPT], so
     * `Facebook` and `YouTube` are never touched whatever else is true.
     */
    const val SPLIT = 12

    /**
     * The words people type stuck to the front of the next one.
     *
     * Deliberately a written-down list rather than anything cleverer. It is a closed class in the
     * language and a short one — every one of them is a word that leans on what comes after it —
     * and the alternative, some rule about how common or how short the first half is, lets
     * `filename`, `backend` and `dropbox` straight through, since `file`, `back` and `drop` are
     * every bit as common and short as `each` and `thank`.
     *
     * Left off on purpose: `you`, because `youtube`; `go`, because `google` would become `go ogle`;
     * `i`, because `iphone` — and because `iam` and `ineed` are repaired to `aim` and `indeed` by
     * an ordinary edit anyway; `on` and `one`, whose glued forms are rare and whose compounds
     * (`onboarding`, `onetime`) are not; `off`, `over` and `under`, because `offset`, `overflow`
     * and `underscore` are words somebody types all day. A word missing from this list costs one
     * correction that does not happen, which is the cheap direction to be wrong in.
     */
    val GLUED: Set<String> = setOf(
        "a",
        "all", "along", "an", "and", "any", "as", "at", "by",
        "each", "even", "ever", "every", "for", "from", "in", "into",
        "no", "not", "of", "or", "out",
        "so", "some", "thank", "that", "the", "this", "to", "too", "up", "we", "with"
    )

    /** Shorter than this and there are not two words in it. */
    const val MIN_SPLIT_LENGTH = 4

    /** The letters a word can be built from, and the apostrophe, which behaves like one here. */
    private val ALPHABET: CharArray = (('a'..'z') + '\'').toCharArray()

    /**
     * What [typed] should become, or null to leave it alone — which is the answer most of the time.
     *
     * [startsSentence] is whether the word begins a sentence, and is only consulted to decide
     * whether a leading capital means "new sentence" or "somebody's name". The service works it out
     * with [KeyboardMachine.startsASentence] on the text in front of the word.
     */
    fun of(
        typed: String,
        startsSentence: Boolean,
        vocabulary: Vocabulary,
        neighbours: KeyNeighbours = KeyNeighbours.QWERTY
    ): Correction? {
        val casing = Casing.of(typed)
        if (casing == Casing.MIXED) return null
        if (casing == Casing.TITLE && !startsSentence) return null

        val word = Vocabulary.normalize(typed)
        if (word.length < MIN_LENGTH || word.length > MAX_LENGTH) return null
        if (!word.all { it.isLetter() || it == '\'' }) return null
        if (word.none { it.isLetter() }) return null
        if (vocabulary.knows(word)) return null
        // `dogs'` is a plural possessive and is spelt correctly; `dog's` is one cheap edit away and
        // is a different thing to have said.
        if (word.endsWith("'") && vocabulary.knows(word.trimEnd('\''))) return null

        val best = (repairs(word, neighbours, vocabulary) + splits(word, vocabulary))
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.weight }
                    .thenBy { it.word }
            )
            .firstOrNull()
            ?: return null

        val accept = if (casing == Casing.LOWER) ACCEPT else CAPITALISED_ACCEPT
        if (best.score < accept) return null
        return Correction(from = typed, to = casing.applyTo(best.word))
    }

    /** Every one-edit repair of [word] that is a word, scored. */
    private fun repairs(word: String, neighbours: KeyNeighbours, vocabulary: Vocabulary): Sequence<Scored> =
        candidates(word, neighbours).asSequence().mapNotNull { (candidate, penalty) ->
            val weight = vocabulary.weight(candidate)
            if (weight == 0) null else Scored(candidate, weight - penalty - surcharge(word, candidate), weight)
        }

    /**
     * Every way of reading [word] as one of the [GLUED] words stuck to an ordinary one.
     *
     * The pair is worth what its second half is worth: the first half is off a list of words that
     * are common by construction, so it is the other one that decides whether this is a phrase
     * anybody would have written. The long tail is excluded here as it is everywhere else, and a
     * one-letter second half is excluded outright — `a` and `i` lean forwards, so `banda` is not
     * `band a`.
     */
    private fun splits(word: String, vocabulary: Vocabulary): Sequence<Scored> {
        if (word.length < MIN_SPLIT_LENGTH) return emptySequence()
        return (1 until word.length).asSequence().mapNotNull { at ->
            val left = word.substring(0, at)
            if (left !in GLUED) return@mapNotNull null
            val right = word.substring(at)
            if (right.length < 2) return@mapNotNull null
            val weight = vocabulary.weight(right)
            if (weight < Vocabulary.ORDINARY) return@mapNotNull null
            Scored("$left $right", weight - SPLIT, weight)
        }
    }

    /**
     * The extra cost of rewriting the first letter of a word — except when it was not rewritten so
     * much as swapped with the second, which is [FIRST_LETTER]'s one exemption and the reason `hte`
     * still becomes `the`.
     */
    private fun surcharge(word: String, candidate: String): Int = when {
        candidate[0] == word[0] -> 0
        word.length >= 2 && candidate.length >= 2 && candidate[0] == word[1] && candidate[1] == word[0] -> 0
        else -> FIRST_LETTER
    }

    /**
     * Every word one edit from [word], with what that edit costs.
     *
     * The four edits are the classic set — a letter dropped, two swapped, one wrong, one missing —
     * and the only thing here that is not textbook is that the cost depends on *which* letter: a
     * doubled letter and a neighbouring key are ordinary slips, a letter from the far side of the
     * keyboard is somebody spelling a word wrong, and those deserve different amounts of suspicion.
     *
     * A candidate reachable two ways keeps the cheaper reading of itself.
     */
    private fun candidates(word: String, neighbours: KeyNeighbours): Map<String, Int> {
        val found = HashMap<String, Int>()

        fun offer(candidate: String, penalty: Int) {
            if (candidate.length < MIN_LENGTH || candidate == word) return
            val existing = found[candidate]
            if (existing == null || penalty < existing) found[candidate] = penalty
        }

        // One letter too many: doubled, hit on the way past its neighbour, or unexplained.
        for (index in word.indices) {
            val before = word.getOrNull(index - 1)
            val after = word.getOrNull(index + 1)
            val letter = word[index]
            val penalty = when {
                letter == before || letter == after -> DOUBLED
                (before != null && neighbours.adjacent(letter, before)) ||
                    (after != null && neighbours.adjacent(letter, after)) -> SLIP
                else -> EXTRA
            }
            offer(word.removeRange(index, index + 1), penalty)
        }

        // Two letters the wrong way round.
        for (index in 0 until word.lastIndex) {
            if (word[index] == word[index + 1]) continue
            val swapped = StringBuilder(word)
            swapped[index] = word[index + 1]
            swapped[index + 1] = word[index]
            offer(swapped.toString(), TRANSPOSED)
        }

        // The wrong letter.
        for (index in word.indices) {
            for (letter in ALPHABET) {
                if (letter == word[index]) continue
                val penalty = if (neighbours.adjacent(word[index], letter)) NEAR else FAR
                offer(word.replaceRange(index, index + 1, letter.toString()), penalty)
            }
        }

        // A letter left out.
        for (index in 0..word.length) {
            for (letter in ALPHABET) {
                val penalty = when {
                    letter == '\'' -> APOSTROPHE
                    (index > 0 && word[index - 1] == letter) ||
                        (index < word.length && word[index] == letter) -> DOUBLED
                    else -> MISSING
                }
                offer(word.substring(0, index) + letter + word.substring(index), penalty)
            }
        }

        return found
    }

    private class Scored(val word: String, val score: Int, val weight: Int)

    /**
     * How a word was capitalised, so the correction can come back wearing the same clothes.
     *
     * `teh` → `the`, `Teh` → `The`, `TEH` → `THE`. Anything else — a capital in the middle — is
     * somebody being deliberate and is not corrected at all.
     */
    enum class Casing {
        LOWER,
        TITLE,
        UPPER,
        MIXED;

        fun applyTo(word: String): String = when (this) {
            LOWER, MIXED -> word
            UPPER -> word.uppercase()
            TITLE -> word.replaceFirstChar { it.uppercase() }
        }

        companion object {
            fun of(word: String): Casing {
                val letters = word.filter { it.isLetter() }
                if (letters.isEmpty()) return MIXED
                if (letters.none { it.isUpperCase() }) return LOWER
                if (letters.all { it.isUpperCase() }) return if (letters.length > 1) UPPER else TITLE
                val rest = word.substring(1)
                return if (word[0].isUpperCase() && rest.none { it.isUpperCase() }) TITLE else MIXED
            }
        }
    }
}
