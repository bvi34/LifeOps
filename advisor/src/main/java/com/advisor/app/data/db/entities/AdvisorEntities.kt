package com.advisor.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted per-app grant. One row per [SourceApp][com.advisor.app.logic.SourceApp] the user has
 * turned on; a missing row means "denied" (privacy-first — see
 * [com.advisor.app.logic.AdvisorPermissions]). Keyed by the stable app key string, never an ordinal.
 */
@Entity(tableName = "advisor_permissions")
data class AppPermissionEntity(
    @PrimaryKey val appKey: String,
    val granted: Boolean,
    val updatedAt: Long
)

/**
 * One turn of a saved conversation — the user's question or the assistant's answer. Citations are
 * stored as the newline-joined document ids they referenced, which is enough to show provenance
 * without pulling in a JSON serializer.
 */
@Entity(tableName = "advisor_messages")
data class AdvisorMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val text: String,
    val citationIds: String,
    val createdAt: Long
)
