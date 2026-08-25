package com.advisor.app.logic

/**
 * Recognises **conversational, non-data turns** — greetings, thanks, farewells, acknowledgements, and
 * "what can you do / who are you" meta-questions — and composes a warm, immediate reply, so chatting
 * with the Advisor feels like talking to an assistant rather than querying a database.
 *
 * It is the social counterpart to [WriteIntent]: where WriteIntent short-circuits explicit *write*
 * commands before the grounding gate, SmallTalk short-circuits pure *social* turns that have no data
 * to ground against — turns that would otherwise dead-end in the C3A gate's "I don't have anything…"
 * or the placeholder engine's "I couldn't find anything…". Grounded questions are left alone:
 * detection only fires when the **whole** message is social/meta, so "hi, what's due today?" falls
 * straight through to normal Q&A.
 *
 * Replies are personalised from what the pipeline already holds — the user's name (from identity), the
 * assistant's own name (from a persona profile), and which apps are currently enabled — which is what
 * ties the chit-chat back to "recall and notes from the other apps". Pure and JVM-testable, like the
 * rest of `logic/`.
 */
object SmallTalk {

    /** The flavour of social/meta turn recognised — used by callers/tests; the reply text follows. */
    enum class Kind { GREETING, THANKS, FAREWELL, ACKNOWLEDGEMENT, CAPABILITY, IDENTITY }

    /** A recognised social/meta turn and the ready-to-show reply for it. */
    data class SmallTalkReply(val kind: Kind, val text: String)

    /**
     * The bits of already-loaded context a reply is personalised from. Everything is optional; a bare
     * [Context] still yields a sensible (if generic) reply.
     */
    data class Context(
        val userName: String = "",
        val assistantName: String = DEFAULT_NAME,
        val grantedApps: Set<SourceApp> = emptySet()
    )

    const val DEFAULT_NAME = "Advisor"

    /**
     * Interpret [question] as a social/meta turn, or return null when it is a real (data) question —
     * in which case the caller runs the normal retrieval pipeline. Detection is deliberately
     * conservative: a message only counts as small talk when every one of its words is a social word
     * or harmless filler (or it matches a known meta phrase), so any turn carrying real content falls
     * through untouched.
     */
    fun detect(question: String, ctx: Context = Context()): SmallTalkReply? {
        val raw = question.trim()
        if (raw.isEmpty()) return null

        val norm = QueryUnderstanding.normalize(raw)
        val cleaned = norm.replace(NON_WORD, " ").trim().replace(WHITESPACE, " ")
        if (cleaned.isEmpty()) return null

        // Meta questions ("what can you do", "who are you") — matched against the message with any
        // leading/trailing social filler stripped, so "hey, what can you do?" still lands.
        val core = stripEdges(cleaned)
        if (CAPABILITY.any { it.matches(core) }) return SmallTalkReply(Kind.CAPABILITY, capabilityText(ctx))
        if (IDENTITY.any { it.matches(core) }) return SmallTalkReply(Kind.IDENTITY, identityText(ctx))

        // Idiomatic multi-word social phrases ("good morning", "see you later", "sounds good").
        PHRASES[cleaned]?.let { return reply(it, ctx) }

        // Otherwise it's small talk only if every token is a social word or allowed filler.
        val kinds = HashSet<Kind>()
        for (token in cleaned.split(' ')) {
            if (token.isBlank()) continue
            when {
                token in GREETING_WORDS -> kinds += Kind.GREETING
                token in THANKS_WORDS -> kinds += Kind.THANKS
                token in FAREWELL_WORDS -> kinds += Kind.FAREWELL
                token in ACK_WORDS -> kinds += Kind.ACKNOWLEDGEMENT
                token in FILLER_WORDS -> {} // carries no meaning; ignore
                else -> return null // a content word — this is a real question, not small talk
            }
        }
        val kind = SOCIAL_PRIORITY.firstOrNull { it in kinds } ?: return null
        return reply(kind, ctx)
    }

    private fun reply(kind: Kind, ctx: Context): SmallTalkReply = SmallTalkReply(
        kind,
        when (kind) {
            Kind.GREETING -> greetingText(ctx)
            Kind.THANKS -> thanksText(ctx)
            Kind.FAREWELL -> farewellText(ctx)
            Kind.ACKNOWLEDGEMENT -> ackText()
            Kind.CAPABILITY -> capabilityText(ctx)
            Kind.IDENTITY -> identityText(ctx)
        }
    )

    /**
     * The assistant's name to speak as, taken from a standing **persona** profile if the user has
     * named it ("add to LLM persona that you are called Ava"), else the default [DEFAULT_NAME]. The
     * most recent naming wins. Kept here so the derivation is pure and tested alongside the replies.
     */
    fun assistantNameFrom(profiles: List<Profile>): String {
        val persona = profiles.firstOrNull { it.kind == ProfileKind.PERSONA }
            ?: profiles.firstOrNull { it.key.contains("persona", ignoreCase = true) }
            ?: return DEFAULT_NAME
        val lines = buildList {
            add(persona.summary)
            persona.entries.forEach { add(it.text) }
        }
        var found: String? = null
        for (line in lines) NAME_PATTERN.findAll(line).forEach { found = it.groupValues[1] }
        return found?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    }

