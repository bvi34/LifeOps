package com.advisor.app.logic

/**
 * A task the user asked the Advisor to create in one of the hosted apps. [target] is whatever they
 * named it against in their own words ("life ops", "the beacon project") — resolving that to a real
 * project/aspect/category is the writing app's job, not the parser's.
 */
data class TaskCommand(
    val title: String,
    val app: SourceApp = SourceApp.LIFEOPS,
    val target: String? = null,
    /**
     * The title with [target] still on the end, when it was trimmed off ("Publish blog post to the
     * website"). Only "… to <somewhere>" that names a real project/aspect/category is filing; when it
     * turns out to name nothing, the writer restores this and keeps the title the user typed.
     */
    val titleWithTarget: String? = null,
    val priority: String? = null
)

/**
 * Recognises an **explicit request to create a task** ("add a task to life ops called X") so it is
 * carried out against the owning app instead of being answered by the language model.
 *
 * This exists because a model asked to "add a Todo task to Life Ops" will happily *say* it did —
 * it has no way to write to another app, so the only trace left was a `@memorize` line in long-term
 * memory and a confident reply about a task that never existed. Creation is a command, not a
 * question: it is parsed here, performed through the app's own connection layer, and confirmed from
 * the real outcome.
 *
 * Deliberately narrow. A turn must name the act (add/create/make/…) *and* the noun (task/to-do), and
 * a question ("what tasks did I add?") is never a command — except for the polite request form
 * ("can you add a task…?"), which is one. When the command is clear but the title isn't,
 * [Result.NeedsTitle] asks for it rather than inventing one. Pure and JVM-testable; the repository
 * runs it before every other write path.
 */
object TaskIntent {

    sealed interface Result {
        /** A complete command, ready to perform. */
        data class Create(val command: TaskCommand) : Result

        /** Clearly a "make me a task" turn, but with no title to give it. */
        data object NeedsTitle : Result
    }

    private const val CREATE_VERB = "(?:add|create|make|log|open|start|set\\s+up|put)"
    private const val TASK_NOUN = "(?:task|to-?do(?:\\s+item)?)"

    // "can you …?" / "please …" — a request, so a trailing question mark doesn't make it a question.
    private val REQUEST_LEAD = Regex(
        """(?i)^(?:hey,?\s+|ok,?\s+|so,?\s+)*(?:please\s+|pls\s+|can\s+(?:you|we)\s+|could\s+(?:you|we)\s+|""" +
            """would\s+you\s+|will\s+you\s+|i(?:'d|\s+would)\s+like\s+(?:you\s+)?to\s+|i\s+(?:want|need)\s+(?:you\s+)?to\s+)"""
    )

    // "what task did you add", "did you add a task", "how do I add a task" — asking about, not asking for.
    private val QUESTION_LEAD = Regex(
        """(?i)^(?:what|which|when|where|who|whose|why|how|did|do|does|is|are|was|were|have|has|had|should)\b"""
    )

    // The command itself: a creating verb, then the task noun, allowing "a new Todo" style filler between.
    private val COMMAND = Regex(
        """(?i)\b$CREATE_VERB\b[ \t]*(?:\w+[ \t]+){0,3}?$TASK_NOUN\b"""
    )

