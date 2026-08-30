package ee.local.go3tvplus.tclredirect

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

class RedirectJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!AdbRedirectManager(this).isConfigured) return false

        executor.execute {
            val shouldRetry = runCatching {
                AdbRedirectManager(this).ensureRedirect(12)
            }.isFailure
            jobFinished(params, shouldRetry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
