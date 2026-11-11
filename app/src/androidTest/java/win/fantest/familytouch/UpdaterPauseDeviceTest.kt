package win.fantest.familytouch

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Isolated QA package only: exercises pause persistence without real downloads. */
@RunWith(AndroidJUnit4::class)
class UpdaterPauseDeviceTest {
    @Test fun pauseKeepsCompletedChunksAndCancelsOnlyItsOwnJob() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("win.fantest.familytouch.qa", context.packageName)
        val version = 1_000_000L + Random.nextInt(100_000)
        val update = AvailableFamilyUpdate(version, "qa-pause-fixture", 4096L,
            "a".repeat(64), "https://family-updates.fantest.win/apks/qa-test-only.apk")
        val name = FamilyUpdates.workName(version)
        val prefs = context.getSharedPreferences("family_update_controls", Context.MODE_PRIVATE)
        val previousManifest = prefs.getString("saved_update", null)
        val previousPause = prefs.getLong("paused_code", -1L)
        val partial = FamilyUpdates.partialFile(context, update)
        assertFalse("Test fixture must not replace an existing partial", partial.exists())
        val wm = WorkManager.getInstance(context.applicationContext)
        val delayed = OneTimeWorkRequestBuilder<FamilyUpdateWorker>()
            .setInitialDelay(1, TimeUnit.DAYS).build()
        try {
            partial.writeBytes(ByteArray(32) { 7 })
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, delayed)
                .result.get(15, TimeUnit.SECONDS)
            FamilyUpdates.pause(context, update)
            wm.cancelUniqueWork(name).result.get(15, TimeUnit.SECONDS)
            assertTrue(FamilyUpdates.isPaused(context, version))
            assertEquals(version, FamilyUpdates.savedUpdate(context)?.code)
            assertEquals(32L, partial.length())
            assertEquals(WorkInfo.State.CANCELLED,
                wm.getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
                    .first { it.id == delayed.id }.state)
        } finally {
            wm.cancelUniqueWork(name).result.get(15, TimeUnit.SECONDS)
            partial.delete()
            val restore = prefs.edit()
            if (previousManifest == null) restore.remove("saved_update")
            else restore.putString("saved_update", previousManifest)
            if (previousPause == -1L) restore.remove("paused_code")
            else restore.putLong("paused_code", previousPause)
            restore.commit()
            context.getSystemService(NotificationManager::class.java)?.cancel(6404)
        }
    }
}
