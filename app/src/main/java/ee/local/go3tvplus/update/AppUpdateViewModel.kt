package ee.local.go3tvplus.update

import android.app.Application
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ee.local.go3tvplus.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdateStage { CHECKING, AVAILABLE, DOWNLOADING, PERMISSION, READY, INSTALLING, CURRENT, ERROR }

data class AppUpdateState(
    val visible: Boolean = false,
    val stage: UpdateStage = UpdateStage.CHECKING,
    val release: AppRelease? = null,
    val progress: Int = 0,
    val message: String? = null,
    val selectedAction: Int = 0,
) {
    val actions: List<String> get() = when (stage) {
        UpdateStage.AVAILABLE -> listOf("Uuenda", "Hiljem")
        UpdateStage.PERMISSION -> listOf("Luba paigaldamine", "Hiljem")
        UpdateStage.READY -> listOf("Paigalda", "Hiljem")
        UpdateStage.ERROR -> listOf("Proovi uuesti", "Sulge")
        UpdateStage.CHECKING, UpdateStage.DOWNLOADING -> listOf("Katkesta")
        UpdateStage.CURRENT, UpdateStage.INSTALLING -> listOf("Sulge")
    }
}

sealed interface UpdateAction {
    data class Install(val file: File) : UpdateAction
    data object AllowInstalls : UpdateAction
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updates = GitHubUpdates()
    private val preferences = application.getSharedPreferences("app_updates", 0)
    private val directory = File(application.cacheDir, "updates")
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private val events = Channel<UpdateAction>(Channel.BUFFERED)
    val actions = events.receiveAsFlow()
    private var job: Job? = null
    private var manualCheck = false
    private var downloaded: File? = null
    private val consumedKeys = mutableSetOf<Int>()

    fun checkAutomatically() {
        if (BuildConfig.DEMO_MODE || mutableState.value.visible || job?.isCompleted == false) return
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong("last_check", 0)
        if (now >= lastCheck && now - lastCheck < 24 * 60 * 60 * 1000L) return
        preferences.edit { putLong("last_check", now) }
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        manualCheck = manualCheck || manual
        if (job?.isCompleted == false) {
            if (manual) mutableState.update { it.copy(visible = true) }
            return
        }
        downloaded?.delete()
        downloaded = null
        mutableState.value = AppUpdateState(visible = manual)
        job = viewModelScope.launch {
            try {
                val release = updates.latest(BuildConfig.VERSION_NAME)
                mutableState.value = AppUpdateState(
                    visible = manualCheck || release != null,
                    stage = if (release == null) UpdateStage.CURRENT else UpdateStage.AVAILABLE,
                    release = release,
                    // A remote OK press intended for TV must not accidentally start an update.
                    selectedAction = if (release != null && !manualCheck) 1 else 0,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (manualCheck) fail(error.message ?: "Uuenduste kontroll ebaõnnestus.")
            } finally {
                manualCheck = false
            }
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
        val current = mutableState.value
        if (!current.visible) return false
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) return false
        if (event.action == KeyEvent.ACTION_DOWN) consumedKeys += event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> dismiss()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.update {
                    it.copy(selectedAction = (it.selectedAction + 1) % it.actions.size)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> activate(current.selectedAction)
            }
        }
        return true
    }

    fun activate(index: Int) {
        val current = mutableState.value
        if (index > 0) return dismiss()
        when (current.stage) {
            UpdateStage.AVAILABLE -> download()
            UpdateStage.PERMISSION -> events.trySend(UpdateAction.AllowInstalls)
            UpdateStage.READY -> requestInstall()
            UpdateStage.ERROR -> if (downloaded?.isFile == true) requestInstall() else check()
            else -> dismiss()
        }
    }

    fun dismiss() {
        job?.cancel()
        // A package installer may still be reading the URI; keep its file until next check.
        if (mutableState.value.stage != UpdateStage.INSTALLING) {
            downloaded?.delete()
            downloaded = null
        }
        mutableState.update { it.copy(visible = false) }
    }

    private fun download() {
        val release = mutableState.value.release ?: return
        if (job?.isCompleted == false) return
        mutableState.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = 0, selectedAction = 0, message = null) }
        job = viewModelScope.launch {
            try {
                val file = updates.download(release, directory) { progress ->
                    mutableState.update { it.copy(progress = progress) }
                }
                withContext(Dispatchers.IO) { verifyUpdateApk(getApplication(), file, release) }
                downloaded = file
                requestInstall()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                File(directory, "update.apk").delete()
                fail(error.message ?: "Uuenduse allalaadimine ebaõnnestus.")
            }
        }
    }

    private fun requestInstall() {
        val file = downloaded?.takeIf { it.isFile } ?: return check()
        mutableState.update { it.copy(stage = UpdateStage.READY, selectedAction = 0, message = null) }
        events.trySend(UpdateAction.Install(file))
    }

    fun needsPermission() {
        mutableState.update { it.copy(stage = UpdateStage.PERMISSION, selectedAction = 0) }
    }

    fun permissionResult(allowed: Boolean) {
        if (mutableState.value.stage == UpdateStage.PERMISSION && downloaded?.isFile == true) {
            if (allowed) requestInstall()
        }
    }

    fun installing() {
        mutableState.update { it.copy(stage = UpdateStage.INSTALLING, selectedAction = 0) }
    }

    fun installerReturned() {
        if (mutableState.value.stage == UpdateStage.INSTALLING) {
            mutableState.update { it.copy(stage = UpdateStage.READY, selectedAction = 0, message = "Paigaldamist ei lõpetatud. Saad uuesti proovida või jätkata vaatamist.") }
        }
    }

    fun fail(message: String) {
        mutableState.update { it.copy(visible = true, stage = UpdateStage.ERROR, message = message, selectedAction = 0) }
    }
}
