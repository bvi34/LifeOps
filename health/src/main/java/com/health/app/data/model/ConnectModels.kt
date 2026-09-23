package com.health.app.data.model

import com.health.app.logic.ConnectKind

/**
 * What Health imports from Health Connect, and what the screens show of it.
 */

/**
 * One record on its way in from Health Connect, already reduced to Health's shape by
 * `connect/RecordMapper`. Framework-free, so the store that files it is tested without Health
 * Connect; the row it becomes is `ConnectRecordEntity`.
 *
 * [detail] is everything else the record carried, as plain values — numbers, strings, lists and
 * maps of them — ready to be written out as JSON.
 */
data class ImportedRecord(
    val id: String,
    val kind: ConnectKind,
    val startAt: Long,
    val endAt: Long?,
    val zoneOffsetSeconds: Int?,
    val value: Double?,
    val secondaryValue: Double? = null,
    val detail: Map<String, Any?> = emptyMap(),
    val source: String? = null,
    val device: String? = null,
    val modifiedAt: Long
)

/** One imported record, as a screen shows it. [detail] is the JSON the row keeps. */
data class ConnectRecord(
    val id: String,
    val profileId: String,
    val kind: ConnectKind,
    val startAt: Long,
    val endAt: Long?,
    val value: Double?,
    val secondaryValue: Double?,
    val detail: String?,
    val source: String?,
    val device: String?
)

/** How much of one kind has been imported for a person, and how recent the newest is. */
data class ConnectKindTotal(val kind: ConnectKind, val count: Int, val latestAt: Long?)
