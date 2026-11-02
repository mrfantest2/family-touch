package win.fantest.familytouch

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Six digits, per-install salted PBKDF2, Keystore-encrypted preferences and persistent backoff. */
internal object FamilyPin {
    var unlocked by mutableStateOf(false)
        private set
    private fun presence(context: Context) = context.getSharedPreferences("pin_presence", Context.MODE_PRIVATE)
    fun enabled(context: Context) = presence(context).getBoolean("enabled",false)
    fun valid(pin: String): Boolean = pin.length == 6 && pin.all { it in '0'..'9' }
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "family_pin_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec=PBEKeySpec(pin.toCharArray(),salt,180000,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun set(context: Context,newPin:String) {
        require(valid(newPin))
        val salt=ByteArray(24).also { SecureRandom().nextBytes(it) }
        val p=prefs(context)
        p.edit().putString("salt",android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP))
            .putString("hash",android.util.Base64.encodeToString(hash(newPin,salt),android.util.Base64.NO_WRAP))
            .putInt("attempts",0).putLong("blockedUntil",0L).commit().also {
                check(it) { "Could not save PIN" }
            }
        check(presence(context).edit().putBoolean("enabled",true).commit())
        unlocked=true
    }
    fun verify(context: Context,pin:String): Boolean {
        if(!valid(pin))return false
        val p=prefs(context)
        val now=System.currentTimeMillis()
        if(now<p.getLong("blockedUntil",0L)) return false
        val salt=android.util.Base64.decode(p.getString("salt",""),android.util.Base64.DEFAULT)
        val expected=android.util.Base64.decode(p.getString("hash",""),android.util.Base64.DEFAULT)
        if(salt.size!=24||expected.size!=32)return false
        val match=MessageDigest.isEqual(hash(pin,salt),expected)
        val editor=p.edit()
        if(match)editor.putInt("attempts",0).putLong("blockedUntil",0L)
        else{
            val count=p.getInt("attempts",0)+1
            editor.putInt("attempts",count)
            if(count>=5)editor.putLong("blockedUntil",now+30000L)
        }
        check(editor.commit())
        if(match)unlocked=true
        return match
    }
    fun cooldown(context: Context): Long =
        ((prefs(context).getLong("blockedUntil",0L)-System.currentTimeMillis())/1000).coerceAtLeast(0L)
    fun disable(context:Context,currentPin:String):Boolean {
        if(!verify(context,currentPin))return false
        check(prefs(context).edit().clear().commit())
        check(presence(context).edit().putBoolean("enabled",false).commit())
        unlocked=true
        return true
    }
    fun lock(){unlocked=false}
}

@Composable
internal fun FamilyPinGate(content:@Composable ()->Unit){
    val ctx=androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    if(!FamilyPin.enabled(ctx)||FamilyPin.unlocked){
        SideEffect { FamilyStore.appVisible = true }
        content();return
    }
    SideEffect { FamilyStore.appVisible = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Family Touch", fontSize = 27.sp, color = androidx.compose.ui.graphics.Color(0xFFE9EDEF))
        Spacer(Modifier.height(8.dp))
        Text("Enter your 6-digit app PIN", color = androidx.compose.ui.graphics.Color(0xFF8696A0))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { index ->
                Box(Modifier.size(16.dp)
                    .background(if (index < pin.length)
                        androidx.compose.ui.graphics.Color(0xFF00A884)
                        else androidx.compose.ui.graphics.Color.Transparent,
                        androidx.compose.foundation.shape.CircleShape)
                    .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF00A884),
                        androidx.compose.foundation.shape.CircleShape))
            }
        }
        if (err.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            Text(err, color = androidx.compose.ui.graphics.Color(0xFFFFB4A9), fontSize = 12.sp)
        }
        Spacer(Modifier.height(26.dp))
        val keys = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    if (key.isBlank()) Spacer(Modifier.weight(1f).height(58.dp))
                    else OutlinedButton(
                        onClick = {
                            err = ""
                            if (key == "⌫") pin = pin.dropLast(1)
                            else if (pin.length < 6) pin += key
                        },
                        modifier = Modifier.weight(1f).height(58.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = androidx.compose.ui.graphics.Color(0xFFE9EDEF)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, androidx.compose.ui.graphics.Color(0xFF43565E)),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(key, fontSize = 23.sp) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(11.dp))
        Button(enabled = pin.length == 6, modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFF00A884),
                contentColor = androidx.compose.ui.graphics.Color(0xFF0B141A),
                disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF275748),
                disabledContentColor = androidx.compose.ui.graphics.Color(0xFFB3CCC2)),
            onClick = {
                try {
                    if (FamilyPin.verify(ctx, pin)) { err = ""; pin = "" }
                    else {
                        err = if (FamilyPin.cooldown(ctx) > 0)
                            "Too many attempts. Try again later." else "Incorrect PIN"
                        pin = ""
                    }
                } catch (_: Exception) { err = "Cannot verify PIN securely"; pin = "" }
            }) { Text("Unlock", fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
        Text("Five failed attempts trigger a temporary lockout.",
            fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFF8696A0))
    }
}

@Composable
internal fun FamilyPinSettings(){
    val ctx=androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(FamilyPin.enabled(ctx)) }
    var dialog by remember { mutableStateOf(false) }
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text("App security",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,fontSize=18.sp)
        Text(if(enabled)"Six-digit PIN enabled" else "Protect chats with an optional six-digit PIN",
            fontSize=13.sp)
        OutlinedButton(onClick={dialog=true;removing=false;old="";fresh="";confirm="";error=""}) {
            Text(if(enabled)"Change PIN" else "Set up six-digit PIN")
        }
        if(enabled)TextButton(onClick={dialog=true;removing=true;old="";fresh="";confirm="";error=""}){
            Text("Remove app PIN")
        }
    }
    if(dialog)AlertDialog(onDismissRequest={dialog=false},
        title={Text(if(removing)"Remove security PIN" else if(enabled)"Change security PIN" else "Create security PIN")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                @Composable fun Field(value:String, update:(String)->Unit, label:String){
                    OutlinedTextField(value,{update(it.filter{ch->ch.isDigit()}.take(6))},
                        label={Text(label)},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                        visualTransformation=PasswordVisualTransformation())
                }
                if(enabled)Field(old,{old=it},"Current PIN")
                if(!removing) {
                    Field(fresh,{fresh=it},"New six-digit PIN")
                    Field(confirm,{confirm=it},"Confirm new PIN")
                }
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton={TextButton(onClick={
            try {
                if(removing) {
                    if(FamilyPin.disable(ctx,old)){enabled=false;dialog=false}else error="Current PIN incorrect or temporarily locked"
                } else if(enabled&&!FamilyPin.verify(ctx,old)) error="Current PIN incorrect or temporarily locked"
                else if(!FamilyPin.valid(fresh)||fresh!=confirm)error="Enter two matching six-digit PINs"
                else{FamilyPin.set(ctx,fresh);enabled=true;dialog=false;old="";fresh="";confirm=""}
            }catch(_:Exception){error="Secure PIN storage unavailable"}
        }){Text(if(removing)"Remove" else "Save PIN")}},
        dismissButton={TextButton(onClick={dialog=false}){Text("Cancel")}})
}