    // --- reply text --------------------------------------------------------------------------------

    private fun greetingText(ctx: Context): String {
        val name = addressed(ctx)
        val apps = grantedList(ctx)
        val offer = if (apps != null) "Ask me about your $apps, or tell me something to remember."
        else "Grant me an app in Permissions and I can dig through your tasks, books, or pantry — " +
            "or just tell me something to remember."
        return "Hey$name! I'm ${ctx.assistantName}. $offer What's on your mind?"
    }

    private fun thanksText(ctx: Context): String =
        "Anytime${addressed(ctx)} — glad I could help. Anything else you'd like to look into?"

    private fun farewellText(ctx: Context): String =
        "Take care${addressed(ctx)}! I'll be right here when you need me."

    private fun ackText(): String = "Got it. What would you like to do next?"

    private fun identityText(ctx: Context): String =
        "I'm ${ctx.assistantName}, your private on-device assistant. I run entirely on this device — " +
            "no cloud, nothing leaves it — and I answer from your own LifeOps, Citation, and Logistics " +
            "data, plus anything you ask me to remember. What can I help you with?"

    private fun capabilityText(ctx: Context): String = buildString {
        append("I'm ${ctx.assistantName} — a private assistant that runs entirely on this device, so ")
        append("nothing you tell me leaves it. ")
        val apps = SourceApp.entries.filter { it in ctx.grantedApps }
        if (apps.isEmpty()) {
            append("You haven't switched on any apps yet. Open Permissions and grant me read access to ")
            append("LifeOps, Citation, or Logistics, and I'll answer from your own tasks, reading notes, ")
            append("and pantry.")
        } else {
            append("Right now I can see your ${humanJoin(apps.map { it.displayName })} data, so you can ")
            append("ask me things like:")
            for (app in apps) append("\n").append(EXAMPLES.getValue(app))
        }
        append("\n\nI also keep long-term memory and standing profiles, so tell me things like ")
        append("\"remember that …\" and I'll hold onto them — and I'll bring them (and our recent chat) ")
        append("back up whenever they're relevant.")
    }

    /** ", Name" when the user's name is known, else "" — so replies read naturally either way. */
    private fun addressed(ctx: Context): String =
        ctx.userName.trim().let { if (it.isNotBlank()) ", $it" else "" }

    /** The enabled apps as a human list ("LifeOps and Citation"), or null when none are granted. */
    private fun grantedList(ctx: Context): String? {
        val apps = SourceApp.entries.filter { it in ctx.grantedApps }.map { it.displayName }
        return if (apps.isEmpty()) null else humanJoin(apps)
    }

    private fun humanJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    // --- vocabulary --------------------------------------------------------------------------------

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")

    private val NAME_PATTERN =
        Regex("(?i)\\b(?:called|named|name is|call you)\\s+([A-Za-z][\\w'-]*)")

    // When several social kinds appear in one turn ("thanks, bye"), the closing one wins.
    private val SOCIAL_PRIORITY =
        listOf(Kind.FAREWELL, Kind.THANKS, Kind.GREETING, Kind.ACKNOWLEDGEMENT)

    private val GREETING_WORDS = setOf(
        "hi", "hii", "hiii", "hello", "helloo", "hey", "heya", "hiya", "yo", "sup", "howdy",
        "greetings", "hola", "wassup", "morning", "afternoon", "evening", "gday"
    )
    private val THANKS_WORDS = setOf(
        "thanks", "thank", "thankyou", "thx", "thnx", "ty", "tysm", "cheers", "ta",
        "appreciate", "appreciated"
    )
    private val FAREWELL_WORDS = setOf(
        "bye", "byebye", "goodbye", "cya", "ttyl", "adios", "farewell", "later", "laters",
        "goodnight", "gnight", "peace"
    )
    private val ACK_WORDS = setOf(
        "ok", "okay", "okey", "k", "kk", "kay", "cool", "coolio", "nice", "neat", "great",
        "awesome", "gotcha", "gotchu", "perfect", "sweet", "alright", "alrighty", "fab",
        "lovely", "wonderful", "excellent", "brilliant", "roger", "understood"
    )
    // Non-content connectors/politeness that may sit alongside a social word without changing that
    // the turn is small talk. Deliberately excludes anything that could carry a data question.
    private val FILLER_WORDS = setOf(
        "you", "u", "there", "advisor", "again", "so", "well", "very", "much", "good", "really",
        "then", "buddy", "friend", "mate", "pal", "dear", "please", "pls", "a", "lot", "for",
        "me", "to", "the", "my", "that", "this", "it", "and", "im", "am", "all", "now", "bunch"
    )

