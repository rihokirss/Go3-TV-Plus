package ee.local.go3tvplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import ee.local.go3tvplus.ui.AppUpdateOverlay
import ee.local.go3tvplus.ui.Go3TvApp
import ee.local.go3tvplus.ui.TvViewModel
import ee.local.go3tvplus.update.AppUpdateViewModel
import ee.local.go3tvplus.update.UpdateAction
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val updates: AppUpdateViewModel by viewModels()
    private val viewModel: TvViewModel by viewModels {
        TvViewModel.Factory((application as TvApplication).container, applicationContext)
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) viewModel.onAppBackgrounded()
        }
    }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.permissionResult(packageManager.canRequestPackageInstalls())
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.installerReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_EXPORTED,
        )
        setContent {
            val updateState by updates.state.collectAsState()
            Box {
                Go3TvApp(viewModel, viewModel.mediaPlayer)
                MaterialTheme { AppUpdateOverlay(updateState, updates::activate) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.appUpdateRequests.collect { updates.check() } }
                launch {
                    updates.actions.collect { action ->
                        when (action) {
                            is UpdateAction.Install -> installUpdate(action.file)
                            UpdateAction.AllowInstalls -> allowUpdateInstalls()
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return updates.handleKey(event) || viewModel.handleKey(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return updates.handleKey(event) || viewModel.handleKey(event) || super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
        updates.checkAutomatically()
    }

    private fun allowUpdateInstalls() {
        try {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        } catch (_: Exception) {
            updates.fail("Paigaldamise luba ei õnnestunud avada. Ava Androidi seadetes rakenduste erijuurdepääs ja luba Go3 Airile tundmatute rakenduste paigaldamine.")
        }
    }

    private fun installUpdate(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            updates.needsPermission()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            // Close Go3's server session before replacing the app; installation kills this process.
            viewModel.onAppBackgrounded()
            updates.installing()
            installer.launch(intent)
        } catch (_: Exception) {
            updates.fail("Androidi paigaldusakent ei õnnestunud avada. Palun proovi uuesti.")
            viewModel.onAppForegrounded()
        }
    }

    // Stop only once hidden: system dialogs over the video and the button redirect's
    // re-launch merely pause the activity, which used to drop and re-tune the stream.
    override fun onStop() {
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}
