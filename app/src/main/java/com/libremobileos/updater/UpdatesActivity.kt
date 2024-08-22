package com.libremobileos.updater

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.snackbar.Snackbar
import com.libremobileos.updater.controller.UpdaterController
import com.libremobileos.updater.controller.UpdaterService
import com.libremobileos.updater.download.DownloadClient
import com.libremobileos.updater.misc.Constants
import com.libremobileos.updater.misc.Utils
import com.libremobileos.updater.model.UpdateInfo
import com.libremobileos.updater.model.UpdateStatus
import com.libremobileos.updater.ui.PreferenceSheet
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.UUID

class UpdatesActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdatesActivity"
    }

    private lateinit var mUpdaterService: UpdaterService
    private lateinit var mBroadcastReceiver: BroadcastReceiver
    private lateinit var bounceAnimation: Animation
    private lateinit var headerTitle: CollapsingToolbarLayout
    private lateinit var updateView: UpdateView
    private lateinit var actionCheck: RelativeLayout
    private lateinit var pullToRefresh: SwipeRefreshLayout
    private var impatience = 0
    private var mToBeExported: UpdateInfo? = null

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as UpdaterService.LocalBinder
            mUpdaterService = binder.service
            updateView.setUpdaterController(mUpdaterService.updaterController)
            getUpdatesList()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            updateView.setUpdaterController(null)
            updateView.lateInit()
        }
    }

    private val mExportUpdate: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportUpdate(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)

        actionCheck = findViewById(R.id.actionCheck)
        pullToRefresh = findViewById(R.id.updates_swipe_container)
        val actionStart: RelativeLayout = findViewById(R.id.actionStart)
        val actionOptions: LinearLayout = findViewById(R.id.actionOptions)
        val updateProgress: RelativeLayout = findViewById(R.id.updateProgressLayout)
        val actionInstall: RelativeLayout = findViewById(R.id.actionInstall)
        val actionReboot: RelativeLayout = findViewById(R.id.actionReboot)
        val actionDelete: RelativeLayout = findViewById(R.id.actionDelete)

        updateView = findViewById(R.id.updateView)
        updateView.setupControlViews(
            actionCheck,
            actionStart,
            updateProgress,
            actionOptions,
            actionInstall,
            actionReboot,
            actionDelete
        )
        updateView.setActivity(this)

        mBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UpdaterController.ACTION_UPDATE_STATUS -> {
                        val downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
                        downloadId?.let { handleDownloadStatusChange(it) }
                        updateView.lateInit()
                    }

                    UpdaterController.ACTION_DOWNLOAD_PROGRESS,
                    UpdaterController.ACTION_INSTALL_PROGRESS,
                    UpdaterController.ACTION_UPDATE_REMOVED -> {
                        updateView.lateInit()
                    }
                }
            }
        }

        headerTitle = findViewById(R.id.app_bar)

        val mainIcon: ImageButton = findViewById(R.id.launchSettings)
        mainIcon.setOnClickListener { showPreferencesDialog() }

        bounceAnimation = AnimationUtils.loadAnimation(this, R.anim.bounce)

        actionCheck.findViewById<View>(R.id.actionCheckButton)
            .setOnClickListener { downloadUpdatesList(true) }
        pullToRefresh.setOnRefreshListener { downloadUpdatesList(true) }

        checkAndRequestForPermissionNotification()
    }

    private fun checkAndRequestForPermissionNotification() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, UpdaterService::class.java).also { intent ->
            startService(intent)
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        }

        val intentFilter = IntentFilter().apply {
            addAction(UpdaterController.ACTION_UPDATE_STATUS)
            addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
            addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
            addAction(UpdaterController.ACTION_UPDATE_REMOVED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver)
        if (::mUpdaterService.isInitialized) {
            unbindService(mConnection)
        }
        super.onStop()
    }

    private fun loadUpdatesList(jsonFile: File, manualRefresh: Boolean) {
        Log.d(TAG, "Adding remote updates")
        val controller = mUpdaterService.updaterController
        var newUpdates = false

        val updates = Utils.parseJson(jsonFile, true)
        val updatesOnline = mutableListOf<String>()
        for (update in updates) {
            newUpdates = newUpdates or controller.addUpdate(update)
            updatesOnline.add(update.downloadId)
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true)

        if (manualRefresh) {
            impatience++
            updateView.unleashTheBunny(if (!newUpdates && impatience >= 3) R.string.bunny else R.string.nothing)
            if (newUpdates) {
                updateView.unleashTheBunny(R.string.hit)
            }
        }

        val sortedUpdates = controller.getUpdates()
        if (sortedUpdates.isEmpty()) {
            updateView.setDownloadId(null)
            updateView.noUpdates()
            actionCheck.visibility = View.VISIBLE
        } else {
            sortedUpdates.sortByDescending { it.timestamp }
            headerTitle.title = getString(R.string.snack_updates_found)
            actionCheck.visibility = View.GONE
            updateView.setDownloadId(sortedUpdates[0].downloadId)
        }
    }

    private fun getUpdatesList() {
        val jsonFile = Utils.getCachedUpdateList(this)
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false)
                Log.d(TAG, "Cached list parsed")
            } catch (e: IOException) {
                Log.e(TAG, "Error while parsing json list", e)
            } catch (e: JSONException) {
                Log.e(TAG, "Error while parsing json list", e)
            }
        } else {
            downloadUpdatesList(false)
        }
    }

    private fun processNewJson(json: File, jsonNew: File, manualRefresh: Boolean) {
        try {
            loadUpdatesList(jsonNew, manualRefresh)
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val millis = System.currentTimeMillis()
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply()
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                Utils.checkForNewUpdates(json, jsonNew)
            ) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this)
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this)
            jsonNew.renameTo(json)
        } catch (e: IOException) {
            Log.e(TAG, "Could not read json", e)
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG)
        } catch (e: JSONException) {
            Log.e(TAG, "Could not read json", e)
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG)
        }
    }

    private fun downloadUpdatesList(manualRefresh: Boolean) {
        val jsonFile = Utils.getCachedUpdateList(this)
        val jsonFileTmp = File(jsonFile.absolutePath + UUID.randomUUID())
        val url = Utils.getServerURL(this)
        Log.d(TAG, "Checking $url")

        val callback = object : DownloadClient.DownloadCallback {
            override fun onFailure(cancelled: Boolean) {
                Log.e(TAG, "Could not download updates list")
                runOnUiThread {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG)
                    }
                    refreshAnimationStop()
                    pullToRefresh.isRefreshing = false
                }
            }

            override fun onResponse(headers: DownloadClient.Headers) {}

            override fun onSuccess() {
                runOnUiThread {
                    Log.d(TAG, "List downloaded")
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh)
                    refreshAnimationStop()
                    pullToRefresh.isRefreshing = false
                }
            }
        }

        try {
            val downloadClient = DownloadClient.Builder()
                .setUrl(url)
                .setDestination(jsonFileTmp)
                .setDownloadCallback(callback)
                .build()
            refreshAnimationStart()
            downloadClient.start()
        } catch (exception: IOException) {
            Log.e(TAG, "Could not build download client")
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG)
        }
    }

    private fun handleDownloadStatusChange(downloadId: String) {
        if (!::mUpdaterService.isInitialized) return

        val update = mUpdaterService.updaterController.getUpdate(downloadId)
        when (update.status) {
            UpdateStatus.PAUSED_ERROR ->
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG)

            UpdateStatus.VERIFICATION_FAILED ->
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG)

            UpdateStatus.VERIFIED ->
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG)

            else -> {}
        }
    }

    fun exportUpdate(update: UpdateInfo) {
        mToBeExported = update

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, update.name)
        }

        mExportUpdate.launch(intent)
    }

    private fun exportUpdate(uri: Uri) {
        val intent = Intent(this, ExportUpdateService::class.java).apply {
            action = ExportUpdateService.ACTION_START_EXPORTING
            putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported?.file)
            putExtra(ExportUpdateService.EXTRA_DEST_URI, uri)
        }
        startService(intent)
    }

    fun showSnackbar(stringId: Int, duration: Int) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show()
    }

    private fun refreshAnimationStart() {
        bounceAnimation.repeatCount = Animation.INFINITE
        actionCheck.startAnimation(bounceAnimation)
        actionCheck.isEnabled = false
    }

    private fun refreshAnimationStop() {
        bounceAnimation.repeatCount = 0
        actionCheck.isEnabled = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPreferencesDialog() {
        PreferenceSheet().setupPreferenceSheet(mUpdaterService)
            .show(supportFragmentManager, "prefdialog")
    }
}