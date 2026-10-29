package com.example.update

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class InAppUpdateState {
    object Idle : InAppUpdateState()
    data class UpdateAvailable(
        val appUpdateInfo: AppUpdateInfo,
        val isImmediate: Boolean,
        val availableVersionCode: Int
    ) : InAppUpdateState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytesToDownload: Long
    ) : InAppUpdateState()
    object Downloaded : InAppUpdateState()
}

class InAppUpdateManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(appContext)
    private val prefs: SharedPreferences = appContext.getSharedPreferences("musicplay_in_app_update", Context.MODE_PRIVATE)

    private val _updateState = MutableStateFlow<InAppUpdateState>(InAppUpdateState.Idle)
    val updateState: StateFlow<InAppUpdateState> = _updateState.asStateFlow()

    private var currentAppUpdateInfo: AppUpdateInfo? = null

    private val installStateUpdatedListener = InstallStateUpdatedListener { state: InstallState ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val bytes = state.bytesDownloaded()
                val total = state.totalBytesToDownload()
                _updateState.value = InAppUpdateState.Downloading(bytes, total)
                Log.d(TAG, "Update downloading: $bytes / $total")
            }
            InstallStatus.DOWNLOADED -> {
                _updateState.value = InAppUpdateState.Downloaded
                Log.d(TAG, "Update downloaded and ready to install.")
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                Log.w(TAG, "Update failed or canceled with status: ${state.installStatus()}")
                _updateState.value = InAppUpdateState.Idle
            }
            else -> {
                // Other statuses (PENDING, INSTALLING, INSTALLED, UNKNOWN)
            }
        }
    }

    init {
        try {
            appUpdateManager.registerListener(installStateUpdatedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register install listener: ${e.message}")
        }
    }

    fun checkForAppUpdate(isManualCheck: Boolean = false) {
        try {
            val appUpdateInfoTask = appUpdateManager.appUpdateInfo
            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                currentAppUpdateInfo = appUpdateInfo
                val availability = appUpdateInfo.updateAvailability()
                val availableVersion = appUpdateInfo.availableVersionCode()

                Log.d(TAG, "Play update availability: $availability, availableVersion: $availableVersion")

                if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                    val updatePriority = appUpdateInfo.updatePriority()
                    val stalenessDays = appUpdateInfo.clientVersionStalenessDays() ?: 0

                    // High priority (>=4) or staleness > 30 days qualifies as immediate update
                    val isImmediate = (updatePriority >= 4 || stalenessDays >= 30) &&
                            appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

                    val isFlexible = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

                    if (isImmediate || isFlexible) {
                        // Check dismissal frequency if not manual check and not immediate
                        if (!isManualCheck && !isImmediate && wasRecentlyDismissed(availableVersion)) {
                            Log.d(TAG, "Update prompt for version $availableVersion was recently dismissed. Skipping prompt.")
                            return@addOnSuccessListener
                        }

                        _updateState.value = InAppUpdateState.UpdateAvailable(
                            appUpdateInfo = appUpdateInfo,
                            isImmediate = isImmediate,
                            availableVersionCode = availableVersion
                        )
                    } else {
                        Log.d(TAG, "Neither Immediate nor Flexible update allowed for this client.")
                    }
                } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    // Update in progress, resume if immediate
                    val isImmediate = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    _updateState.value = InAppUpdateState.UpdateAvailable(
                        appUpdateInfo = appUpdateInfo,
                        isImmediate = isImmediate,
                        availableVersionCode = availableVersion
                    )
                } else {
                    if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                        _updateState.value = InAppUpdateState.Downloaded
                    } else {
                        _updateState.value = InAppUpdateState.Idle
                    }
                }
            }.addOnFailureListener { exception ->
                Log.w(TAG, "Play In-App Update check failed: ${exception.message}")
                // Graceful failure - do not disrupt normal app experience
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error checking for in-app updates: ${e.message}")
        }
    }

    fun startUpdate(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        val updateInfo = currentAppUpdateInfo ?: return
        val currentState = _updateState.value
        val updateType = if (currentState is InAppUpdateState.UpdateAvailable && currentState.isImmediate) {
            AppUpdateType.IMMEDIATE
        } else {
            AppUpdateType.FLEXIBLE
        }

        try {
            val options = AppUpdateOptions.newBuilder(updateType).build()
            appUpdateManager.startUpdateFlowForResult(
                updateInfo,
                launcher,
                options
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start update flow: ${e.message}")
        }
    }

    fun completeUpdate() {
        try {
            appUpdateManager.completeUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete update: ${e.message}")
        }
    }

    fun dismissPrompt(versionCode: Int) {
        prefs.edit()
            .putLong("dismissed_at_$versionCode", System.currentTimeMillis())
            .apply()
        _updateState.value = InAppUpdateState.Idle
    }

    private fun wasRecentlyDismissed(versionCode: Int): Boolean {
        val lastDismissed = prefs.getLong("dismissed_at_$versionCode", 0L)
        val oneDayMillis = 24 * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastDismissed) < oneDayMillis
    }

    fun onResume(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        try {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                currentAppUpdateInfo = appUpdateInfo
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    _updateState.value = InAppUpdateState.Downloaded
                } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    // Resume immediate update flow
                    if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        startUpdate(activity, launcher)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update onResume: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering listener: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "InAppUpdateManager"

        @Volatile
        private var instance: InAppUpdateManager? = null

        fun getInstance(context: Context): InAppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: InAppUpdateManager(context).also { instance = it }
            }
        }
    }
}
