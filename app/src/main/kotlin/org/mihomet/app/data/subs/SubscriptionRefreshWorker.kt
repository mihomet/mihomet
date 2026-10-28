package org.mihomet.app.data.subs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import org.mihomet.app.data.db.ProfileDao
import java.util.concurrent.TimeUnit

/**
 * Background refresh of remote subscriptions.
 *
 * Runs every [PERIODIC_INTERVAL_HOURS] hours; for each REMOTE profile whose
 * panel-declared `profile-update-interval` is at least
 * [SubscriptionRepository.MIN_AUTO_UPDATE_HOURS] and whose stored data is older
 * than that interval, re-downloads the subscription via
 * [SubscriptionRepository.refreshProfile]. Individual failures are logged and
 * never fail the whole run.
 *
 * Dependencies are resolved manually from Koin ([GlobalContext]) instead of
 * installing a CustomWorkerFactory: the default reflective factory instantiates
 * the worker with the standard `(Context, WorkerParameters)` constructor and the
 * body pulls what it needs. Koin is guaranteed to be started by then —
 * [MihometApplication.onCreate][org.mihomet.app.MihometApplication.onCreate]
 * starts it before scheduling this worker, and WorkManager only executes work
 * in a process that has gone through `Application.onCreate`.
 */
class SubscriptionRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dependencies = resolveDependencies()
        if (dependencies == null) {
            Log.w(TAG, "Koin not started or dependencies missing; retrying later")
            return Result.retry()
        }
        val (profileDao, repository) = dependencies

        val now = System.currentTimeMillis()
        val dueProfiles = profileDao.getAllProfilesOnce()
            .filter { SubscriptionRepository.isDueForRefresh(it, now) }
        if (dueProfiles.isEmpty()) {
            return Result.success()
        }

        Log.i(TAG, "Auto-refreshing ${dueProfiles.size} subscription(s)")
        for (profile in dueProfiles) {
            try {
                repository.refreshProfile(profile)
                    .onSuccess { Log.i(TAG, "Refreshed '${profile.name}'") }
                    .onFailure { e ->
                        Log.w(TAG, "Refresh failed for '${profile.name}': ${e.message}")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A single broken profile must not fail (or abort) the whole run.
                Log.w(TAG, "Refresh crashed for '${profile.name}': ${e.message}")
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SubsRefresh"

        /** Unique-work name and tag for the periodic refresh request. */
        const val UNIQUE_WORK_NAME = "subscription_refresh"
        const val WORK_TAG = "subscription_refresh"

        /** Wall-clock period of the scheduling pass. */
        const val PERIODIC_INTERVAL_HOURS = 6L

        /** Manual Koin resolution (avoids a CustomWorkerFactory); null when DI is unavailable. */
        internal fun resolveDependencies(): Pair<ProfileDao, SubscriptionRepository>? =
            runCatching {
                val koin = GlobalContext.get()
                koin.get<ProfileDao>() to koin.get<SubscriptionRepository>()
            }.getOrNull()

        /**
         * Idempotently schedules the periodic refresh (6 h, KEEP policy — an
         * already-enqueued identical periodic work is preserved).
         */
        fun schedulePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
                PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
