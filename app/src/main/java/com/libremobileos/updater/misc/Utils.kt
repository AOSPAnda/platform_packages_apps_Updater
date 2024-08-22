/*
 * SPDX-FileCopyrightText: 2017-2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.misc

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.libremobileos.updater.R
import com.libremobileos.updater.UpdatesDbHelper
import com.libremobileos.updater.controller.UpdaterService
import com.libremobileos.updater.model.Update
import com.libremobileos.updater.model.UpdateBaseInfo
import com.libremobileos.updater.model.UpdateInfo
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*
import java.util.zip.ZipFile

object Utils {

    private const val TAG = "Utils"

    @JvmStatic
    fun getDownloadPath(context: Context): File {
        return File(context.getString(R.string.download_path))
    }

    fun getExportPath(context: Context?): File {
        val dir = context?.getExternalFilesDir(null)?.let {
            File(it, context.getString(R.string.export_path))
        } ?: File("")
        if (!dir.isDirectory) {
            if (dir.exists() || !dir.mkdirs()) {
                throw RuntimeException("Could not create directory")
            }
        }
        return dir
    }

    fun getCachedUpdateList(context: Context): File {
        return File(context.cacheDir, "updates.json")
    }

    // This should really return an UpdateBaseInfo object, but currently this only
    // used to initialize UpdateInfo objects
    private fun parseJsonUpdate(jsonObject: JSONObject): UpdateInfo {
        return Update().apply {
            timestamp = jsonObject.getLong("datetime")
            name = jsonObject.getString("filename")
            downloadId = jsonObject.getString("id")
            type = jsonObject.getString("romtype")
            fileSize = jsonObject.getLong("size")
            downloadUrl = jsonObject.getString("url")
            version = jsonObject.getString("version")
        }
    }

    fun isCompatible(update: UpdateBaseInfo): Boolean {
        if (update.version.split(".")[0].toInt() <
            SystemProperties.get(Constants.PROP_BUILD_VERSION).split(".")[0].toInt()
        ) {
            Log.d(TAG, "${update.name} is older than current Android version")
            return false
        }
        if (!SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false) &&
            update.timestamp <= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        ) {
            Log.d(TAG, "${update.name} is older than/equal to the current build")
            return false
        }
        if (!update.type.equals(
                SystemProperties.get(Constants.PROP_RELEASE_TYPE),
                ignoreCase = true
            )
        ) {
            Log.d(TAG, "${update.name} has type ${update.type}")
            return false
        }
        return true
    }

    fun canInstall(update: UpdateBaseInfo): Boolean {
        return (SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false) ||
                update.timestamp > SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) &&
                (SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_MAJOR_UPDATE, false) ||
                        update.version.split(".")[0].equals(
                            SystemProperties.get(Constants.PROP_BUILD_VERSION).split(".")[0],
                            ignoreCase = true
                        ))
    }

    @Throws(IOException::class, JSONException::class)
    fun parseJson(file: File, compatibleOnly: Boolean): List<UpdateInfo> {
        val updates = mutableListOf<UpdateInfo>()

        val json = StringBuilder()
        BufferedReader(FileReader(file)).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                json.append(line)
            }
        }

        val obj = JSONObject(json.toString())
        val updatesList = obj.getJSONArray("response")
        for (i in 0 until updatesList.length()) {
            if (updatesList.isNull(i)) {
                continue
            }
            try {
                val update = parseJsonUpdate(updatesList.getJSONObject(i))
                if (!compatibleOnly || isCompatible(update)) {
                    updates.add(update)
                } else {
                    Log.d(TAG, "Ignoring incompatible update ${update.name}")
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Could not parse update object, index=$i", e)
            }
        }

        return updates
    }

    fun getServerURL(context: Context): String {
        val incrementalVersion = SystemProperties.get(Constants.PROP_BUILD_VERSION_INCREMENTAL)
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE,
            SystemProperties.get(Constants.PROP_DEVICE)
        )
        val type = SystemProperties.get(Constants.PROP_RELEASE_TYPE).lowercase(Locale.ROOT)

        var serverUrl = SystemProperties.get(Constants.PROP_UPDATER_URI)
        if (serverUrl.trim().isEmpty()) {
            serverUrl = context.getString(R.string.updater_server_url)
        }

        return serverUrl.replace("{device}", device)
            .replace("{type}", type)
            .replace("{incr}", incrementalVersion)
    }

    fun getUpgradeBlockedURL(context: Context?): String {
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE,
            SystemProperties.get(Constants.PROP_DEVICE)
        )
        return context?.getString(R.string.blocked_update_info_url, device) ?: ""
    }

    fun getChangelogURL(context: Context): String {
        return context.getString(R.string.menu_changelog_url)
    }

    fun triggerUpdate(context: Context?, downloadId: String) {
        context?.let {
            val intent = Intent(it, UpdaterService::class.java).apply {
                action = UpdaterService.ACTION_INSTALL_UPDATE
                putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
            }
            it.startService(intent)
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = cm.activeNetwork ?: return false
        val networkCapabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else false
    }

    fun isNetworkMetered(context: Context?): Boolean {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.isActiveNetworkMetered
    }

    @Throws(IOException::class, JSONException::class)
    fun checkForNewUpdates(oldJson: File, newJson: File): Boolean {
        val oldList = parseJson(oldJson, true)
        val newList = parseJson(newJson, true)
        val oldIds = oldList.map { it.downloadId }.toSet()
        return newList.any { it.downloadId !in oldIds }
    }

    @JvmStatic
    fun getZipEntryOffset(zipFile: ZipFile, entryPath: String): Long {
        val FIXED_HEADER_SIZE = 30
        var offset = 0L
        for (entry in zipFile.entries()) {
            val headerSize = FIXED_HEADER_SIZE + entry.name.length +
                    (entry.extra?.size ?: 0)
            offset += headerSize
            if (entry.name == entryPath) {
                return offset
            }
            offset += entry.compressedSize
        }
        Log.e(TAG, "Entry $entryPath not found")
        throw IllegalArgumentException("The given entry was not found")
    }

    fun removeUncryptFiles(downloadPath: File) {
        downloadPath.listFiles { _, name -> name.endsWith(Constants.UNCRYPT_FILE_EXT) }
            ?.forEach { it.delete() }
    }

    @JvmStatic
    fun cleanupDownloadsDir(context: Context) {
        val downloadPath = getDownloadPath(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        removeUncryptFiles(downloadPath)

        val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val prevTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0)
        val lastUpdatePath = preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null)
        val reinstalling = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
        val deleteUpdates = isDeleteUpdatesForceEnabled(context) || preferences.getBoolean(
            Constants.PREF_AUTO_DELETE_UPDATES,
            true
        )
        if ((buildTimestamp != prevTimestamp || reinstalling) && deleteUpdates &&
            lastUpdatePath != null
        ) {
            val lastUpdate = File(lastUpdatePath)
            if (lastUpdate.exists()) {
                lastUpdate.delete()
                preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply()
            }
        }

        val DOWNLOADS_CLEANUP_DONE = "cleanup_done"
        if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
            return
        }

        Log.d(TAG, "Cleaning $downloadPath")
        if (!downloadPath.isDirectory) {
            return
        }
        val files = downloadPath.listFiles() ?: return

        val dbHelper = UpdatesDbHelper(context)
        val knownPaths = dbHelper.getUpdates(null, null).map { it.file.absolutePath }
        for (file in files) {
            if (file.absolutePath !in knownPaths) {
                Log.d(TAG, "Deleting ${file.absolutePath}")
                file.delete()
            }
        }

        preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply()
    }

    @JvmStatic
    fun appendSequentialNumber(file: File): File {
        val (name, extension) = file.name.let {
            val dotIndex = it.lastIndexOf(".")
            if (dotIndex > 0) {
                it.substring(0, dotIndex) to it.substring(dotIndex)
            } else {
                it to ""
            }
        }
        val parent = file.parentFile
        for (i in 1 until Int.MAX_VALUE) {
            val newFile = File(parent, "$name-$i$extension")
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw IllegalStateException()
    }

    @JvmStatic
    fun isABDevice(): Boolean {
        return SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false)
    }

    fun isABUpdate(zipFile: ZipFile): Boolean {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun isABUpdate(file: File): Boolean {
        return ZipFile(file).use { isABUpdate(it) }
    }

    fun addToClipboard(
        context: Context?, label: String, text: String,
        toastMessage: String
    ) {
        context?.let {
            val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.let { clipboardManager ->
                val clip = ClipData.newPlainText(label, text)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(it, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JvmStatic
    fun isEncrypted(context: Context, file: File): Boolean {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.isEncrypted(file)
    }

    fun getUpdateCheckSetting(context: Context): Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getInt(
            Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
            Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY
        )
    }

    fun isUpdateCheckEnabled(context: Context): Boolean {
        return getUpdateCheckSetting(context) != Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER
    }

    fun getUpdateCheckInterval(context: Context): Long {
        return when (getUpdateCheckSetting(context)) {
            Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY -> AlarmManager.INTERVAL_DAY
            Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY -> AlarmManager.INTERVAL_DAY * 7
            Constants.AUTO_UPDATES_CHECK_INTERVAL_MONTHLY -> AlarmManager.INTERVAL_DAY * 30
            else -> AlarmManager.INTERVAL_DAY * 7
        }
    }

    fun isRecoveryUpdateExecPresent(): Boolean {
        return File(Constants.UPDATE_RECOVERY_EXEC).exists()
    }

    @JvmStatic
    fun isDeleteUpdatesForceEnabled(context: Context): Boolean {
        return context.resources.getBoolean(R.bool.config_forceDeleteUpdates)
    }

    @JvmStatic
    fun isABPerfModeForceEnabled(context: Context): Boolean {
        return context.resources.getBoolean(R.bool.config_forcePrioritizeUpdateProcess)
    }
}