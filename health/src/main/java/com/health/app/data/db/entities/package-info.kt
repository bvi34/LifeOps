package com.health.app.data.db.entities

/**
 * Health's tables, one file per thing the app keeps records about — the same division the stores in
 * `data/repository` use, so a table and the code that writes it are found the same way.
 *
 * Two conventions run through all of them, and both are deliberate:
 *
 *  - **Instants are epoch millis, dates are ISO strings.** When a temperature was taken is a moment
 *    — it is compared, subtracted and windowed by `logic/`, so it is a `Long`. A birth date is a
 *    calendar fact that must not shift when someone changes time zone, so it is `yyyy-MM-dd` text.
 *  - **Every row belongs to a profile.** There is no "current person" hiding in a global; the
 *    profile id is on the row, because the whole point of this app is more than one person.
 *
 * Cross-table links (`episodeId`, `medicationId`) are plain nullable ids rather than Room foreign
 * keys: a reading taken before anyone declared an illness is still a real reading, and deleting a
 * medication should not delete the record that a dose of it was given. The profile link is the one
 * exception — see the cascade in `ProfileEntity`'s note.
 */
