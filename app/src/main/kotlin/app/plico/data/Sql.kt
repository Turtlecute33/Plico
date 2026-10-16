package app.plico.data

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram

// Small typed helpers over the framework API. Query arguments are bound as strings (the only form
// rawQuery takes); SQLite converts them for comparisons with INTEGER columns. Writes bind typed values.

internal inline fun <T> SQLiteDatabase.list(sql: String, vararg args: Any, row: (Cursor) -> T): List<T> =
    rawQuery(sql, queryArgs(args)).use { c -> buildList(c.count) { while (c.moveToNext()) add(row(c)) } }

internal inline fun <T> SQLiteDatabase.one(sql: String, vararg args: Any, row: (Cursor) -> T): T? =
    rawQuery(sql, queryArgs(args)).use { c -> if (c.moveToFirst()) row(c) else null }

/** The first column of the first row, or null when there is no row or the value is NULL. */
internal fun SQLiteDatabase.long(sql: String, vararg args: Any): Long? =
    one(sql, *args) { c -> c.longOrNull(0) }

/** INSERT; returns the new rowid. */
internal fun SQLiteDatabase.insertRow(sql: String, vararg args: Any?): Long =
    compileStatement(sql).use { s -> s.bind(args); s.executeInsert() }

/** UPDATE or DELETE; returns the number of changed rows. */
internal fun SQLiteDatabase.write(sql: String, vararg args: Any?): Int =
    compileStatement(sql).use { s -> s.bind(args); s.executeUpdateDelete() }

private fun SQLiteProgram.bind(args: Array<out Any?>) =
    args.forEachIndexed { i, a -> DatabaseUtils.bindObjectToProgram(this, i + 1, a) }

private fun queryArgs(args: Array<out Any>): Array<String> = Array(args.size) {
    when (val a = args[it]) {
        is Boolean -> if (a) "1" else "0"
        else -> a.toString()
    }
}

/** `1,2,3` for an `IN (…)` list. Only for numbers, so inlining cannot inject SQL. */
internal fun Iterable<Long>.sqlList(): String = joinToString(",")

/** Splits large id sets so that statements stay small. */
internal fun <T> Collection<T>.batches(): List<List<T>> = chunked(500)

internal fun Cursor.str(i: Int): String = getString(i)
internal fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
internal fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
internal fun Cursor.bool(i: Int): Boolean = getInt(i) != 0
