package com.people.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.people.app.data.db.dao.CheckInDao
import com.people.app.data.db.dao.PartnerDao
import com.people.app.data.db.dao.PeopleDao
import com.people.app.data.db.entities.CheckInAnswerEntity
import com.people.app.data.db.entities.CheckInEntity
import com.people.app.data.db.entities.CheckInFieldEntity
import com.people.app.data.db.entities.ImportantDateEntity
import com.people.app.data.db.entities.PartnerEventEntity
import com.people.app.data.db.entities.PartnerLinkEntity
import com.people.app.data.db.entities.PartnerOutboxEntity
import com.people.app.data.db.entities.PartnerTakenEntity
import com.people.app.data.db.entities.PartnerWeekTaskEntity
import com.people.app.data.db.entities.PersonEntity
import com.people.app.data.db.entities.PersonNoteEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val PEOPLE_DB_VERSION = 4

/**
 * People's own store. It holds the household directory and nothing about what other apps do with it:
 * no tasks, no readings, no calendar. Peers keep their own rows and reconcile with this one over the
 * sync seam (`sync/`), which is why this database is the *directory* rather than the *authority* —
 * both ends can edit, and merge decides.
 */
@Database(
    entities = [
        PersonEntity::class,
        PersonNoteEntity::class,
        ImportantDateEntity::class,
        PartnerLinkEntity::class,
        PartnerWeekTaskEntity::class,
        PartnerOutboxEntity::class,
        PartnerTakenEntity::class,
        PartnerEventEntity::class,
        CheckInFieldEntity::class,
        CheckInEntity::class,
        CheckInAnswerEntity::class
    ],
    version = PEOPLE_DB_VERSION,
    exportSchema = true
)
abstract class PeopleDatabase : RoomDatabase() {

    abstract fun peopleDao(): PeopleDao

    abstract fun partnerDao(): PartnerDao

    abstract fun checkInDao(): CheckInDao

    companion object {
        const val DB_NAME = "people.db"

        /**
         * v2 adds [PersonEntity.household] — the flag that decides whether Health grows a profile
         * for somebody.
         *
         * Existing rows are seeded **false**, deliberately. Defaulting them true would hand every
         * adult in the house a medical profile on the first round after an upgrade, which is exactly
         * the outcome the flag exists to prevent; whoever is already tracked in Health keeps their
         * profile either way, because the flag only governs *creating* one.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE people ADD COLUMN household INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 adds the partner seam: a link per paired person, the mirror of their week, what we have
         * added to it, what we have taken from it, and the log of what they changed.
         *
         * Purely additive — not one existing column moves. That is not a happy accident: a partner's
         * week is held in tables of its own precisely *because* it must not touch the household's
         * own rows, so a migration that had to alter `people` would have been a sign the feature was
         * reaching somewhere it should not.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partner_links` (" +
                        "`id` TEXT NOT NULL, `personId` TEXT NOT NULL, " +
                        "`partnerInstanceId` TEXT, `partnerName` TEXT NOT NULL, " +
                        "`partnerPersonKey` TEXT, `mySecret` TEXT NOT NULL, " +
                        "`partnerSecret` TEXT NOT NULL, `confirmedAt` INTEGER, " +
                        "`lastSyncAt` INTEGER, `lastRejection` TEXT, `lastSeenAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_partner_links_personId` ON `partner_links` (`personId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_partner_links_partnerInstanceId` " +
                        "ON `partner_links` (`partnerInstanceId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partner_week_tasks` (" +
                        "`linkId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `weekStart` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `dueDate` TEXT, `done` INTEGER NOT NULL, " +
                        "`fromContribution` TEXT, PRIMARY KEY(`linkId`, `taskId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_partner_week_tasks_linkId` " +
                        "ON `partner_week_tasks` (`linkId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partner_outbox` (" +
                        "`id` TEXT NOT NULL, `linkId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`dueDate` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_partner_outbox_linkId` ON `partner_outbox` (`linkId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partner_taken` (" +
                        "`contributionId` TEXT NOT NULL, `linkId` TEXT NOT NULL, " +
                        "`localTaskId` TEXT, `title` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contributionId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_partner_taken_linkId` ON `partner_taken` (`linkId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partner_events` (" +
                        "`id` TEXT NOT NULL, `linkId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `at` INTEGER NOT NULL, `seen` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_partner_events_linkId` ON `partner_events` (`linkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_partner_events_seen` ON `partner_events` (`seen`)")
            }
        }

        /**
         * v4 adds the daily check-in: a person's form, the days recorded against it, and the answers
         * between them.
         *
         * Additive again, and for a related reason to v3's: a check-in is somebody's diary of a day,
         * not a fact about who they are, so it belongs in tables of its own rather than as columns on
         * `people` — where it would ride the sync seam to Health and LifeOps, neither of which asked
         * what the child had for lunch.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `check_in_fields` (" +
                        "`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `options` TEXT, `position` INTEGER NOT NULL, " +
                        "`retired` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`personId`) REFERENCES `people`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_check_in_fields_personId` " +
                        "ON `check_in_fields` (`personId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `check_ins` (" +
                        "`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`personId`) REFERENCES `people`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_ins_personId` ON `check_ins` (`personId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_check_ins_personId_day` " +
                        "ON `check_ins` (`personId`, `day`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_ins_day` ON `check_ins` (`day`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `check_in_answers` (" +
                        "`checkInId` TEXT NOT NULL, `fieldId` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "PRIMARY KEY(`checkInId`, `fieldId`), " +
                        "FOREIGN KEY(`checkInId`) REFERENCES `check_ins`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`fieldId`) REFERENCES `check_in_fields`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_check_in_answers_checkInId` " +
                        "ON `check_in_answers` (`checkInId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_check_in_answers_fieldId` " +
                        "ON `check_in_answers` (`fieldId`)"
                )
            }
        }

        @Volatile
        private var instance: PeopleDatabase? = null

        fun getInstance(context: Context): PeopleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PeopleDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }

        /** Close and drop the singleton so a restore can swap the underlying file. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
