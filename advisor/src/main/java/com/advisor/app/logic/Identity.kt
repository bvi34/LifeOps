package com.advisor.app.logic

/**
 * The user's stable, identity-based data — who Advisor is advising. This is deliberately **not** in
 * the database: it's persisted as a small, human-editable, portable **JSON file** (see
 * `data/identity/IdentityStore`), because identity is slow-changing, worth reading/editing by hand,
 * and belongs in the model's *always-on* context rather than in retrieval.
 *
 * Every field is optional; a blank field simply contributes no context line. Extra, free-form facts
 * that don't fit a named field go in [traits] so the schema never blocks the user.
 */
data class Identity(
    val name: String = "",
    val pronouns: String = "",
    val roles: List<String> = emptyList(),
    val values: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val focus: String = "",
    val communicationStyle: String = "",
    val bio: String = "",
    val traits: Map<String, String> = emptyMap()
) {

    val isEmpty: Boolean
        get() = name.isBlank() && pronouns.isBlank() && roles.isEmpty() && values.isEmpty() &&
            goals.isEmpty() && focus.isBlank() && communicationStyle.isBlank() && bio.isBlank() &&
            traits.isEmpty()

    /** The identity rendered as prompt context lines — only the fields the user actually filled in. */
    fun toContextLines(): List<String> {
        val lines = ArrayList<String>()
        if (name.isNotBlank()) {
            lines += if (pronouns.isNotBlank()) "Name: $name (pronouns: $pronouns)" else "Name: $name"
        } else if (pronouns.isNotBlank()) {
            lines += "Pronouns: $pronouns"
        }
        if (roles.isNotEmpty()) lines += "Roles: " + roles.joinToString(", ")
        if (values.isNotEmpty()) lines += "Values: " + values.joinToString(", ")
        if (goals.isNotEmpty()) lines += "Goals: " + goals.joinToString(", ")
        if (focus.isNotBlank()) lines += "Current focus: $focus"
        if (communicationStyle.isNotBlank()) lines += "Preferred communication style: $communicationStyle"
        if (bio.isNotBlank()) lines += "Bio: $bio"
        for ((key, value) in traits) {
            if (value.isNotBlank()) lines += "$key: $value"
        }
        return lines
    }

    companion object {
        val EMPTY = Identity()
    }
}
