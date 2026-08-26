package com.health.app.logic

/**
 * Talking to an insurer's **provider directory** — the published list of who is in its networks.
 *
 * ### Why this can be asked at all
 *
 * Since the CMS Interoperability and Patient Access rule, payers publish their provider directory as
 * a *public, unauthenticated* FHIR R4 API — the Da Vinci **PDEX Plan-Net** profile. `Practitioner`,
 * `PractitionerRole`, `Organization`, `Location`, `InsurancePlan`; no key, no sign-in, no member
 * number, by design. It is a directory in the telephone-book sense: the insurer publishes it so that
 * anybody can look a doctor up before booking.
 *
 * That is the only reason this file exists, and it is what keeps it on the right side of Health's
 * promise. The question Health asks a directory is *"is Dr Okafor in this network?"* — a question
 * about a **practitioner**, of the kind the directory was published to answer for anyone who asks.
 * It is never *"is Dr Okafor my daughter's doctor?"*, and there is no parameter on any function here
 * that could carry a profile, a member number or a diagnosis. See `data/net/ProviderDirectoryClient`
 * for the wire, and the note in `AndroidManifest.xml` for the whole argument.
 *
 * ### Why so much of this is guesswork about URLs
 *
 * There is no national registry of these endpoints. Each payer publishes its own base URL, usually
 * on a developer page, sometimes on the back of the card, and Health has no way to know it in
 * advance. **It does not ship a table of guessed URLs** — a wrong endpoint quietly answering "not
 * listed" for every doctor in the house is precisely the kind of confident fiction this app refuses.
 *
 * So the user pastes what the plan gave them and Health does the only honest thing: takes that at
 * face value, tries the handful of paths a FHIR server conventionally lives at underneath it
 * ([candidateBases]), asks each for its `CapabilityStatement`, and reports exactly what came back —
 * including "there is nothing here", which is a perfectly ordinary answer.
 *
 * Framework-free and unit-tested. Nothing here opens a connection; it decides what to ask for and
 * what an answer means.
 */

/** What happened when Health asked a URL whether it was a provider directory. */
enum class DirectoryOutcome(val key: String, val label: String) {
    /** No directory URL is recorded for the plan. Nothing was asked, and nothing is claimed. */
    NOT_CONFIGURED("not_configured", "No directory recorded"),

    /** A FHIR server answered and it can be searched for practitioners. */
    REACHABLE("reachable", "Directory reachable"),

    /** Something answered, but it isn't a FHIR provider directory — usually a marketing page. */
    NOT_A_DIRECTORY("not_a_directory", "Not a provider directory"),

    /** The server is there and says there is nothing at this path. Very common while hunting a base URL. */
    NOT_PUBLISHED("not_published", "Nothing published here"),

    /**
     * The endpoint wants credentials. Plan-Net is meant to be public, so this usually means the URL
     * points at the *member* portal rather than the directory — a distinction worth telling the user
     * about rather than reporting as a failure.
     */
    NEEDS_SIGN_IN("needs_sign_in", "Needs a sign-in"),

    /** Asked too often. Not a verdict about anything — just "come back later". */
    RATE_LIMITED("rate_limited", "Directory is rate-limiting"),

    /** Nothing answered: no network, DNS failure, timeout, or the payer's server is down. */
    UNREACHABLE("unreachable", "Couldn't reach the directory");

    companion object {
        fun fromKey(key: String?): DirectoryOutcome = entries.firstOrNull { it.key == key } ?: UNREACHABLE
    }
}

/**
 * What Health found at the end of a probe: which URL answered, what it said it was, and which
 * resource types it will let you search.
 */
data class DirectoryProbe(
    val outcome: DirectoryOutcome,
    /** The candidate that answered — worth keeping, because it is rarely the URL that was pasted. */
    val baseUrl: String? = null,
    val softwareName: String? = null,
    val fhirVersion: String? = null,
    val resources: List<String> = emptyList(),
    val detail: String? = null
) {
    /**
     * Whether a practitioner can actually be looked up here.
     *
     * A server that answers a `CapabilityStatement` but exposes neither `Practitioner` nor
     * `PractitionerRole` is a FHIR endpoint for something else entirely — a claims API, a formulary.
     * Health reports it as reached and unsearchable rather than pretending it will answer.
     */
    val searchable: Boolean
        get() = outcome == DirectoryOutcome.REACHABLE &&
            resources.any { it.equals("Practitioner", true) || it.equals("PractitionerRole", true) }
}

