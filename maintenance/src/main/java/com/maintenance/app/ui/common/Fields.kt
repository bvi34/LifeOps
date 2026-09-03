package com.maintenance.app.ui.common

import java.time.LocalDate

/**
 * What is left of Maintenance's own form fields: nothing but a date helper.
 *
 * The text, number and money fields that used to live here are the suite's now
 * ([com.operations.suite.ui.fields.SuiteTextField] and friends) — they were the best-written of the
 * five copies the apps had grown, so they became the shared ones rather than being replaced by them.
 * The money arithmetic underneath went with them, to [com.operations.suitekit.SuiteMoney].
 */

/** Today, as the millis these screens store. */
fun todayMillis(): Long = toEpochMillis(LocalDate.now())
