package ee.local.go3tvplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import ee.local.go3tvplus.ui.Go3TvApp
import ee.local.go3tvplus.ui.TvViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TvViewModel by viewModels {
        TvViewModel.Factory((application as TvApplication).container, applicationContext)
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) viewModel.onAppBackgrounded()
        }
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
            Go3TvApp(viewModel, viewModel.mediaPlayer)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.handleKey(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.handleKey(event) || super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
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