/** One practitioner as a directory describes them. Everything but the id may be missing. */
data class DirectoryPractitioner(
    val id: String,
    val name: String?,
    val npi: String?,
    /** The directory's own `active` flag. Null when it didn't say. */
    val active: Boolean?,
    val specialties: List<String> = emptyList(),
    /** The practices/groups they are listed under. */
    val organizations: List<String> = emptyList(),
    /**
     * The **networks** the directory lists them in, which is the fact that actually matters.
     *
     * A payer's directory covers every network it sells, so "listed in this directory" and "in the
     * network your plan buys" are not the same sentence. Where Plan-Net names the networks, Health
     * keeps the names and shows them, so the reader can see which one they were found in instead of
     * being handed a bare yes.
     */
    val networks: List<String> = emptyList()
) {
    val descriptor: String?
        get() = (specialties + organizations).distinct().joinToString(" · ").ifBlank { null }
}

/** How sure Health is that a directory entry is the doctor somebody wrote down. */
enum class MatchStrength {
    /** The NPIs agree. A national provider identifier is unique to one clinician; this is certainty. */
    NPI,
    /** The names agree well enough that a person would say it is them. */
    NAME,
    /** A surname and an initial. Enough to show, never enough to conclude. */
    WEAK,
    NONE
}

object ProviderDirectory {

    /** The FHIR resources a Plan-Net directory has to expose for any of this to work. */
    val SEARCH_RESOURCES = listOf("Practitioner", "PractitionerRole")

    /** The system every US directory identifies a clinician's NPI under. */
    const val NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi"

    /**
     * The paths a FHIR base conventionally sits at underneath whatever the user pasted, in the order
     * worth trying.
     *
     * Kept deliberately short. Each candidate is a real request to somebody else's server, and a
     * probe that fires fifteen of them to be thorough is a probe that looks like a scan; five is
     * enough to find the endpoint when the user pasted the developer page instead of the API root,
     * and is honest about giving up when it isn't there.
     *
     * A URL that already names FHIR is taken at its word and tried alone — the user has clearly been
     * given the real base, and guessing extra paths under it would only generate noise.
     */
    fun candidateBases(raw: String?): List<String> {
        val base = normalizeBase(raw) ?: return emptyList()
        if (looksLikeFhirBase(base)) return listOf(base)
        return listOf(
            base,
            "$base/fhir",
            "$base/fhir/R4",
            "$base/api/fhir/r4",
            "$base/plan-net/fhir"
        )
    }

    /**
     * Tidy what somebody pasted into something that can have a path appended to it: a scheme (https,
     * assumed when none was typed), no query, no fragment, no trailing slash, and no `/metadata`
     * suffix — people copy the capability URL as often as the base, and asking for
     * `…/metadata/metadata` fails in a way that looks like the directory is broken.
     */
    fun normalizeBase(raw: String?): String? {
        var text = raw?.trim()?.ifBlank { null } ?: return null
        text = text.substringBefore('#').substringBefore('?').trim()
        if (!text.contains("://")) text = "https://$text"
        text = text.trimEnd('/')
        val lower = text.lowercase()
        if (lower.endsWith("/metadata")) text = text.dropLast("/metadata".length).trimEnd('/')
        // A bare scheme, or a scheme with no host, is not a URL anybody can ask anything of.
        val afterScheme = text.substringAfter("://", "")
        if (afterScheme.isBlank()) return null
        return text
    }

    /** Whether the URL already points at a FHIR service root rather than a page above one. */
    fun looksLikeFhirBase(base: String): Boolean {
        val path = base.substringAfter("://", "").substringAfter('/', "").lowercase()
        if (path.isBlank()) return false
        val segments = path.split('/').filter { it.isNotBlank() }
        return segments.any { it == "fhir" || it == "plan-net" || it == "plannet" }
    }

