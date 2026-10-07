package io.github.yuriimurha.reels.data.db

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.yuriimurha.reels.di.BackendChoice
import io.github.yuriimurha.reels.sync.pacing.Pacer

/**
 * Before Task 7 the request log behind the real 24 h budget lived in `reels.db`; it now lives in `library.db` (P2), which
 * starts empty. Without a copy, an upgrade would forget the last 24 hours of real requests: the budget would read 0 and
 * the restart-gap seed would find nothing. So, ONCE (a flag in the `backend` preferences), the first time `library.db` is
 * opened, the `api_request` rows from the last 24 hours are copied out of `reels.db`.
 *
 * A missing `reels.db` means nothing to copy, and is never created: it is only opened if it is already there, read-only.
 * Failing to copy must not stop the database from opening, so any failure leaves the flag unset and is retried at the
 * next open. Runs on Room's own thread.
 */
class LegacyRequestLogCopy(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        if (prefs.getBoolean(BackendChoice.KEY_REQUEST_LOG_COPIED, false)) return
        try {
            val old = context.getDatabasePath(OLD_DATABASE)
            if (old.isFile) {
                val since = now() - Pacer.DAY_MS
                val times = SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                    source.rawQuery("SELECT at FROM api_request WHERE at > ?", arrayOf(since.toString())).use { rows ->
                        buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
                    }
                }
                db.beginTransaction()
                try {
                    times.forEach { db.execSQL("INSERT INTO api_request (at) VALUES (?)", arrayOf<Any>(it)) }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            prefs.edit().putBoolean(BackendChoice.KEY_REQUEST_LOG_COPIED, true).commit()
        } catch (e: Exception) {
            // Retried at the next open.
        }
    }

    private companion object {
        const val OLD_DATABASE = "reels.db"
    }
}
