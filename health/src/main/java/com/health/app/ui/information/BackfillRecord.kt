package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.health.app.data.model.CareKind
import com.health.app.data.model.Medication
import com.health.app.logic.TempSite
import com.health.app.logic.Temperature
import com.health.app.ui.common.*

/**
 * The vocabulary the backfill dialogs and the view model share.
 *
 * Its own file because it is the seam between them: `EpisodeDialogs` builds one of these and
 * [InformationViewModel] consumes it, and a type that two files agree on belongs to neither.
 */

/**
 * Something being added to the history after the fact.
 *
 * A sealed type rather than four view-model methods because the four record dialogs are reached
 * through one "add something that happened" chooser, and the chooser needs to hand back *whatever
 * was filled in* without the caller having to remember which of four callbacks matches which dialog.
 */
sealed interface BackfillRecord {
    val at: Long

    data class Temperature(
        val celsius: Double,
        val site: TempSite,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Dose(
        val medication: Medication?,
        val name: String,
        val amount: Double,
        val unit: String,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Symptom(
        val name: String,
        val severity: Int,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Care(val kind: CareKind, val text: String, override val at: Long) : BackfillRecord
}