    fun metadataUrl(base: String): String = "${base.trimEnd('/')}/metadata"

    /**
     * Search a directory for a practitioner **by NPI**, which is the only search that can be
     * definitive: the identifier is unique to one clinician nationally, so a hit is the right person
     * and a miss is a real miss.
     */
    fun npiSearchUrl(base: String, npi: String, count: Int = DEFAULT_COUNT): String =
        "${base.trimEnd('/')}/Practitioner?identifier=${encodeQuery("$NPI_SYSTEM|${npi.trim()}")}&_count=$count"

    /**
     * Search by name, which never is. Names are shared, directories spell them differently, and the
     * result is a list to be judged rather than an answer — see [assess] and the `AMBIGUOUS` verdict
     * in `NetworkStatus`.
     */
    fun nameSearchUrl(base: String, name: String, count: Int = DEFAULT_COUNT): String =
        "${base.trimEnd('/')}/Practitioner?name=${encodeQuery(searchTerm(name))}&_count=$count"

    /**
     * The roles a practitioner holds — which practice, which specialty and, in Plan-Net, which
     * **networks**. Asked as a second request only when the first found somebody, because the roles
     * are what turn "this person exists" into "this person is in a network".
     */
    fun roleSearchUrl(base: String, practitionerId: String, count: Int = DEFAULT_COUNT): String =
        "${base.trimEnd('/')}/PractitionerRole?practitioner=${encodeQuery(practitionerId.trim())}" +
            "&_count=$count"

    /**
     * The part of a name worth sending: the surname.
     *
     * FHIR's `name` search matches any part of any name, so a full "Dr Jane A. Okafor" typically
     * matches nothing at all while "Okafor" returns the handful of entries a human can then be shown.
     * The titles are stripped for the same reason — no directory files anybody under "Dr".
     */
    fun searchTerm(name: String): String {
        val tokens = nameTokens(name)
        return tokens.lastOrNull() ?: name.trim()
    }

    /**
     * A person's name reduced to the words that identify them: lowercase, no punctuation, and with
     * the honorifics and credentials that vary between directories thrown away.
     *
     * The one piece of care here is the letter left behind by a hyphenated credential. "PA-C" and
     * "FNP-BC" survive the punctuation strip as `pa` + `c` and `fnp` + `bc`; dropping the credential
     * and keeping its second half would leave a stray initial that then matches somebody's middle
     * name. So a one- or two-letter token **immediately after a credential** goes with it — while a
     * single letter after an *honorific* stays, because "Dr. J Okafor" is a name with an initial in
     * it and that initial is the only first name the directory may have.
     */
    fun nameTokens(raw: String?): List<String> {
        val text = raw?.lowercase()?.map { if (it.isLetter() || it.isWhitespace()) it else ' ' }?.joinToString("")
            ?: return emptyList()
        val kept = mutableListOf<String>()
        var afterCredential = false
        text.split(' ', '\t', '\n').forEach { token ->
            val word = token.trim()
            when {
                word.isEmpty() -> Unit
                word in CREDENTIALS -> afterCredential = true
                word in HONORIFICS -> Unit
                afterCredential && word.length <= 2 -> Unit
                else -> {
                    afterCredential = false
                    kept += word
                }
            }
        }
        return kept
    }

    /**
     * How well two names agree, comparing the words rather than the order — a directory that files
     * somebody as "Okafor, Jane A" and a household that wrote down "Dr. Jane Okafor" mean the same
     * person, and any comparison that says otherwise is useless in practice.
     *
     * Two shared words is the bar for [MatchStrength.NAME]. One shared word plus an initial that
     * lines up is [MatchStrength.WEAK] — enough to put on screen and ask about, never enough for
     * Health to conclude anything.
     */
    fun compareNames(query: String?, candidate: String?): MatchStrength {
        val q = nameTokens(query).toSet()
        val c = nameTokens(candidate).toSet()
        if (q.isEmpty() || c.isEmpty()) return MatchStrength.NONE
        if (q == c) return MatchStrength.NAME
        val shared = q intersect c
        if (shared.size >= 2) return MatchStrength.NAME
        if (shared.size == 1 && (q.all { it in c } || c.all { it in q })) return MatchStrength.NAME
        if (shared.size == 1 && initialsAgree(q - shared, c - shared)) return MatchStrength.WEAK
        return MatchStrength.NONE
    }

