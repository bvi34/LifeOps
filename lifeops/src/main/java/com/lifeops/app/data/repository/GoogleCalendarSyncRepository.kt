package com.lifeops.app.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** One calendar on the device that LifeOps could sync with — usually a Google account calendar. */
data class GoogleCalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String
)

data class CalendarSyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val error: String? = null
)

/**
 * Two-way sync between the user's own busy-block schedule and a calendar on the device's Calendar
 * Provider (CalendarContract) — the same provider the Google Calendar app keeps in sync with the
 * signed-in Google account, so this needs no separate OAuth setup of its own.
 *
 * People are carried as plain #tags: a block tagged with "Mom" pushes as an event whose
 * description ends with "Tags: #Mom", and an incoming event whose title/description contains
 * "#Mom" tags the pulled-in block with that person (matched by name, case/space-insensitive).
 *
 * Push: every own-schedule block (personId == null) is written as an event (insert first time,
 * update thereafter via the stored googleEventId — never duplicated). One-off blocks map to a
 * single-occurrence event; weekly-recurring blocks map to a weekly RRULE event starting at the
 * next matching day.
 *
 * Pull: every occurrence (CalendarContract.Instances already expands recurring Google events) in
 * a rolling window is materialized as its own one-off busy block, so arbitrary Google recurrence
 * rules never need to be reverse-engineered into our own weekly bitmask. An event this app pushed
 * (its description carries a "[lifeops:<id>]" marker) is skipped on pull to avoid a round-trip
 * duplicate. Idempotent per (googleEventId, calendar, occurrence date), so re-syncing updates the
 * same rows instead of piling up copies.
 *
 * All times are read/written in the device's own zone (ZoneId.systemDefault()) — never UTC —
 * matching the rest of the app (see DateUtil) and CalendarContract's own EVENT_TIMEZONE field.
 */
