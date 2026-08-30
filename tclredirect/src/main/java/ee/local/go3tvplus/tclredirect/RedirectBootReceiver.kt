package ee.local.go3tvplus.tclredirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RedirectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in supportedActions && AdbRedirectManager(context).isConfigured) {
            RedirectScheduler.schedule(context)
        }
    }

    private companion object {
        val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
