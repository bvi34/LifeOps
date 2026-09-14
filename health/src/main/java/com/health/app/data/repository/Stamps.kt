package com.health.app.data.repository

import java.util.UUID

/**
 * The two stamps every store in this package puts on a new row.
 *
 * Top-level rather than a base class because inheriting from something in order to call
 * `System.currentTimeMillis()` is a hierarchy that buys nothing. Both stay `internal`: they are how
 * this package writes rows, not part of what it offers anyone else.
 */
internal fun now(): Long = System.currentTimeMillis()

internal fun newId(): String = UUID.randomUUID().toString()
