package win.fantest.familytouch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Nonproduction QA-package-only WorkManager resilience verification.
 * Downloads an older signed APK from the same origin for transfer/resume inspection.
 * This test intentionally leaves the QA job running for subsequent ADB checks.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundUpdateDeviceTest {
    private val code = 33L
    private val sha = "a17f668c866042ae117fec5d060d7fbbfaba85a605d84689392738ab48bbcd62"

    @Test fun enqueueAuthenticatedDownloadForBackgroundInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Run only in separate QA package", context.packageName == "win.fantest.familytouch.qa")
        val update = AvailableFamilyUpdate(
            code, "0.3.16-qa-transfer-probe", 146031235L, sha,
            "https://family-updates.fantest.win/apks/embedded.apk"
        )
        FamilyUpdates.enqueue(context, update)
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FamilyUpdates.workName(code))
            .get(12, TimeUnit.SECONDS)
        assertTrue("Background WorkManager job must be persisted", work.isNotEmpty())
        println("FT_QA_BG_WORK scheduled state=" + work.first().state + " id=" + work.first().id)
    }

    @Test fun pinLockKeepsWorkScheduledInIsolatedQaPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("QA only", context.packageName == "win.fantest.familytouch.qa")
        org.junit.Assume.assumeFalse("Do not change an existing QA PIN", FamilyPin.enabled(context))
        val manager = WorkManager.getInstance(context)
        val before = manager.getWorkInfosForUniqueWork(FamilyUpdates.workName(code))
            .get(12, TimeUnit.SECONDS).firstOrNull()
        assertTrue("A QA update must already be queued", before != null)
        val ephemeralPin = java.security.SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        FamilyPin.set(context, ephemeralPin)
        try {
            FamilyPin.lock()
            assertTrue("QA PIN enabled", FamilyPin.enabled(context))
            assertTrue("QA screen locked", !FamilyPin.unlocked)
            val after = manager.getWorkInfosForUniqueWork(FamilyUpdates.workName(code))
                .get(12, TimeUnit.SECONDS).firstOrNull()
            assertTrue("PIN lock must preserve queued WorkManager job", before?.id == after?.id)
            assertTrue("PIN lock must not cancel job",
                after?.state != androidx.work.WorkInfo.State.CANCELLED)
            println("FT_QA_PIN_LOCK did not change work id or cancel job")
        } finally {
            assertTrue("Restore QA PIN state", FamilyPin.disable(context, ephemeralPin))
        }
    }

    @Test fun rejectsWrongSignerPermanentlyInQa() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("QA package only", context.packageName == "win.fantest.familytouch.qa")
        val update = AvailableFamilyUpdate(
            code, "qa-only", 146031235L, sha,
            "https://family-updates.fantest.win/apks/embedded.apk"
        )
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FamilyUpdates.workName(code))
            .get(12, TimeUnit.SECONDS)
        assertTrue("QA job must exist", infos.isNotEmpty())
        println("FT_QA_BAD_SIGNER state=" + infos.first().state)
        org.junit.Assert.assertEquals(
            "A complete APK signed for Embedded must fail in separate QA package",
            androidx.work.WorkInfo.State.FAILED, infos.first().state
        )
        assertTrue("Rejected APK must be deleted",
            !FamilyUpdates.partialFile(context, update).exists())
    }

    @Test fun cleanUpOnlyQaProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName == "win.fantest.familytouch.qa")
        WorkManager.getInstance(context).cancelUniqueWork(FamilyUpdates.workName(code))
            .result.get(12, TimeUnit.SECONDS)
        val update = AvailableFamilyUpdate(
            code, "qa-only", 146031235L, sha,
            "https://family-updates.fantest.win/apks/embedded.apk"
        )
        FamilyUpdates.partialFile(context, update).delete()
        FamilyUpdates.targetFile(context, update).delete()
        println("FT_QA_BG_WORK cleaned test artifacts")
    }
}
