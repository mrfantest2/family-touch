package win.fantest.familytouch

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Independent, isolated QA-package updater checks. These tests deliberately
 * schedule delayed work rather than downloading from the live update service.
 * Every test owns and cleans up only its uniquely named QA WorkManager job.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundUpdateDeviceTest {
    private fun qaContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Only the isolated QA package may be tested", "win.fantest.familytouch.qa", context.packageName)
        return context
    }

    private fun scheduleDelayed(context: Context, name: String): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<FamilyUpdateWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
            .result.get(12, TimeUnit.SECONDS)
        return request
    }

    private fun state(context: Context, name: String, id: UUID): WorkInfo.State? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name)
            .get(12, TimeUnit.SECONDS).firstOrNull { it.id == id }?.state

    private fun cancel(context: Context, name: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name).result.get(12, TimeUnit.SECONDS)
    }

    @Test fun scheduledBackgroundWorkPersistsUntilExplicitlyCancelled() {
        val context = qaContext()
        val name = "ft-qa-delay-" + UUID.randomUUID()
        try {
            val request = scheduleDelayed(context, name)
            assertEquals(WorkInfo.State.ENQUEUED, state(context, name, request.id))
        } finally {
            cancel(context, name)
        }
    }

    @Test fun pinLockDoesNotCancelIndependentScheduledWork() {
        val context = qaContext()
        assumeFalse("Preserve any existing QA PIN", FamilyPin.enabled(context))
        val name = "ft-qa-pin-" + UUID.randomUUID()
        val request = scheduleDelayed(context, name)
        val pin = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        var setPin = false
        try {
            assertEquals(WorkInfo.State.ENQUEUED, state(context, name, request.id))
            FamilyPin.set(context, pin)
            setPin = true
            FamilyPin.lock()
            assertTrue(FamilyPin.enabled(context))
            assertTrue(!FamilyPin.unlocked)
            assertEquals("PIN lock must not cancel queued updater work",
                WorkInfo.State.ENQUEUED, state(context, name, request.id))
        } finally {
            if (setPin) assertTrue("Restore original QA PIN state", FamilyPin.disable(context, pin))
            cancel(context, name)
        }
    }

    @Test fun localApkValidationAcceptsMatchingSignerAndRejectsBadVersionOrArchive() {
        val context = qaContext()
        val ownApk = File(context.applicationInfo.sourceDir)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertTrue(ownApk.isFile)
        FamilyUpdates.verifyPackageAndSigner(context, ownApk, version)
        assertTrue("Different version must fail",
            runCatching { FamilyUpdates.verifyPackageAndSigner(context, ownApk, version + 1) }
                .exceptionOrNull() is IOException)

        val corrupt = File(context.cacheDir, "ft-qa-not-an-apk-" + UUID.randomUUID() + ".apk")
        try {
            corrupt.writeText("Invalid APK fixture; contains no package or signing certificate.")
            assertTrue("Malformed archive must fail",
                runCatching { FamilyUpdates.verifyPackageAndSigner(context, corrupt, version) }
                    .exceptionOrNull() is IOException)
        } finally {
            corrupt.delete()
        }
    }

    @Test fun rejectsValidSamePackageApkSignedByDifferentCertificate() {
        val context = qaContext()
        val fixture = File(context.filesDir, "qa-foreign-signer.apk")
        assumeTrue("Host must stage an independently signed QA APK fixture", fixture.isFile)
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            val archive = context.packageManager.getPackageArchiveInfo(fixture.absolutePath, flags)
            assertNotNull("Foreign-signed test fixture must be a parsable APK", archive)
            assertEquals("Fixture must use the same package name", installed.packageName, archive!!.packageName)
            assertEquals("Fixture must use the same version", installed.longVersionCode, archive.longVersionCode)
            val installedCert = installed.signingInfo!!.apkContentsSigners.first().toByteArray()
            val foreignCert = archive.signingInfo!!.apkContentsSigners.first().toByteArray()
            assertFalse("Test fixture must have an independent signer", installedCert.contentEquals(foreignCert))
            assertTrue("Correct package and version with an untrusted signer must be rejected",
                runCatching {
                    FamilyUpdates.verifyPackageAndSigner(context, fixture, installed.longVersionCode)
                }.exceptionOrNull() is IOException)
        } finally {
            fixture.delete()
        }
    }

    @Test fun cancellingOneQaWorkDoesNotCancelAnother() {
        val context = qaContext()
        val first = "ft-qa-a-" + UUID.randomUUID()
        val second = "ft-qa-b-" + UUID.randomUUID()
        try {
            val a = scheduleDelayed(context, first)
            val b = scheduleDelayed(context, second)
            assertNotNull(state(context, first, a.id))
            assertEquals(WorkInfo.State.ENQUEUED, state(context, second, b.id))
            cancel(context, first)
            assertEquals(WorkInfo.State.CANCELLED, state(context, first, a.id))
            assertEquals("Unrelated work must remain queued",
                WorkInfo.State.ENQUEUED, state(context, second, b.id))
        } finally {
            cancel(context, first)
            cancel(context, second)
        }
    }
}
