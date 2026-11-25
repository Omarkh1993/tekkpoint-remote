package com.example.miremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

object Key {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val POWER = 26; const val PLAY_PAUSE = 85
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
    private val tv: TvConnection = StubConnection()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RemoteScreen(tv) } }
    }
}

@Composable
fun RemoteScreen(tv: TvConnection) {
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Mouch metconnecti") }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(ip, { ip = it }, label = { Text("IP mta3 Mi Box") }, singleLine = true)
        Button(onClick = {
            scope.launch { status = if (tv.connect(ip)) "Aktheb el code elli fil TV" else "Echec" }
        }) { Text("Connecti") }
        OutlinedTextField(code, { code = it }, label = { Text("Code pairing") }, singleLine = true)
        Button(onClick = {
            scope.launch { status = if (tv.submitPairingCode(code)) "Connecté ✔" else "Code ghalet" }
        }) { Text("Valider") }
        Text(status)

        Spacer(Modifier.height(8.dp))
        RemoteButton("▲") { tv.sendKey(Key.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton("◀") { tv.sendKey(Key.LEFT) }
            RemoteButton("OK") { tv.sendKey(Key.OK) }
            RemoteButton("▶") { tv.sendKey(Key.RIGHT) }
        }
        RemoteButton("▼") { tv.sendKey(Key.DOWN) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton("Back") { tv.sendKey(Key.BACK) }
            RemoteButton("Home") { tv.sendKey(Key.HOME) }
            RemoteButton("⏯") { tv.sendKey(Key.PLAY_PAUSE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton("Vol-") { tv.sendKey(Key.VOL_DOWN) }
            RemoteButton("Mute") { tv.sendKey(Key.MUTE) }
            RemoteButton("Vol+") { tv.sendKey(Key.VOL_UP) }
        }
        RemoteButton("⏻ Power") { tv.sendKey(Key.POWER) }
    }
}

@Composable
fun RemoteButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 56.dp)) {
        Text(label)
    }
}
