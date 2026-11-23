package com.mcosta.mybooks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.db.toBook
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A phone on database version 8 keeps its books when the update adds the shelf columns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "place-migration.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    private fun versionEight(): JSONObject =
        JSONObject(File("../shared/schemas/com.mcosta.mybooks.data.db.AppDatabase/8.json").readText()).getJSONObject("database")

    private fun createVersionEight() {
        val schema = versionEight()
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (e in 0 until entities.length()) {
            val entity = entities.getJSONObject(e)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (i in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (q in 0 until setup.length()) db.execSQL(setup.getString(q))

        val books = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "books" }
        val fields = books.getJSONArray("fields")
        val row = ContentValues()
        for (f in 0 until fields.length()) {
            val field = fields.getJSONObject(f)
            val column = field.getString("columnName")
            if (column == "id" || !field.optBoolean("notNull")) continue
            when {
                column == "title" -> row.put(column, "Stoner")
                column == "status" -> row.put(column, "READ")
                column == "uuid" -> row.put(column, "book-1")
                column == "fieldTimes" -> row.put(column, "{}")
                field.getString("affinity") == "TEXT" -> row.put(column, "")
                else -> row.put(column, 0)
            }
        }
        db.insertOrThrow("books", null, row)
        db.version = 8
        db.close()
    }

    @Test
    fun placeColumnsArriveAndBooksStay() {
        createVersionEight()
        val database = createAndroidDatabase(context, name)
        try {
            val book = runBlocking { database.bookDao().getAll().single().toBook() }
            assertEquals("Stoner", book.title)
            assertNull(book.shelfId)
            assertNull(book.shelfPosition)
        } finally {
            database.close()
        }
    }
}
