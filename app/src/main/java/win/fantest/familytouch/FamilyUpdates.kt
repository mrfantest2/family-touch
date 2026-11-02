package win.fantest.familytouch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class AvailableFamilyUpdate(
    val code: Long,
    val name: String,
    val bytes: Long,
    val checksum: String,
    val url: String,
    val mirrors: List<String> = emptyList()
)

/** Self-update checks are HTTPS only. Installer signatures must match the installed app. */
/** The update feed can be older than this APK. Reporting both versions is essential. */
internal data class FamilyUpdateCheck(
    val publishedCode: Long,
    val publishedName: String,
    val update: AvailableFamilyUpdate?
)

internal object FamilyVersionLabels {
    fun format(name: String, code: Long): String = "v${name.substringBefore('-')} (build $code)"
    fun status(installedCode: Long, publishedCode: Long): String = when {
        publishedCode > installedCode -> "A newer signed update is available."
        publishedCode == installedCode -> "Your version matches the latest published release."
        else -> "Your installed version is newer than the published update feed."
    }
}

internal object FamilyUpdates {
    var checkRequest by mutableIntStateOf(0)
        private set
    fun checkNow() { checkRequest++ }
    private val http = OkHttpClient.Builder().connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private const val HOST = "https://family-updates.fantest.win"
    internal fun isAllowedDownloadUrl(url: String): Boolean = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null) false
        else when (uri.host?.lowercase()) {
            "family-updates.fantest.win" -> uri.path.startsWith("/apks/") && uri.path.endsWith(".apk")
            "drive.google.com" -> uri.path == "/uc" || uri.path.startsWith("/file/d/")
            "drive.usercontent.google.com" -> uri.path == "/download"
            else -> uri.host?.lowercase()?.endsWith(".googleusercontent.com") == true
        }
    } catch (_: Exception) { false }

    fun workName(code: Long): String = "family-touch-update-" + code

    fun partialFile(context: Context, update: AvailableFamilyUpdate): File =
        File(File(context.filesDir, "updates").also { it.mkdirs() },
            "family-touch-" + update.code + "-" + update.checksum.take(12) + ".partial")

    fun targetFile(context: Context, update: AvailableFamilyUpdate): File =
        File(File(context.filesDir, "updates").also { it.mkdirs() },
            "family-touch-" + update.code + "-" + update.checksum.take(12) + ".apk")

    fun enqueue(context: Context, update: AvailableFamilyUpdate) {
        require(isAllowedDownloadUrl(update.url) && update.mirrors.all { isAllowedDownloadUrl(it) })
        val input = workDataOf(
            FamilyUpdateWorker.KEY_VERSION to update.code,
            FamilyUpdateWorker.KEY_NAME to update.name,
            FamilyUpdateWorker.KEY_SIZE to update.bytes,
            FamilyUpdateWorker.KEY_SHA to update.checksum,
            FamilyUpdateWorker.KEY_SOURCES to (listOf(update.url) + update.mirrors).toTypedArray()
        )
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<FamilyUpdateWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("family-touch-signed-updater")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(update.code), ExistingWorkPolicy.KEEP, request)
    }

    fun markReady(context: Context, update: AvailableFamilyUpdate) {
        context.getSharedPreferences("family_update_ready", Context.MODE_PRIVATE)
            .edit().putLong("code", update.code).putString("sha", update.checksum)
            .putLong("bytes", update.bytes).commit()
    }

    suspend fun verifiedReady(context: Context, update: AvailableFamilyUpdate): File? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("family_update_ready", Context.MODE_PRIVATE)
            if (prefs.getLong("code", -1L) != update.code ||
                prefs.getString("sha", "") != update.checksum ||
                prefs.getLong("bytes", -1L) != update.bytes) return@withContext null
            val file = targetFile(context, update)
            if (!file.isFile || file.length() != update.bytes) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != update.checksum) {
                file.delete()
                return@withContext null
            }
            try {
                verifyPackageAndSigner(context, file, update.code)
                file
            } catch (_: Exception) {
                file.delete()
                null
            }
        }

    private fun safeResponse(url: String, maxBytes: Int): ByteArray {
        if (!url.startsWith("$HOST/")) throw IOException("Untrusted update host")
        http.newCall(Request.Builder().url(url).header("Cache-Control","no-cache").build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("Update server HTTP " + response.code)
                val stream = response.body?.byteStream() ?: throw IOException("Missing update metadata")
                return stream.use { source ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > maxBytes) throw IOException("Manifest too large")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
    }
    suspend fun check(context: Context): FamilyUpdateCheck = withContext(Dispatchers.IO) {
        val metadata = String(safeResponse("$HOST/manifests/" + context.packageName + ".json", 4096))
        val j = JSONObject(metadata)
        if (j.getString("packageName") != context.packageName) throw IOException("Package mismatch")
        val code = j.getLong("versionCode")
        val publishedName = j.optString("versionName", "Unknown version")
        if (code <= BuildConfig.VERSION_CODE.toLong())
            return@withContext FamilyUpdateCheck(code, publishedName, null)
        val url = j.getString("apkUrl")
        if (!url.startsWith("$HOST/apks/") || !isAllowedDownloadUrl(url))
            throw IOException("Untrusted APK URL")
        val hash = j.getString("sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(hash)) throw IOException("Invalid update checksum")
        val bytes = j.getLong("bytes")
        if (bytes !in 1..250_000_000L) throw IOException("Invalid update size")
        val mirrors = mutableListOf<String>()
        val rawMirrors = j.optJSONArray("mirrorUrls")
        if (rawMirrors != null) {
            for (i in 0 until minOf(rawMirrors.length(), 3)) {
                val candidate = rawMirrors.optString(i)
                if (isAllowedDownloadUrl(candidate) && candidate != url && candidate !in mirrors) {
                    mirrors.add(candidate)
                }
            }
        }
        FamilyUpdateCheck(code, publishedName,
            AvailableFamilyUpdate(code, publishedName, bytes, hash, url, mirrors))
    }
    internal fun verifyPackageAndSigner(context: Context, file: File, version: Long) {
        signerDigest(context, file)
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("APK package invalid")
        if (packageInfo.longVersionCode != version) throw IOException("APK version mismatch")
    }

    private fun signerDigest(context: Context, file: File): String {
        val flags=PackageManager.GET_SIGNING_CERTIFICATES
        val pm=context.packageManager
        val current=pm.getPackageInfo(context.packageName,flags)
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags)
            ?: throw IOException("Invalid Android package")
        if (archive.packageName != context.packageName)
            throw IOException("This update belongs to another app")
        val oldSigners=current.signingInfo?.apkContentsSigners
            ?: throw IOException("Current signature missing")
        val newSigners=archive.signingInfo?.apkContentsSigners
            ?: throw IOException("New APK signature missing")
        val md=MessageDigest.getInstance("SHA-256")
        fun fingerprints(signers: Array<android.content.pm.Signature>) =
            signers.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }.toSet()
        if (fingerprints(oldSigners) != fingerprints(newSigners))
            throw IOException("Update signature does not match installed Family Touch")
        return fingerprints(newSigners).first()
    }
    fun install(context:Context,file:File) {
        if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
        val intent=Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) }
        catch(e:ActivityNotFoundException) { throw IOException("Android package installer not found",e) }
    }
    fun canInstall(context:Context):Boolean =
        Build.VERSION.SDK_INT<26 || context.packageManager.canRequestPackageInstalls()
}

