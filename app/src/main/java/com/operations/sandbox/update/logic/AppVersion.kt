package com.operations.sandbox.update.logic

/**
 * A release version, as the tag names it.
 *
 * The updater's whole job rests on one question — "is the release on GitHub newer than the APK I am
 * running?" — and that question is answered here, in plain Kotlin with no Android in it, so it can
 * be tested on the JVM the way the rest of the suite's real logic is. Android's own versionCode is
 * the wrong tool for it: the phone knows the *installed* code, but the GitHub API hands back a tag,
 * and turning that tag into a code and back is a second place for the arithmetic to be wrong.
 *
 * Semver's ordering rules, as far as this app needs them:
 *  - `major.minor.patch` compare numerically, left to right — so 1.10.0 is newer than 1.9.9, which
 *    a string comparison gets backwards.
 *  - A pre-release ("1.2.0-beta.1") sorts *below* the release it precedes ("1.2.0"), so tagging a
 *    beta never offers itself as an upgrade over the final build of the same version.
 *  - Build metadata after "+" is ignored entirely, which is what the spec says.
 *
 * Anything that isn't parseable is [UNKNOWN], which compares below everything — a garbled tag on
 * the release, or the "0.0.0-dev" a developer build carries, means "not newer than what's running".
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The dot-separated pre-release identifiers, empty for a final release. */
    val preRelease: List<String> = emptyList()
) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        (major - other.major).let { if (it != 0) return it }
        (minor - other.minor).let { if (it != 0) return it }
        (patch - other.patch).let { if (it != 0) return it }
        // Equal cores: a version *with* a pre-release is the earlier one.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        /**
         * Lower than every real version, and equal only to itself. Both the unparseable tag and the
         * local "0.0.0-dev" build land here, which is the conservative answer in both directions:
         * an unreadable release is never offered, and a dev build is always upgradeable.
         */
        val UNKNOWN = AppVersion(0, 0, 0, listOf("unknown"))

        /**
         * Read a version out of whatever the tag or BuildConfig says, tolerating the "v" that tags
         * conventionally carry. Returns [UNKNOWN] rather than throwing: this runs on data fetched
         * from the network, and a bad tag should grey the update out, not crash the launcher.
         */
        fun parse(raw: String?): AppVersion {
            val text = raw?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty()
            if (text.isEmpty()) return UNKNOWN
            val withoutBuild = text.substringBefore('+')
            val core = withoutBuild.substringBefore('-')
            val pre = withoutBuild.substringAfter('-', "")
                .split('.')
                .filter { it.isNotEmpty() }

            val parts = core.split('.')
            if (parts.size !in 1..3) return UNKNOWN
            val numbers = parts.map { it.toIntOrNull() ?: return UNKNOWN }
            if (numbers.any { it < 0 }) return UNKNOWN
            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                preRelease = pre
            )
        }

        /**
         * Semver's pre-release ordering: identifier by identifier, numeric ones compared as numbers
         * and below alphanumeric ones, and a shorter run of identifiers below a longer one that
         * shares its prefix (so "beta" precedes "beta.1").
         */
        private fun comparePreRelease(left: List<String>, right: List<String>): Int {
            for (i in 0 until minOf(left.size, right.size)) {
                val a = left[i]
                val b = right[i]
                val na = a.toIntOrNull()
                val nb = b.toIntOrNull()
                val step = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1
                    nb != null -> 1
                    else -> a.compareTo(b)
                }
                if (step != 0) return step
            }
            return left.size - right.size
        }
    }
}
