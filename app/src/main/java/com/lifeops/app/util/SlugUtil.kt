package com.lifeops.app.util

/** Stable dedup key derived from a task title. Lowercase + collapsed whitespace. */
fun String.toSlug(): String = this.lowercase().trim().replace(Regex("\\s+"), " ")
