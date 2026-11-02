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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AvailableFamilyUpdate(
    val code: Long,
    val name: String,
    val bytes: Long,
    val checksum: String,
    val url: String
)

/** Self-update checks are HTTPS only. Installer signatures must match the installed app. */
internal object FamilyUpdates {
    var checkRequest by mutableIntStateOf(0)
        private set
    fun checkNow() { checkRequest++ }
    private val http = OkHttpClient.Builder().connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private const val HOST = "https://family-updates.fantest.win"
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
    suspend fun find(context: Context): AvailableFamilyUpdate? = withContext(Dispatchers.IO) {
        val metadata = String(safeResponse("$HOST/manifests/" + context.packageName + ".json", 4096))
        val j = JSONObject(metadata)
        if (j.getString("packageName") != context.packageName) throw IOException("Package mismatch")
        val code = j.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE.toLong()) return@withContext null
        val url = j.getString("apkUrl")
        if (!url.startsWith("$HOST/apks/") || !url.endsWith(".apk")) throw IOException("Untrusted APK URL")
        val hash = j.getString("sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(hash)) throw IOException("Invalid update checksum")
        val bytes = j.getLong("bytes")
        if (bytes !in 1..250_000_000L) throw IOException("Invalid update size")
        AvailableFamilyUpdate(code, j.optString("versionName", "Latest version"), bytes, hash, url)
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
    suspend fun download(context: Context, update: AvailableFamilyUpdate,
                         progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val folder=File(context.cacheDir,"updates").also{it.mkdirs()}
        val file=File(folder,"family-touch-update.apk")
        val part=File(folder,"family-touch-update.partial")
        if(part.exists())part.delete()
        val digest=MessageDigest.getInstance("SHA-256")
        var received=0L
        try {
            http.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download error HTTP " + response.code)
                val source=response.body?.byteStream() ?: throw IOException("No APK data")
                source.use { stream ->
                    part.outputStream().use { out ->
                        val buffer=ByteArray(65536)
                        while (true) {
                            val n=stream.read(buffer)
                            if(n<0)break
                            received+=n
                            if(received>update.bytes)throw IOException("Unexpected APK length")
                            out.write(buffer,0,n)
                            digest.update(buffer,0,n)
                            progress((received*100/update.bytes).toInt().coerceIn(0,100))
                        }
                    }
                }
            }
            val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            if(received!=update.bytes || hash!=update.checksum)throw IOException("APK checksum mismatch")
            if(file.exists())file.delete()
            if(!part.renameTo(file))throw IOException("Cannot store update")
            signerDigest(context,file)
            val info=context.packageManager.getPackageArchiveInfo(file.path,0)
                ?:throw IOException("APK package invalid")
            if(info.longVersionCode != update.code)throw IOException("APK version mismatch")
            file
        }catch(e:Exception){part.delete();file.delete();throw e}
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
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val trigger=FamilyUpdates.checkRequest
    var available by remember {mutableStateOf<AvailableFamilyUpdate?>(null)}
    var ready by remember {mutableStateOf<File?>(null)}
    var downloading by remember {mutableStateOf(false)}
    var progress by remember {mutableIntStateOf(0)}
    var error by remember {mutableStateOf("")}
    var showNoUpdate by remember {mutableStateOf(false)}
    LaunchedEffect(trigger) {
        try {
            val candidate=FamilyUpdates.find(context)
            available=candidate
            if(trigger>0 && candidate==null)showNoUpdate=true
        }catch(e:Exception) { if(trigger>0) error=e.message ?: "Update check unavailable" }
    }
    if (showNoUpdate) AlertDialog(onDismissRequest={showNoUpdate=false},
        title={Text("Family Touch is up to date")},
        text={Text("No newer signed update is available.")},
        confirmButton={TextButton(onClick={showNoUpdate=false}){Text("OK")}})
    if (error.isNotEmpty()) AlertDialog(onDismissRequest={error=""},
        title={Text("Update unavailable")},text={Text(error)},
        confirmButton={TextButton(onClick={error=""}){Text("OK")}})
    available?.let { update ->
        AlertDialog(onDismissRequest={if(!downloading)available=null},
            title={Text("New Family Touch update")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Version " + update.name + " is available. Update now?")
                if(downloading) {
                    LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                    Text("Downloading verified APK: " + progress + "%")
                }
                if(ready!=null && !FamilyUpdates.canInstall(context))
                    Text("Allow installation from Family Touch in Android settings, then tap Install.")
                Text("Android will ask you to confirm the installation.",style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(enabled=!downloading,onClick={
                if(ready!=null) {
                    try {FamilyUpdates.install(context,ready!!)}
                    catch(e:Exception){error=e.message?:"Cannot open installer"}
                }else {
                    downloading=true
                    scope.launch {
                        try {
                            ready=FamilyUpdates.download(context,update) { progress=it }
                            FamilyUpdates.install(context,ready!!)
                        } catch(e:Exception) { error=e.message?:"Download failed" }
                        finally { downloading=false }
                    }
                }
            }) {Text(if(ready!=null)"Install update" else if(downloading)"Downloading…" else "Update now")}},
            dismissButton={TextButton(enabled=!downloading,onClick={available=null}){Text("Later")}})
    }
}
