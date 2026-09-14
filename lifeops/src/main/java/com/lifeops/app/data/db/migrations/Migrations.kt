package com.lifeops.app.data.db.migrations

import androidx.room.migration.Migration

/**
 * Every LifeOps schema migration, in version order, as Room wants them.
 *
 * The list lives here rather than inline in the database builder so that adding a migration is a
 * two-line change in one place: write it in the `MigrationsNNToNN.kt` file for its range, then name
 * it here. The individual migrations are grouped into those files purely by version range — see any
 * of them for why.
 */
internal val LIFEOPS_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21,
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_30_31,
    MIGRATION_31_32,
    MIGRATION_32_33,
    MIGRATION_33_34,
    MIGRATION_34_35,
    MIGRATION_35_36,
    MIGRATION_36_37,
    MIGRATION_37_38,
    MIGRATION_38_39,
    MIGRATION_39_40,
    MIGRATION_40_41,
    MIGRATION_41_42,
    MIGRATION_42_43,
    MIGRATION_43_44,
    MIGRATION_44_45,
    MIGRATION_45_46,
    MIGRATION_46_47,
    MIGRATION_47_48,
    MIGRATION_48_49,
    MIGRATION_49_50,
    MIGRATION_50_51,
    MIGRATION_51_52,
    MIGRATION_52_53,
    MIGRATION_53_54,
    MIGRATION_54_55
)