    /** "jane" against "j" — the shortening every directory does to a middle name and some to a first. */
    private fun initialsAgree(left: Set<String>, right: Set<String>): Boolean =
        left.any { l -> right.any { r -> l.first() == r.first() && (l.length == 1 || r.length == 1) } }

    /**
     * How sure Health is that this directory entry is the doctor that was written down.
     *
     * The NPI decides it whenever both sides have one, **in both directions**: matching identifiers
     * are certainty, and conflicting identifiers are certainty the other way. A directory entry whose
     * name reads exactly right but whose NPI is somebody else's is a different clinician with the
     * same name, which is common enough — and treating that as a match is precisely how an app tells
     * a household their doctor is in network when he isn't.
     */
    fun assess(providerName: String?, providerNpi: String?, candidate: DirectoryPractitioner): MatchStrength {
        val wanted = digits(providerNpi)
        val found = digits(candidate.npi)
        if (wanted != null && found != null) {
            return if (wanted == found) MatchStrength.NPI else MatchStrength.NONE
        }
        return compareNames(providerName, candidate.name)
    }

    /**
     * What a directory's answer amounts to: one [CheckOutcome], the entry it rests on, and a
     * sentence saying why.
     *
     * Pure, and separate from the request that produced it, because this is the judgement — the part
     * where a list of strangers with similar names becomes a yes, a no, or an honest "couldn't tell".
     * Getting it wrong in the generous direction is the worst thing this feature could do, so the
     * rules are conservative and written down:
     *
     *  - **Nothing came back** → not listed. The directory answered; it does not have them.
     *  - **The NPIs agree** → listed, whatever the name says. One entry, one clinician, no doubt.
     *  - **Exactly one name matches** → listed.
     *  - **More than one name matches**, or only a surname-and-initial does → *ambiguous*. Never
     *    "probably the first one": the whole reason an NPI field exists on a provider is to settle
     *    this, and quietly picking a match would hide the fact that it needs settling.
     *  - **Entries came back but none of them is them** → not listed. A directory full of Patels
     *    that does not contain *this* Dr Patel has answered the question.
     *  - **The match is flagged inactive** → not listed, and says so. Plan-Net's `active = false` is
     *    how a payer records a clinician who has stopped practising at that listing, and reading it
     *    as a yes is exactly the mistake that sends somebody to a closed office.
     */
    fun judge(
        providerName: String?,
        providerNpi: String?,
        results: List<DirectoryPractitioner>,
        byIdentifier: Boolean
    ): DirectoryVerdict {
        if (results.isEmpty()) {
            return DirectoryVerdict(
                outcome = CheckOutcome.NOT_LISTED,
                matched = null,
                matchCount = 0,
                detail = if (byIdentifier) {
                    "The directory has no entry under that NPI."
                } else {
                    "The directory returned nobody under that name."
                }
            )
        }

        val scored = results.map { it to assess(providerName, providerNpi, it) }
        val byNpi = scored.filter { it.second == MatchStrength.NPI }.map { it.first }
        val byName = scored.filter { it.second == MatchStrength.NAME }.map { it.first }
        val weak = scored.filter { it.second == MatchStrength.WEAK }.map { it.first }

        val confident = byNpi.firstOrNull() ?: byName.singleOrNull()
        if (confident != null) {
            if (confident.active == false) {
                return DirectoryVerdict(
                    outcome = CheckOutcome.NOT_LISTED,
                    matched = confident,
                    matchCount = 1,
                    detail = "The directory has them, but marks the listing as no longer active."
                )
            }
            return DirectoryVerdict(
                outcome = CheckOutcome.LISTED,
                matched = confident,
                matchCount = 1,
                detail = confident.descriptor
            )
        }

        if (byName.size > 1) {
            return DirectoryVerdict(
                outcome = CheckOutcome.AMBIGUOUS,
                matched = null,
                matchCount = byName.size,
                detail = "${byName.size} entries match that name. Their NPI would tell them apart."
            )
        }

        if (weak.isNotEmpty()) {
            return DirectoryVerdict(
                outcome = CheckOutcome.AMBIGUOUS,
                matched = null,
                matchCount = weak.size,
                detail = "Only a surname and an initial matched — not enough to be sure it is them."
            )
        }

        return DirectoryVerdict(
            outcome = CheckOutcome.NOT_LISTED,
            matched = null,
            matchCount = 0,
            detail = "The directory returned ${results.size} entries, and none of them is them."
        )
    }

