/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import com.libremobileos.updater.model.Update
import java.io.File

class UpdatesDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    object UpdateEntry : BaseColumns {
        const val TABLE_NAME = "updates"
        const val COLUMN_NAME_STATUS = "status"
        const val COLUMN_NAME_PATH = "path"
        const val COLUMN_NAME_DOWNLOAD_ID = "download_id"
        const val COLUMN_NAME_TIMESTAMP = "timestamp"
        const val COLUMN_NAME_TYPE = "type"
        const val COLUMN_NAME_VERSION = "version"
        const val COLUMN_NAME_SIZE = "size"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(SQL_DELETE_ENTRIES)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    fun addUpdateWithOnConflict(update: Update, conflictAlgorithm: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            fillContentValues(update, this)
        }
        db.insertWithOnConflict(UpdateEntry.TABLE_NAME, null, values, conflictAlgorithm)
    }

    fun removeUpdate(downloadId: String) {
        val db = writableDatabase
        val selection = "${UpdateEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val selectionArgs = arrayOf(downloadId)
        db.delete(UpdateEntry.TABLE_NAME, selection, selectionArgs)
    }

    fun changeUpdateStatus(update: Update) {
        val selection = "${UpdateEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val selectionArgs = arrayOf(update.downloadId)
        changeUpdateStatus(selection, selectionArgs, update.persistentStatus)
    }

    fun getUpdates(selection: String? = null, selectionArgs: Array<String>? = null): List<Update> {
        val db = readableDatabase
        val projection = arrayOf(
            UpdateEntry.COLUMN_NAME_PATH,
            UpdateEntry.COLUMN_NAME_DOWNLOAD_ID,
            UpdateEntry.COLUMN_NAME_TIMESTAMP,
            UpdateEntry.COLUMN_NAME_TYPE,
            UpdateEntry.COLUMN_NAME_VERSION,
            UpdateEntry.COLUMN_NAME_STATUS,
            UpdateEntry.COLUMN_NAME_SIZE
        )
        val sort = "${UpdateEntry.COLUMN_NAME_TIMESTAMP} DESC"
        val cursor = db.query(
            UpdateEntry.TABLE_NAME, projection, selection, selectionArgs,
            null, null, sort
        )

        val updates = mutableListOf<Update>()
        cursor?.use {
            while (it.moveToNext()) {
                val update = Update().apply {
                    file =
                        File(it.getString(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_PATH)))
                    name = file.name
                    downloadId =
                        it.getString(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_DOWNLOAD_ID))
                    timestamp =
                        it.getLong(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_TIMESTAMP))
                    type = it.getString(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_TYPE))
                    version =
                        it.getString(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_VERSION))
                    persistentStatus =
                        it.getInt(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_STATUS))
                    fileSize = it.getLong(it.getColumnIndexOrThrow(UpdateEntry.COLUMN_NAME_SIZE))
                }
                updates.add(update)
            }
        }
        return updates
    }

    private fun changeUpdateStatus(selection: String, selectionArgs: Array<String>, status: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(UpdateEntry.COLUMN_NAME_STATUS, status)
        }
        db.update(UpdateEntry.TABLE_NAME, values, selection, selectionArgs)
    }

    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "updates.db"

        private const val SQL_CREATE_ENTRIES = """
            CREATE TABLE ${UpdateEntry.TABLE_NAME} (
                ${BaseColumns._ID} INTEGER PRIMARY KEY,
                ${UpdateEntry.COLUMN_NAME_STATUS} INTEGER,
                ${UpdateEntry.COLUMN_NAME_PATH} TEXT,
                ${UpdateEntry.COLUMN_NAME_DOWNLOAD_ID} TEXT NOT NULL UNIQUE,
                ${UpdateEntry.COLUMN_NAME_TIMESTAMP} INTEGER,
                ${UpdateEntry.COLUMN_NAME_TYPE} TEXT,
                ${UpdateEntry.COLUMN_NAME_VERSION} TEXT,
                ${UpdateEntry.COLUMN_NAME_SIZE} INTEGER)
        """

        private const val SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS ${UpdateEntry.TABLE_NAME}"

        private fun fillContentValues(update: Update, values: ContentValues) {
            values.apply {
                put(UpdateEntry.COLUMN_NAME_STATUS, update.persistentStatus)
                put(UpdateEntry.COLUMN_NAME_PATH, update.file.absolutePath)
                put(UpdateEntry.COLUMN_NAME_DOWNLOAD_ID, update.downloadId)
                put(UpdateEntry.COLUMN_NAME_TIMESTAMP, update.timestamp)
                put(UpdateEntry.COLUMN_NAME_TYPE, update.type)
                put(UpdateEntry.COLUMN_NAME_VERSION, update.version)
                put(UpdateEntry.COLUMN_NAME_SIZE, update.fileSize)
            }
        }
    }
}