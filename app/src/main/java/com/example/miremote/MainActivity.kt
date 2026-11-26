package com.example.miremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

object Key {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val POWER = 26; const val PLAY_PAUSE = 85
    const val ASSISTANT = 231; const val MENU = 82
}

interface TvConnection {
    suspend fun connect(ip: String): Boolean
    suspend fun submitPairingCode(code: String): Boolean
    fun sendKey(keyCode: Int)
}

class StubConnection : TvConnection {
    override suspend fun connect(ip: String) = true
    override suspend fun submitPairingCode(code: String) = true
    override fun sendKey(keyCode: Int) { println("send key $keyCode") }
}

class MainActivity : ComponentActivity() {
    private val tv: TvConnection by lazy { RealConnection(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { RemoteScreen(tv) } }
    }
}

@Composable
fun RemoteScreen(tv: TvConnection) {
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Mouch metconnecti") }
    var showConn by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0D)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status, color = Color(0xFF9E9E9E), fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showConn = !showConn }) {
                    Text(if (showConn) "Khabbi" else "Connexion")
                }
            }

            if (showConn) {
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("IP mta3 Mi Box") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    scope.launch {
                        status = "Nconnecti..."
                        status = if (tv.connect(ip)) "Aktheb el code elli fil TV" else "Echec"
                    }
                }) { Text("Connecti") }
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Code pairing") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    scope.launch {
                        val ok = tv.submitPairingCode(code)
                        status = if (ok) "Connecté ✔" else "Code ghalet"
                        if (ok) showConn = false
                    }
                }) { Text("Valider") }
            }

            // ----- Télécommande -----
            Column(
                Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF161618))
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                RoundKey("⏻", 48.dp, textColor = Color(0xFFFF8A00)) { tv.sendKey(Key.POWER) }

                Box(
                    Modifier
                        .size(48.dp).clip(CircleShape)
                        .background(Color(0xFF2C2C2E))
                        .clickable { tv.sendKey(Key.ASSISTANT) },
                    contentAlignment = Alignment.Center
                ) {
                    Row {
                        listOf(0xFF4285F4, 0xFFEA4335, 0xFFFBBC05, 0xFF34A853).forEach {
                            Text("●", color = Color(it), fontSize = 9.sp)
                        }
                    }
                }

                DPad(tv)

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoundKey("mi", 48.dp) { tv.sendKey(Key.MENU) }
                    RoundKey("←", 48.dp) { tv.sendKey(Key.BACK) }
                    RoundKey("○", 48.dp) { tv.sendKey(Key.HOME) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillKey("NETFLIX", Color(0xFFE50914)) { }
                    PillKey("prime video", Color(0xFF4FA3FF)) { }
                }

                Column(
                    Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF2C2C2E)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(60.dp).clickable { tv.sendKey(Key.VOL_UP) },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = Color.White, fontSize = 24.sp) }
                    Box(
                        Modifier.fillMaxWidth().height(60.dp).clickable { tv.sendKey(Key.VOL_DOWN) },
                        contentAlignment = Alignment.Center
                    ) { Text("−", color = Color.White, fontSize = 24.sp) }
                }
            }
        }
    }
}

@Composable
fun DPad(tv: TvConnection) {
    Box(
        Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(Color(0xFF2C2C2E))
    ) {
        DPadArrow("▲", Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { tv.sendKey(Key.UP) }
        DPadArrow("▼", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) { tv.sendKey(Key.DOWN) }
        DPadArrow("◄", Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) { tv.sendKey(Key.LEFT) }
        DPadArrow("►", Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { tv.sendKey(Key.RIGHT) }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(88.dp)
                .clip(CircleShape)
                .background(Color(0xFF1C1C1E))
                .clickable { tv.sendKey(Key.OK) }
        )
    }
}

@Composable
fun DPadArrow(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFBBBBBB), fontSize = 18.sp) }
}

@Composable
fun RoundKey(
    label: String,
    size: Dp,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF2C2C2E)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = textColor, fontSize = 20.sp) }
}

@Composable
fun PillKey(label: String, textColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(112.dp).height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xFF2C2C2E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
