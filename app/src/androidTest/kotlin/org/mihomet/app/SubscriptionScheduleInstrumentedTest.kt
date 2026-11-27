package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.data.subs.SubscriptionRefreshWorker
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SubscriptionScheduleInstrumentedTest {
    @Test
    fun existingSixHourWorkIsUpdatedWithoutChangingItsIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val old = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniquePeriodicWork(SubscriptionRefreshWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE, old).result.get(10, TimeUnit.SECONDS)
        SubscriptionRefreshWorker.schedulePeriodicRefresh(context)
        // Reading work infos is serialized after the enqueued UPDATE transaction.
        val info = manager.getWorkInfosForUniqueWork(SubscriptionRefreshWorker.UNIQUE_WORK_NAME)
            .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        assertEquals(old.id, info.id)
        assertEquals(TimeUnit.HOURS.toMillis(1), info.periodicityInfo!!.repeatIntervalMillis)
    }
}
