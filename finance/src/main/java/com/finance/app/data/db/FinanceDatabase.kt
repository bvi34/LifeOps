package com.finance.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.finance.app.data.db.dao.FinanceDao
import com.finance.app.data.db.entities.AccountEntity
import com.finance.app.data.db.entities.BillEntity
import com.finance.app.data.db.entities.ConnectionEntity
import com.finance.app.data.db.entities.TransactionEntity

/**
 * The schema version, in one place — the backup manifest reads it from here rather than repeating
 * the number, so it can't drift from the schema the copied file was written at.
 */
const val FINANCE_DB_VERSION = 1

/**
 * Finance's own store: what the institutions said, and what this app made of it.
 *
 * One database rather than one per concern, because every question crosses them — "what is due
 * before the next paycheque", "what has this card cost in interest", "which account is the
 * insurance actually paid from" — and none of those can be asked across two SQLite files without
 * doing the join in Kotlin.
 *
 * Foreign keys are on and cascading runs connection → account → transaction, so removing a
 * connection takes its accounts and their history with it. Bills are the deliberate exception and
 * hang off no key at all: an obligation can outlive the account it was read from, and a cascade
 * would quietly delete a bill that is still genuinely owed.
 *
 * There are no migrations yet because there is no released version to migrate from. When there is,
 * they go here as `internal` objects, in the way `MaintenanceDatabase` does it — visible to the
 * migration test so it runs the objects that ship rather than a copy that could drift.
 */
@Database(
    entities = [
        ConnectionEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        BillEntity::class
    ],
    version = FINANCE_DB_VERSION,
    exportSchema = true
)
abstract class FinanceDatabase : RoomDatabase() {

    abstract fun financeDao(): FinanceDao

    companion object {
        const val DB_NAME = "finance.db"

        @Volatile
        private var instance: FinanceDatabase? = null

        fun getInstance(context: Context): FinanceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
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