class GoogleCalendarSyncRepository(
    private val appContext: Context,
    private val busyBlockRepository: BusyBlockRepository,
    private val personRepository: PersonRepository
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun listCalendars(): List<GoogleCalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME)
        val out = mutableListOf<GoogleCalendarInfo>()
        appContext.contentResolver.query(
            Calendars.CONTENT_URI, projection,
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars.ACCOUNT_NAME} ASC"
        )?.use { c ->
            val idxId = c.getColumnIndexOrThrow(Calendars._ID)
            val idxName = c.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)
            val idxAccount = c.getColumnIndexOrThrow(Calendars.ACCOUNT_NAME)
            while (c.moveToNext()) {
                out += GoogleCalendarInfo(
                    id = c.getLong(idxId),
                    displayName = c.getString(idxName) ?: "Calendar",
                    accountName = c.getString(idxAccount) ?: ""
                )
            }
        }
        out
    }

    /** Push own-schedule blocks to [calendarId], then pull events from it back in. */
    suspend fun sync(calendarId: Long): CalendarSyncResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext CalendarSyncResult(error = "Calendar permission not granted")
        try {
            val pushed = push(calendarId)
            val pulled = pull(calendarId)
            CalendarSyncResult(pushed = pushed, pulled = pulled)
        } catch (e: Exception) {
            CalendarSyncResult(error = e.message ?: "Sync failed")
        }
    }

    private suspend fun push(calendarId: Long): Int {
        val peopleById = personRepository.getAll().associateBy { it.id }
        val myBlocks = busyBlockRepository.getAll().filter { it.personId == null }
        val resolver = appContext.contentResolver
        var pushed = 0
        for (block in myBlocks) {
            val values = buildEventValues(block, calendarId, peopleById) ?: continue
            val existingId = block.googleEventId
            var eventId = existingId
            if (existingId != null) {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, existingId)
                val updated = resolver.update(uri, values, null, null)
                if (updated == 0) eventId = null // event gone from the calendar; fall through to re-insert
            }
            if (eventId == null) {
                val newUri = resolver.insert(Events.CONTENT_URI, values)
                eventId = newUri?.let { ContentUris.parseId(it) }
            }
            if (eventId != null && eventId != existingId) {
                busyBlockRepository.upsert(block.copy(googleEventId = eventId, googleCalendarId = calendarId))
            }
            pushed++
        }
        return pushed
    }

    private fun buildEventValues(
        block: BusyBlock,
        calendarId: Long,
        peopleById: Map<String, com.lifeops.app.data.model.Person>
    ): ContentValues? {
        val zone = ZoneId.systemDefault()
        val tags = block.peopleIds.mapNotNull { peopleById[it]?.name }
            .joinToString(" ") { "#${it.replace(" ", "")}" }
        val description = buildString {
            append(LIFEOPS_MARKER_PREFIX).append(block.id).append(LIFEOPS_MARKER_SUFFIX)
            if (tags.isNotBlank()) append("\n\nTags: ").append(tags)
        }
        val values = ContentValues().apply {
            put(Events.TITLE, block.title)
            put(Events.DESCRIPTION, description)
            put(Events.CALENDAR_ID, calendarId)
            put(Events.EVENT_TIMEZONE, zone.id)
        }
        if (block.specificDate != null) {
            val date = try { LocalDate.parse(block.specificDate) } catch (_: Exception) { return null }
            values.put(Events.DTSTART, date.atTime(block.startMinutes / 60, block.startMinutes % 60).atZone(zone).toInstant().toEpochMilli())
            values.put(Events.DTEND, date.atTime(block.endMinutes / 60, block.endMinutes % 60).atZone(zone).toInstant().toEpochMilli())
        } else {
            val byDayIndices = (0..6).filter { (block.daysMask and (1 shl it)) != 0 }
            if (byDayIndices.isEmpty()) return null
            var start = LocalDate.now()
            while ((start.dayOfWeek.value - 1) !in byDayIndices) start = start.plusDays(1)
            values.put(Events.DTSTART, start.atTime(block.startMinutes / 60, block.startMinutes % 60).atZone(zone).toInstant().toEpochMilli())
            values.put(Events.DURATION, "PT${block.endMinutes - block.startMinutes}M")
            values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=" + byDayIndices.joinToString(",") { RRULE_DAYS[it] })
        }
        return values
    }

    private suspend fun pull(calendarId: Long): Int {
        val people = personRepository.getAll()
        val zone = ZoneId.systemDefault()
        val windowStart = ZonedDateTime.now(zone).minusDays(1).toInstant().toEpochMilli()
        val windowEnd = ZonedDateTime.now(zone).plusDays(PULL_WINDOW_DAYS).toInstant().toEpochMilli()
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, windowStart)
        ContentUris.appendId(builder, windowEnd)
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END,
            Instances.TITLE, Instances.DESCRIPTION, Instances.ALL_DAY
        )
        var pulled = 0
        appContext.contentResolver.query(
            builder.build(), projection,
            "${Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()),
            "${Instances.BEGIN} ASC"
        )?.use { c ->
            val idxEventId = c.getColumnIndexOrThrow(Instances.EVENT_ID)
            val idxBegin = c.getColumnIndexOrThrow(Instances.BEGIN)
            val idxEnd = c.getColumnIndexOrThrow(Instances.END)
            val idxTitle = c.getColumnIndexOrThrow(Instances.TITLE)
            val idxDescription = c.getColumnIndexOrThrow(Instances.DESCRIPTION)
            val idxAllDay = c.getColumnIndexOrThrow(Instances.ALL_DAY)
            while (c.moveToNext()) {
                if (c.getInt(idxAllDay) != 0) continue // all-day events don't fit a minutes-of-day block
                val description = c.getString(idxDescription) ?: ""
                if (description.contains(LIFEOPS_MARKER_PREFIX)) continue // round-trip guard: skip our own pushes

                val eventId = c.getLong(idxEventId)
                val beginMillis = c.getLong(idxBegin)
                val endMillis = c.getLong(idxEnd)
                val beginZdt = Instant.ofEpochMilli(beginMillis).atZone(zone)
                val date = beginZdt.toLocalDate()
                val startMinutes = beginZdt.hour * 60 + beginZdt.minute
                val endZdt = Instant.ofEpochMilli(endMillis).atZone(zone)
                var endMinutes = if (endZdt.toLocalDate() == date) endZdt.hour * 60 + endZdt.minute else 1439
                if (endMinutes <= startMinutes) endMinutes = (startMinutes + 1).coerceAtMost(1439)
                val title = c.getString(idxTitle)?.takeIf { it.isNotBlank() } ?: "Busy"

                val tags = Regex("#(\\w+)").findAll("$title $description")
                    .map { it.groupValues[1].lowercase() }.toSet()
                val taggedPeople = people.filter { it.name.replace(" ", "").lowercase() in tags }.map { it.id }

                val existing = busyBlockRepository.findByGoogleInstance(eventId, calendarId, date.toString())
                busyBlockRepository.upsert(
                    BusyBlock(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        title = title,
                        startMinutes = startMinutes,
                        endMinutes = endMinutes,
                        daysMask = 0,
                        specificDate = date.toString(),
                        personId = null,
                        createdAt = existing?.createdAt ?: DateUtil.now(),
                        reminderEnabled = existing?.reminderEnabled ?: false,
                        googleEventId = eventId,
                        googleCalendarId = calendarId,
                        peopleIds = taggedPeople
                    )
                )
                pulled++
            }
        }
        return pulled
    }

    companion object {
        private val RRULE_DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        private const val PULL_WINDOW_DAYS = 60L
        private const val LIFEOPS_MARKER_PREFIX = "[lifeops:"
        private const val LIFEOPS_MARKER_SUFFIX = "]"
    }
}