    // "titled X" / "called X" / "named X" / "task: X" — an outright title.
    private val TITLED = Regex(
        """(?i)\b(?:titled|called|named|entitled|title[ \t]*[:=])[ \t]*(.+)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val QUOTED = Regex("""["“”'‘’]([^"“”'‘’]{2,})["“”'‘’]""")
    private val NOUN_COLON = Regex("""(?i)\b$TASK_NOUN\b[ \t]*[:\-–—][ \t]*(.+)""", RegexOption.DOT_MATCHES_ALL)
    // "add a task buy flour" — the title trails the noun directly, with no naming word at all.
    private val NOUN_TRAILING = Regex(
        """(?i)\b$CREATE_VERB\b[ \t]*(?:\w+[ \t]+){0,3}?$TASK_NOUN\b[ \t]+""" +
            """(?!(?:to|in|into|under|for|on|onto|about|that|it|this|there)\b)(.+)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // "to life ops", "in the beacon project" — where the user wants it filed.
    private val TARGET = Regex(
        """(?i)\b(?:to|in|into|under|onto|for)[ \t]+(?:my[ \t]+|the[ \t]+|our[ \t]+)?""" +
            """([A-Za-z0-9][\w'&. -]{1,40}?)(?:[ \t]+(?:project|list|board|aspect|category|app))?""" +
            """(?=[ \t]*(?:[,.;:?!]|$|\b(?:and|please|titled|called|named|entitled|maybe|so|then)\b))"""
    )

    // Targets that name a time window, this assistant's own stores, or the generic task list itself.
    private val NOT_A_TARGET = Regex(
        """(?i)^(?:(?:this|the|next|current|coming|upcoming|last)[ \t]+)?""" +
            """(?:week|weekend|month|day|today|tomorrow|morning|evening|night|memory|long[- ]?term[ \t]+memory|""" +
            """notes?|tasks?|to-?dos?|list|schedule|plan|now|me|it|there)$"""
    )

    // Trailing "for the current week" / "this week" — scope, not part of the title.
    private val TRAILING_WHEN = Regex(
        """(?i)[ \t,]*\b(?:for|in|on|to|during)?[ \t]*(?:the[ \t]+|my[ \t]+)?""" +
            """(?:current|this|next|coming|upcoming)[ \t]+(?:week|weekend|month)[ \t]*[.!]*$"""
    )
    private val TRAILING_FILLER = Regex(
        """(?i)[ \t]+(?:now|please|thanks|thank[ \t]+you|for[ \t]+me|if[ \t]+you[ \t]+can|ok)[ \t]*[.!]*$"""
    )
    private val LEADING_FILLER = Regex(
        """(?i)^(?:maybe|perhaps|possibly|just|something[ \t]+like|it|something)[ \t]+"""
    )
    private val PRIORITY = Regex("""(?i)\b(?:(high|medium|low)[ \t]+priority|(urgent|asap))\b""")

    /** Interpret [question]; null when it isn't a task-creation command at all. */
    fun detect(question: String): Result? {
        val q = question.trim()
        if (q.isEmpty()) return null

        val polite = REQUEST_LEAD.containsMatchIn(q)
        if (!polite && QUESTION_LEAD.containsMatchIn(q)) return null
        if (!polite && q.endsWith("?")) return null
        val command = COMMAND.find(q) ?: return null

        val target = target(q, from = command.range.last)
        val title = title(q, target) ?: return Result.NeedsTitle
        if (title.text.isBlank()) return Result.NeedsTitle
        return Result.Create(
            TaskCommand(
                title = title.text,
                target = target,
                titleWithTarget = title.withTarget,
                priority = priority(q)
            )
        )
    }

    /** Where the user wants it filed, in their words — the first plausible one after the noun. */
    private fun target(q: String, from: Int): String? {
        val tail = q.substring(from.coerceAtMost(q.length))
        return TARGET.findAll(tail)
            .map { it.groupValues[1].trim().trimEnd(',', '.', ';') }
            .firstOrNull { it.isNotBlank() && !NOT_A_TARGET.containsMatchIn(it) }
    }

    /**
     * The title, in order of how explicitly the user gave it: quoted, then a naming word
     * ("titled X"), then a colon after the noun, then whatever trails the noun.
     */
    private fun title(q: String, target: String?): Title? {
        val raw = QUOTED.find(q)?.groupValues?.get(1)
            ?: TITLED.find(q)?.groupValues?.get(1)
            ?: NOUN_COLON.find(q)?.groupValues?.get(1)
            ?: NOUN_TRAILING.find(q)?.groupValues?.get(1)
            ?: return null
        return clean(raw, target)
    }

    /** A cleaned title, plus the longer form to fall back on if [TaskCommand.target] names nothing. */
    private data class Title(val text: String, val withTarget: String?)

    private fun priority(q: String): String? = PRIORITY.find(q)?.let { m ->
        m.groupValues[1].lowercase().ifBlank { "high" }
    }

    /**
     * Trim the scope the user tacked onto the title — "… to the current week", "… in life ops" — plus
     * politeness and stray punctuation, so the task is named what they meant it to be named.
     */
    private fun clean(raw: String, target: String?): Title {
        var text = raw.trim().substringBefore('\n').trim()
        // Everything from the first sentence break on is a separate clause, not the title.
        text = text.split(Regex("""[?!;]"""))[0].trim()
        val withTarget = if (target == null) null else {
            val trailingTarget = Regex(
                """(?i)[ \t,]*\b(?:to|in|into|under|onto|for)[ \t]+(?:my[ \t]+|the[ \t]+|our[ \t]+)?""" +
                    Regex.escape(target) + """(?:[ \t]+(?:project|list|board|aspect|category|app))?[ \t]*[.!]*$"""
            )
            val trimmed = text.replace(trailingTarget, "")
            (text.takeIf { trimmed != text })?.also { text = trimmed }
        }
        var previous: String
        do {
            previous = text
            text = text.replace(TRAILING_WHEN, "")
                .replace(TRAILING_FILLER, "")
                .replace(LEADING_FILLER, "")
                .trim()
                .trimEnd(',', '.', '!')
                .trim()
        } while (text != previous)
        return Title(text, withTarget?.let { tidy(it) })
    }

    /** The same politeness/scope trim as [clean], for the fallback title (which keeps its target). */
    private fun tidy(raw: String): String {
        var text = raw
        var previous: String
        do {
            previous = text
            text = text.replace(TRAILING_WHEN, "")
                .replace(TRAILING_FILLER, "")
                .replace(LEADING_FILLER, "")
                .trim()
                .trimEnd(',', '.', '!')
                .trim()
        } while (text != previous)
        return text
    }
}