    /**
     * Whether a National Provider Identifier is well-formed: ten digits, and the last one is the
     * Luhn check digit over the number prefixed with `80840` (the NPI's ISO issuer prefix), exactly
     * as the NPPES specification defines it.
     *
     * Worth checking before anything is sent, because a mistyped NPI is otherwise indistinguishable
     * from a doctor who has left the network — the search comes back empty either way.
     */
    fun isValidNpi(raw: String?): Boolean {
        val npi = digits(raw) ?: return false
        if (npi.length != 10) return false
        val payload = NPI_LUHN_PREFIX + npi.substring(0, 9)
        return luhnCheckDigit(payload) == npi[9].digitToInt()
    }

    private fun luhnCheckDigit(payload: String): Int {
        var sum = 0
        payload.reversed().forEachIndexed { index, char ->
            val digit = char.digitToInt()
            // Every second digit from the right of the payload doubles; a doubled 9 becomes 18 → 9.
            val contribution = if (index % 2 == 0) digit * 2 else digit
            sum += if (contribution > 9) contribution - 9 else contribution
        }
        return (10 - sum % 10) % 10
    }

    /** Digits only, or null — so "1234567893", "1234-567-893" and " 1234567893 " are one NPI. */
    fun digits(raw: String?): String? =
        raw?.filter { it.isDigit() }?.ifBlank { null }

    /**
     * What an HTTP status from a directory means. Only the transport is read here; whether the body
     * was actually a `CapabilityStatement` is [FhirDirectoryParser]'s question.
     */
    fun classify(status: Int): DirectoryOutcome = when {
        status in 200..299 -> DirectoryOutcome.REACHABLE
        status == 401 || status == 403 -> DirectoryOutcome.NEEDS_SIGN_IN
        status == 404 || status == 410 -> DirectoryOutcome.NOT_PUBLISHED
        status == 429 -> DirectoryOutcome.RATE_LIMITED
        status in 400..499 -> DirectoryOutcome.NOT_A_DIRECTORY
        else -> DirectoryOutcome.UNREACHABLE
    }

    /** Percent-encoding for one query value. `URLEncoder` lives in the Android half; this stays pure. */
    fun encodeQuery(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            when {
                char.isLetterOrDigit() && char.code < 128 -> append(char)
                char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
                else -> append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    private const val NPI_LUHN_PREFIX = "80840"

    private const val DEFAULT_COUNT = 20

    /**
     * What goes in front of a name. Dropped, but nothing after them is — see [nameTokens].
     */
    private val HONORIFICS = setOf("dr", "doctor", "mr", "mrs", "ms", "miss", "prof", "professor")

    /**
     * What goes after one: credentials and generational suffixes. None of them identify anybody, all
     * of them appear on one side of a comparison and not the other, and every one left in is a name
     * that fails to match itself.
     */
    private val CREDENTIALS = setOf(
        "md", "do", "np", "pa", "rn", "lpn", "aprn", "fnp", "agnp", "cnp", "cns",
        "dds", "dmd", "od", "dpm", "dc", "phd", "psyd", "lcsw",
        "facs", "faap", "facp", "fache", "mph", "msn", "crna", "cnm",
        "jr", "sr", "ii", "iii", "iv"
    )
}

/**
 * A directory's answer about one provider, judged — see [ProviderDirectory.judge].
 *
 * [matched] is null whenever nothing was decided, which includes both "not there" and "several, and
 * Health will not guess". The screen shows the entry when there is one, so the reader can check that
 * the app matched the person they meant.
 */
data class DirectoryVerdict(
    val outcome: CheckOutcome,
    val matched: DirectoryPractitioner?,
    val matchCount: Int,
    val detail: String?
)
