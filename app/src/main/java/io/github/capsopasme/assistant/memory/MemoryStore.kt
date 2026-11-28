package io.github.capsopasme.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Memory(val id: Long, val subject: String, val content: String, val updated: Long) {
    /** "11月28日 18:30" / "今天 18:30" / "昨天 08:05" */
    fun whenText(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val m = Calendar.getInstance().apply { timeInMillis = updated }
        val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(updated))
        val days = dayIndex(c) - dayIndex(m)
        return when {
            days == 0 -> "今天 $hm"
            days == 1 -> "昨天 $hm"
            c.get(Calendar.YEAR) == m.get(Calendar.YEAR) -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(updated))
            else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(updated))
        }
    }

    private fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
}

/**
 * What the user asked to be remembered: one row per subject (the same subject said again replaces
 * the old value, e.g. a new parking spot). App-private SQLite, excluded from backups. Call off the
 * main thread.
 */
class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mem (id INTEGER PRIMARY KEY AUTOINCREMENT, subject TEXT NOT NULL, canon TEXT NOT NULL, content TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX mem_canon ON mem(canon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun all(): List<Memory> = readableDatabase.rawQuery(
        "SELECT id, subject, content, updated FROM mem ORDER BY updated DESC", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(Memory(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
    }

    /** @return the stored memory and the one it replaced (same subject), if any */
    @Synchronized fun remember(subject: String, content: String): Pair<Memory, Memory?> {
        val canon = MemoryParser.canonical(subject)
        val old = all().firstOrNull { MemoryParser.canonical(it.subject) == canon }
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("subject", subject); put("canon", canon); put("content", content); put("updated", now)
        }
        val db = writableDatabase
        val id = if (old != null) {
            db.update("mem", v, "id = ?", arrayOf(old.id.toString()))
            old.id
        } else db.insert("mem", null, v)
        if (count() > MAX) {
            db.execSQL("DELETE FROM mem WHERE id IN (SELECT id FROM mem ORDER BY updated ASC LIMIT ${count() - MAX})")
        }
        return Memory(id, subject, content, now) to old
    }

    /** the best match for a question, with its score */
    @Synchronized fun best(query: String): Pair<Memory, Double>? =
        all().map { it to MemoryParser.score(query, it.subject, it.content) }.maxByOrNull { it.second }

    /** the memories most related to [text], at most [n] */
    @Synchronized fun related(text: String, n: Int): List<Memory> =
        all().map { it to MemoryParser.score(text, it.subject, it.content) }
            .filter { it.second > 0.2 }.sortedByDescending { it.second }.take(n).map { it.first }

    /** deletes what matches [query] well; @return what was deleted */
    @Synchronized fun forget(query: String): List<Memory> {
        val hits = all().map { it to MemoryParser.score(query, it.subject, it.content) }
            .filter { it.second >= MemoryParser.RECALL_MIN }
        if (hits.isEmpty()) return emptyList()
        // only the clear winner(s): "忘掉车位" must not take an unrelated memory along
        val top = hits.maxOf { it.second }
        val gone = hits.filter { it.second >= top - 0.05 }.map { it.first }
        for (m in gone) delete(m.id)
        return gone
    }

    @Synchronized fun delete(id: Long) {
        writableDatabase.delete("mem", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized fun clear(): Int = writableDatabase.delete("mem", null, null)

    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM mem", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        /** plenty for personal notes; the oldest go first beyond this */
        private const val MAX = 300

        @Volatile private var instance: MemoryStore? = null
        fun get(context: Context): MemoryStore = instance ?: synchronized(this) {
            instance ?: MemoryStore(context).also { instance = it }
        }
    }
}
