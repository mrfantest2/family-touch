package win.fantest.familytouch

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Locale

internal class FamilyMapBridge(private val callback: (Double, Double) -> Unit) {
    /** Only passes bounded coordinates; does not expose privileged device controls. */
    @JavascriptInterface
    fun selected(lat: Double, lon: Double) {
        if (LiveLocationProtocol.validCoordinates(lat, lon)) callback(lat, lon)
    }
}

/** User must tap Send/Start after selecting a place; moving the pin never broadcasts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationPinPicker(
    initialLat: Double, initialLon: Double, contact: String, direct: Boolean,
    onDismiss: () -> Unit, onSendPin: (Double,Double) -> Unit,
    onStartLive: (Long) -> Unit
) {
    val context = LocalContext.current
    var lat by remember(initialLat) { mutableDoubleStateOf(initialLat) }
    var lon by remember(initialLon) { mutableDoubleStateOf(initialLon) }
    var mode by remember { mutableStateOf("pin") }
    var minutes by remember { mutableLongStateOf(15L) }
    val html = remember {
        context.assets.open("family_map_picker.html").bufferedReader().use { it.readText() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = Color(0xFF202C33)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Share location with " + contact, fontWeight = FontWeight.SemiBold)
            Text("Tap map or drag pin. Nothing is sent until you confirm.",
                color = Color(0xFFC4D2D5), fontSize = 12.sp)
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " FamilyTouch/0.3"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?,
                            request: android.webkit.WebResourceRequest?): Boolean = true
                    }
                    addJavascriptInterface(FamilyMapBridge { a,b -> post { lat=a;lon=b } },
                        "FamilyMapBridge")
                    loadDataWithBaseURL("https://appassets.androidplatform.net/",
                        html.replace("__LAT__", String.format(Locale.US,"%.6f",initialLat))
                            .replace("__LON__",String.format(Locale.US,"%.6f",initialLon)),
                        "text/html","UTF-8",null)
                }
            },modifier = Modifier.fillMaxWidth().height(255.dp),
                onRelease = { it.destroy() })
            Text(String.format(Locale.US,"Selected: %.5f, %.5f", lat,lon),
                color = Color(0xFFC4D2D5),fontSize = 12.sp)
            Text("Online tiles: © OpenStreetMap contributors. Viewing tiles reveals the map area to the provider.",
                color = Color(0xFFADC5C7),fontSize = 11.sp)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected=mode=="pin",onClick={mode="pin"},
                    shape=SegmentedButtonDefaults.itemShape(0,2)) { Text("Send pin") }
                SegmentedButton(selected=mode=="live",onClick={mode="live"},enabled=direct,
                    shape=SegmentedButtonDefaults.itemShape(1,2)) { Text("Live location") }
            }
            if(mode=="live") {
                Text("Live sharing uses your actual device location, not the selected pin.",
                    fontSize=12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(7.dp)){
                    for ((count,label) in listOf(15L to "15 min",60L to "1 hour",480L to "8 hours")){
                        FilterChip(selected=minutes==count,onClick={minutes=count},
                            label={Text(label)})
                    }
                }
                Text("A persistent Stop notification stays visible. Sharing ends automatically.",
                    color=Color(0xFFF0D4AE),fontSize=12.sp)
            }
            Button(modifier=Modifier.fillMaxWidth(),onClick={
                if(mode=="pin") onSendPin(lat,lon)
                else onStartLive(minutes*LiveLocationProtocol.MINUTE_MS)
            }){Text(if(mode=="pin")"Send selected pin" else "Start live sharing")}
            TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text("Cancel")}
        }
    }
}

@Composable
internal fun StreetMapPreview(latitude: Double, longitude: Double) {
    if (!LiveLocationProtocol.validCoordinates(latitude, longitude)) return
    val url = remember(latitude,longitude){
        val bbox=String.format(Locale.US,"%.6f,%.6f,%.6f,%.6f",
            longitude-.005, latitude-.004, longitude+.005, latitude+.004)
        val marker=String.format(Locale.US,"%.6f,%.6f",latitude,longitude)
        "https://www.openstreetmap.org/export/embed.html?bbox="+
            Uri.encode(bbox)+"&layer=mapnik&marker="+Uri.encode(marker)
    }
    AndroidView(factory={ctx->
        WebView(ctx).apply{
            // OSM embed relies on JavaScript to display real map tiles.
            // No JavaScript bridge is installed on the external preview.
            settings.javaScriptEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode=WebSettings.LOAD_DEFAULT
            settings.userAgentString=settings.userAgentString+" FamilyTouch/0.3"
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView?,
                    request:android.webkit.WebResourceRequest?):Boolean=request?.isForMainFrame == true
            }
            loadUrl(url)
        }
    }, modifier=Modifier.fillMaxWidth().height(125.dp),
       update={view->if(view.url!=url)view.loadUrl(url)},
       onRelease={it.destroy()})
}

@Composable
internal fun LiveLocationBubble(fix: LiveLocationFix, mine:Boolean, onStop:()->Unit) {
    val context=LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fix.sessionId,fix.ended){
        while(LiveLocationProtocol.remainingMs(fix,clock)>0){
            delay(10_000);clock=System.currentTimeMillis()
        }
    }
    val active=LiveLocationProtocol.remainingMs(fix,clock)>0
    val mins=(LiveLocationProtocol.remainingMs(fix,clock)/60_000).coerceAtLeast(0)
    Column(Modifier.widthIn(max=270.dp).background(Color(0xFF233E3B),
        RoundedCornerShape(10.dp)).padding(5.dp)){
        Text(if(active)"Live location" else "Live location ended",
            color=Color(0xFF8FFFD5),fontWeight=FontWeight.Medium,fontSize=13.sp)
        StreetMapPreview(fix.latitude,fix.longitude)
        Text(if(active)"Active · approximately "+mins+" min remaining" else "Last shared location",
            color=Color(0xFFDEE8E5),fontSize=11.sp)
        Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={
                try{
                    val link=String.format(Locale.US,
                        "https://www.google.com/maps?q=%.6f,%.6f",fix.latitude,fix.longitude)
                    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))
                } catch(_:Exception){FamilyStore.lastError="No maps app available"}
            }){Text("Open in Maps")}
            Spacer(Modifier.weight(1f))
            if(mine&&active&&LiveLocationSharing.active?.sessionId==fix.sessionId)
                TextButton(onClick=onStop){Text("Stop",color=Color(0xFFFFB8B8))}
        }
    }
}
