package ee.local.go3tvplus.tclredirect

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object RedirectScheduler {
    private const val IMMEDIATE_JOB_ID = 0x473301
    private const val PERIODIC_JOB_ID = 0x473302
    private const val MINUTE_MS = 60_000L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, RedirectJobService::class.java)

        scheduler.schedule(
            JobInfo.Builder(IMMEDIATE_JOB_ID, component)
                .setMinimumLatency(1_000)
                .setOverrideDeadline(10_000)
                .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build(),
        )

        if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_JOB_ID, component)
                    .setPeriodic(15 * MINUTE_MS)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(IMMEDIATE_JOB_ID)
        scheduler.cancel(PERIODIC_JOB_ID)
    }
}
