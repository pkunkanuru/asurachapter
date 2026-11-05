package app.chapteralert

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

const val CHECK_MINUTES = 5L

class CheckWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        try { Tracker.check(applicationContext) }
        finally { schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE) }
        return Result.success()
    }

    companion object {
        // WorkManager's periodic jobs can't run more often than 15 min, so each run queues the next one.
        fun schedule(c: Context, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>()
                .setInitialDelay(CHECK_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork("check", policy, req)
        }
    }
}