    // Idiomatic multi-word phrases (apostrophes already stripped) → their kind.
    private val PHRASES: Map<String, Kind> = mapOf(
        "good morning" to Kind.GREETING,
        "good afternoon" to Kind.GREETING,
        "good evening" to Kind.GREETING,
        "how are you" to Kind.GREETING,
        "how are you doing" to Kind.GREETING,
        "how is it going" to Kind.GREETING,
        "how are things" to Kind.GREETING,
        "hope you are well" to Kind.GREETING,
        "long time no see" to Kind.GREETING,
        "good night" to Kind.FAREWELL,
        "see you" to Kind.FAREWELL,
        "see you later" to Kind.FAREWELL,
        "see ya" to Kind.FAREWELL,
        "talk to you later" to Kind.FAREWELL,
        "take care" to Kind.FAREWELL,
        "catch you later" to Kind.FAREWELL,
        "have a good one" to Kind.FAREWELL,
        "good day" to Kind.FAREWELL,
        "got it" to Kind.ACKNOWLEDGEMENT,
        "sounds good" to Kind.ACKNOWLEDGEMENT,
        "makes sense" to Kind.ACKNOWLEDGEMENT,
        "will do" to Kind.ACKNOWLEDGEMENT,
        "good to know" to Kind.ACKNOWLEDGEMENT,
        "that helps" to Kind.ACKNOWLEDGEMENT,
        "thats helpful" to Kind.ACKNOWLEDGEMENT,
        "fair enough" to Kind.ACKNOWLEDGEMENT,
        "no worries" to Kind.ACKNOWLEDGEMENT,
        "all good" to Kind.ACKNOWLEDGEMENT,
        "good stuff" to Kind.ACKNOWLEDGEMENT,
        "thank you" to Kind.THANKS,
        "thanks a lot" to Kind.THANKS,
        "thank you so much" to Kind.THANKS,
        "thanks so much" to Kind.THANKS,
        "many thanks" to Kind.THANKS,
        "much appreciated" to Kind.THANKS,
        "i appreciate it" to Kind.THANKS,
        "thanks a bunch" to Kind.THANKS
    )

    // Whole-message (after edge-stripping) patterns that mean "tell me what you can do".
    private val CAPABILITY: List<Regex> = listOf(
        "what can (?:you|u|advisor|this app|this) (?:do|help)(?: me)?(?: with)?",
        "what (?:do|can) you do(?: for me)?",
        "what are you (?:capable of|able to do)",
        "what can i (?:ask|do)(?: you)?(?: about| here)?",
        "how (?:do|does) (?:you|this|it) work",
        "how can (?:you|i) (?:help|get started)(?: me)?(?: with .*)?",
        "what kind of (?:things|stuff) can you (?:do|help with)",
        "what all can you do",
        "what else can you do",
        "show me what you can do",
        "help",
        "i need help",
        "what do you know",
        "what can you tell me"
    ).map { Regex(it) }

    // Whole-message patterns that mean "who/what are you" (about the assistant, not the user).
    private val IDENTITY: List<Regex> = listOf(
        "who (?:are|r) (?:you|u)",
        "what are you",
        "what are you exactly",
        "what is your name",
        "what should i call you",
        "do you have a name",
        "are you (?:an )?ai",
        "are you a (?:bot|robot|human|person|real person)",
        "are you (?:human|real|sentient|conscious)",
        "what (?:model|llm) (?:are you|is this|do you use|are you running)",
        "who (?:made|built|created|trained) you",
        "tell me about yourself",
        "introduce yourself"
    ).map { Regex(it) }

    private val EXAMPLES: Map<SourceApp, String> = mapOf(
        SourceApp.LIFEOPS to "• \"What should I focus on this week?\" or \"What tasks are due?\"",
        SourceApp.CITATION to "• \"What am I reading?\" or \"Show me my notes on a book.\"",
        SourceApp.LOGISTICS to "• \"What's running low in the pantry?\" or \"What can I cook tonight?\"",
        SourceApp.HEALTH to "• \"When did she last have paracetamol?\" or \"How long was his fever?\"",
        SourceApp.PEOPLE to "• \"Whose birthday is next?\" or \"What's my sister's email?\""
    )

    // Social openers/closers to peel off before meta-matching, so "hey, what can you do?" still lands.
    private val EDGE_WORDS = setOf(
        "hi", "hey", "hello", "yo", "ok", "okay", "so", "um", "uh", "hmm", "well", "please",
        "pls", "advisor", "hiya", "heya"
    )
    private val TRAILING_EDGE = setOf("please", "pls", "thanks", "now", "advisor")

    /** Strip leading/trailing social filler words from an already-cleaned message. */
    private fun stripEdges(cleaned: String): String {
        val tokens = cleaned.split(' ').filter { it.isNotBlank() }.toMutableList()
        while (tokens.isNotEmpty() && tokens.first() in EDGE_WORDS) tokens.removeAt(0)
        while (tokens.isNotEmpty() && tokens.last() in TRAILING_EDGE) tokens.removeAt(tokens.size - 1)
        return tokens.joinToString(" ")
    }
}
