package app.plico.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

/**
 * The app database (framework SQLite, WAL, foreign keys on). Schema in DESIGN.md §5.
 * Writes go through [tx]; after the commit, the touched table names are emitted on [changes], which
 * re-runs every [observe] query that reads one of them. Every non-Flow call of the DAOs blocks; callers
 * run them on Dispatchers.IO.
 */
class Db(context: Context, name: String = "plico.db") {
    private val helper = object : SQLiteOpenHelper(context, name, null, Schema.VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) = Schema.V1.forEach(db::execSQL)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    // tx() must never block, so emission cannot suspend. The observe() collectors only filter and hand
    // over to a conflated buffer, so they keep up; only a stalled collector would lose its oldest sets.
    private val changed = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Tables touched by the open transaction of this thread; null outside [tx]. */
    private val touched = ThreadLocal<MutableSet<String>?>()

    val sql: SQLiteDatabase get() = helper.writableDatabase

    /** Table names changed by each committed transaction. */
    val changes: SharedFlow<Set<String>> = changed.asSharedFlow()

    /** Runs [block] in a transaction and then emits [tables] on [changes]. Nested calls join the outer transaction. */
    fun <T> tx(vararg tables: String, block: SQLiteDatabase.() -> T): T {
        val db = sql
        val outer = touched.get()
        val tableSet = outer ?: HashSet<String>().also(touched::set)
        tableSet.addAll(tables)
        db.beginTransaction()
        val result = try {
            db.block().also { db.setTransactionSuccessful() }
        } finally {
            try {
                db.endTransaction()
            } finally {
                if (outer == null) touched.remove()
            }
        }
        // Only the outermost call commits; joined calls added their tables to its set.
        if (outer == null && tableSet.isNotEmpty()) changed.tryEmit(tableSet)
        return result
    }

    /** Emits [read] now and again after every change to one of [tables]. Reads run on Dispatchers.IO. */
    fun <T> observe(vararg tables: String, read: SQLiteDatabase.() -> T): Flow<T> {
        require(tables.isNotEmpty()) { "observe() needs a table" }
        val watched = tables.toSet()
        return changes
            // The first read starts only once the subscription is active, so no commit is missed.
            .onSubscription { emit(watched) }
            .filter { set -> set.any(watched::contains) }
            .conflate()
            .map { sql.read() }
            .conflate()
            .flowOn(Dispatchers.IO)
    }

    /** Closes the database; the next access opens it again. */
    fun close() = helper.close()
}
