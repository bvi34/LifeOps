package com.health.app.data.model

/**
 * What the screens work in, one file per thing the app keeps records about — the same division the
 * stores in `data/repository` and the tables in `data/db/entities` use.
 *
 * These are the entity rows with their string columns resolved into the enums `logic/` actually
 * reasons about. The mapping happens once, in `data/repository/Mappers.kt`, rather than being
 * re-guessed by each screen.
 */