@Composable
internal fun FamilyUpdatePrompt() {
    val context = LocalContext.current
    val trigger = FamilyUpdates.checkRequest
    var available by remember { mutableStateOf<AvailableFamilyUpdate?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var workInfo by remember { mutableStateOf<WorkInfo?>(null) }
    var error by remember { mutableStateOf("") }
    var showNoUpdate by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf<FamilyUpdateCheck?>(null) }
    val installed = FamilyVersionLabels.format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())

    LaunchedEffect(trigger) {
        if (trigger > 0) {
            showNoUpdate = false
            error = ""
            published = null
        }
        try {
            val check = FamilyUpdates.check(context)
            val candidate = check.update
            published = check
            available = candidate
            ready = if (candidate != null) FamilyUpdates.verifiedReady(context, candidate) else null
            if (trigger > 0 && candidate == null) showNoUpdate = true
        } catch (e: Exception) {
            if (trigger > 0) error = e.message ?: "Update check unavailable"
        }
    }
    DisposableEffect(available?.code, context) {
        val code = available?.code
        if (code == null) {
            workInfo = null
            onDispose { }
        } else {
            val live = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkLiveData(FamilyUpdates.workName(code))
            val observer = Observer<List<WorkInfo>> { infos ->
                workInfo = infos.firstOrNull()
            }
            live.observeForever(observer)
            onDispose { live.removeObserver(observer) }
        }
    }
    LaunchedEffect(workInfo?.state) {
        val candidate = available
        if (candidate != null && workInfo?.state == WorkInfo.State.SUCCEEDED) {
            ready = FamilyUpdates.verifiedReady(context, candidate)
        }
    }
    if (showNoUpdate) AlertDialog(onDismissRequest = { showNoUpdate = false },
        title = { Text("Family Touch update status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed version: $installed")
                published?.let { current ->
                    Text("Published release: " +
                        FamilyVersionLabels.format(current.publishedName, current.publishedCode))
                    Text(FamilyVersionLabels.status(BuildConfig.VERSION_CODE.toLong(), current.publishedCode))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showNoUpdate = false }) { Text("OK") } })
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" },
        title = { Text("Update check unavailable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed version: $installed")
                Text("Unable to check the published release: $error")
            }
        },
        confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
    available?.let { candidate ->
        val state = workInfo?.state
        val running = state == WorkInfo.State.RUNNING ||
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
        val pct = workInfo?.progress?.getInt(FamilyUpdateWorker.KEY_PROGRESS, 0) ?: 0
        AlertDialog(onDismissRequest = { available = null },
            title = { Text(if (ready != null) "Verified update ready" else "New Family Touch update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Installed version: $installed")
                    Text("Available update: " +
                        FamilyVersionLabels.format(candidate.name, candidate.code))
                    if (running) {
                        LinearProgressIndicator(progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("Background download: $pct%. Safe to leave or lock the app.")
                        val warning = workInfo?.progress?.getString(FamilyUpdateWorker.KEY_ERROR)
                        if (!warning.isNullOrBlank()) Text(warning)
                    } else if (ready != null) {
                        Text("Downloaded and signature-verified. Android will ask you to confirm installation.")
                        if (!FamilyUpdates.canInstall(context))
                            Text("Allow Family Touch to install unknown apps in Android settings, then tap Install.")
                    } else {
                        Text("Downloading continues outside Family Touch and while your PIN is locked. " +
                            "If your phone restarts or the network disconnects, it resumes later.")
                        if (candidate.mirrors.isNotEmpty()) Text("Backup source available: Google Drive.")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !running, onClick = {
                    if (ready != null) {
                        try { FamilyUpdates.install(context, ready!!) }
                        catch (e: Exception) { error = e.message ?: "Cannot open Android installer" }
                    } else {
                        try { FamilyUpdates.enqueue(context, candidate) }
                        catch (e: Exception) { error = e.message ?: "Cannot schedule update" }
                    }
                }) { Text(if (ready != null) "Install update" else if (running) "Downloading…" else "Download in background") }
            },
            dismissButton = {
                TextButton(onClick = { available = null }) { Text("Close") }
            })
    }
}
