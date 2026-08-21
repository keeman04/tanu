package com.mai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mai.app.data.ActionRecord
import com.mai.app.data.MaiDb
import com.mai.app.data.MeetingRecord
import com.mai.app.data.Participant
import com.mai.app.recording.RecordingBus
import com.mai.app.recording.RecordingService
import com.mai.app.recording.SpeechModelHolder
import com.mai.app.share.PdfShare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

private val BrandBlue = Color(0xFF2F6BFF)
private val BrandViolet = Color(0xFF7A4DFF)
private val BrandNavy = Color(0xFF0B1534)
private val SoftBlue = Color(0xFFEFF3FF)
private val DarkSurface = Color(0xFF121C3D)

private sealed interface Screen {
    data object Home : Screen
    data object Meetings : Screen
    data object Actions : Screen
    data object Settings : Screen
    data object NewMeeting : Screen
    data object Recording : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaiRoot() }
    }
}

@Composable
private fun MaiRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> systemDark }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF86A8FF), secondary = Color(0xFFA988FF), background = BrandNavy,
        surface = DarkSurface, onBackground = Color.White, onSurface = Color.White
    ) else lightColorScheme(
        primary = BrandBlue, secondary = BrandViolet, background = Color(0xFFF7F9FD),
        surface = Color.White, onBackground = BrandNavy, onSurface = BrandNavy
    )

    MaterialTheme(colorScheme = colors) {
        var splash by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(1900); splash = false }
        if (splash) LaunchScreen(dark) else PermissionGate(themeMode, onTheme = { themeMode = it })
    }
}

@Composable
private fun LaunchScreen(dark: Boolean) {
    val scale = remember { Animatable(0.65f) }
    val alpha = remember { Animatable(0f) }
    val ring = remember { Animatable(-30f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(700, easing = EaseOutCubic)) }
        launch { alpha.animateTo(1f, tween(650)) }
        ring.animateTo(0f, tween(900, easing = EaseOutCubic))
    }
    Box(Modifier.fillMaxSize().background(if (dark) BrandNavy else Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.mai_brand_mark), null,
                Modifier.size(116.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = ring.value; this.alpha = alpha.value }
            )
            Spacer(Modifier.height(14.dp))
            GradientText("MAI", 42.sp, FontWeight.Bold, Modifier.graphicsLayer { this.alpha = alpha.value })
            Text("Meeting Assistant Intelligence", style = MaterialTheme.typography.labelLarge, color = if (dark) Color(0xFFB8C1DD) else Color(0xFF6F7894))
        }
    }
}

@Composable
private fun PermissionGate(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    val required = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
    val granted = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val modelReady by SpeechModelHolder.ready.collectAsStateWithLifecycle()
    val modelError by SpeechModelHolder.error.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    LaunchedEffect(Unit) { SpeechModelHolder.ensure(context) }
    if (!granted) {
        PermissionScreen(
            onAllow = {
                val ask = required.toMutableList()
                if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(ask.toTypedArray())
            },
            onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        )
    } else if (!modelReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.mai_brand_mark), null, Modifier.size(72.dp))
                Spacer(Modifier.height(18.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(modelError ?: "Preparing MAI")
                if (modelError != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { SpeechModelHolder.ensure(context) }) { Text("Retry") }
                }
            }
        }
    } else {
        MaiApp(themeMode, onTheme)
    }
    permissionTick.hashCode()
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.mai_brand_mark), null, Modifier.size(86.dp))
            Spacer(Modifier.height(18.dp))
            GradientText("MAI", 36.sp, FontWeight.Bold)
            Spacer(Modifier.height(30.dp))
            PermissionRow(Icons.Default.Mic, "Microphone")
            PermissionRow(Icons.Default.People, "Contacts")
            Spacer(Modifier.height(26.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) { Text("Allow") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = BrandBlue) }
        Spacer(Modifier.width(14.dp)); Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f)); Text("Required", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
    }
}

@Composable
private fun MaiApp(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    val db = remember { MaiDb(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var refresh by remember { mutableIntStateOf(0) }
    val recording by RecordingBus.state.collectAsStateWithLifecycle()

    LaunchedEffect(recording.active, recording.status) {
        if (!recording.active && recording.status == "ready" && screen == Screen.Recording && recording.meetingId != null) {
            refresh++
            screen = Screen.Detail(recording.meetingId!!)
        }
    }

    val isRoot = screen == Screen.Home || screen == Screen.Meetings || screen == Screen.Actions || screen == Screen.Settings
    Scaffold(
        bottomBar = {
            if (isRoot) NavigationBar {
                NavigationBarItem(screen == Screen.Home, { screen = Screen.Home }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(screen == Screen.Meetings, { screen = Screen.Meetings }, { Icon(Icons.Default.Description, null) }, label = { Text("Meetings") })
                NavigationBarItem(screen == Screen.Actions, { screen = Screen.Actions }, { Icon(Icons.Default.ListAlt, null) }, label = { Text("Actions") })
                NavigationBarItem(screen == Screen.Settings, { screen = Screen.Settings }, { Icon(SettingsIcon, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(db, refresh, recording.active, onNew = { screen = Screen.NewMeeting }, onMeeting = { screen = Screen.Detail(it) }, onResume = { screen = Screen.Recording })
                Screen.Meetings -> MeetingsScreen(db, refresh, onMeeting = { screen = Screen.Detail(it) })
                Screen.Actions -> ActionsScreen(db, refresh)
                Screen.Settings -> SettingsScreen(themeMode, onTheme)
                Screen.NewMeeting -> NewMeetingScreen(db, onBack = { screen = Screen.Home }, onStarted = { screen = Screen.Recording })
                Screen.Recording -> RecordingScreen(recording, onStop = {
                    ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                })
                is Screen.Detail -> MeetingDetailScreen(db, s.id, onBack = { refresh++; screen = Screen.Meetings })
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mai_brand_mark), null, Modifier.size(46.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            GradientText("MAI", 24.sp, FontWeight.Bold)
            Text("Meeting Assistant Intelligence", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
    }
}

@Composable
private fun HomeScreen(db: MaiDb, refresh: Int, recordingActive: Boolean, onNew: () -> Unit, onMeeting: (String) -> Unit, onResume: () -> Unit) {
    val meetings = remember(refresh) { db.listMeetings() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            BrandHeader(); Spacer(Modifier.height(26.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(22.dp)) {
                    GradientText("Listen. Understand.", 28.sp, FontWeight.Bold)
                    Text("Summarize. Action.", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .78f))
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = if (recordingActive) onResume else onNew, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(9.dp)); Text(if (recordingActive) "Recording" else "Record", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (meetings.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(meetings.take(5)) { m -> MeetingCard(m) { onMeeting(m.id) } }
        }
    }
}

@Composable
private fun MeetingCard(m: MeetingRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(SoftBlue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = BrandBlue) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.startedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
            if (m.status == "ready") Icon(Icons.Default.Check, null, tint = Color(0xFF22A06B))
        }
    }
}

@Composable
private fun NewMeetingScreen(db: MaiDb, onBack: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    val people = remember { mutableStateListOf<Participant>() }
    var contactDialog by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TopBack("New Meeting", onBack)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Meeting name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        Text("People", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton({ contactDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Groups, null); Spacer(Modifier.width(6.dp)); Text("Contacts") }
            OutlinedButton({ manualDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Manual") }
        }
        Spacer(Modifier.height(12.dp))
        people.forEachIndexed { index, p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Text(p.name.take(1).uppercase(), color = BrandBlue, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.phone, style = MaterialTheme.typography.bodySmall) }
                    IconButton({ people.removeAt(index) }) { Icon(Icons.Default.DeleteOutline, null) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                val id = db.createMeeting(title, people.toList())
                val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_MEETING_ID, id)
                ContextCompat.startForegroundService(context, intent)
                onStarted()
            },
            enabled = people.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
            shape = RoundedCornerShape(18.dp)
        ) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start", fontWeight = FontWeight.Bold) }
    }

    if (contactDialog) ContactDialog(onDismiss = { contactDialog = false }, onPick = { p ->
        if (people.none { normalizePhone(it.phone) == normalizePhone(p.phone) }) people += p
        contactDialog = false
    })
    if (manualDialog) ManualPersonDialog(onDismiss = { manualDialog = false }, onAdd = { p ->
        if (people.none { normalizePhone(it.phone) == normalizePhone(p.phone) }) people += p
        manualDialog = false
    })
}

@Composable
private fun ContactDialog(onDismiss: () -> Unit, onPick: (Participant) -> Unit) {
    val context = LocalContext.current
    val contacts = remember { loadContacts(context) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contacts") },
        text = {
            Column(Modifier.height(440.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(contacts.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Text(p.name.take(1).uppercase(), color = BrandBlue) }
                            Spacer(Modifier.width(10.dp)); Column { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.phone, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun loadContacts(context: Context): List<Participant> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = LinkedHashMap<String, Participant>()
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
        val ni = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val pi = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (c.moveToNext()) {
            val name = c.getString(ni)?.trim().orEmpty(); val phone = c.getString(pi)?.trim().orEmpty()
            if (name.isNotBlank() && phone.isNotBlank()) out.putIfAbsent(normalizePhone(phone), Participant(name, phone))
        }
    }
    return out.values.toList()
}

@Composable
private fun ManualPersonDialog(onDismiss: () -> Unit, onAdd: (Participant) -> Unit) {
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    val valid = name.trim().length >= 2 && normalizePhone(phone).length >= 7
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add person") },
        text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp number") }, singleLine = true) } },
        confirmButton = { Button(onClick = { onAdd(Participant(name.trim(), phone.trim())) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun normalizePhone(s: String) = s.filter { it.isDigit() || it == '+' }.replace(" ", "")

@Composable
private fun RecordingScreen(state: com.mai.app.recording.RecordingSnapshot, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandHeader(); Spacer(Modifier.height(36.dp))
        Text(formatElapsed(state.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(24.dp))
        LiveWaveform(state.levels, Modifier.fillMaxWidth().height(132.dp))
        Spacer(Modifier.height(10.dp))
        Text(if (state.level <= 0f) "Waiting for voice" else "Voice detected", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill("Recording", BrandBlue)
            StatusPill(if (state.audioSafe) "Audio safe" else "Saving", if (state.audioSafe) Color(0xFF22A06B) else BrandViolet)
        }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp)) {
            LazyColumn(Modifier.padding(18.dp)) {
                item { Text("Transcript", fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)) }
                item { Text((state.transcript + if (state.partial.isNotBlank()) "\n${state.partial}" else "").ifBlank { "…" }, lineHeight = 22.sp) }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB4233A)), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2f
        if (levels.all { it <= 0.001f }) {
            drawLine(Brush.horizontalGradient(listOf(BrandBlue.copy(.45f), BrandViolet.copy(.45f))), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 4f, cap = StrokeCap.Round)
        } else {
            val gap = 5f
            val barW = (size.width - gap * (levels.size - 1)) / levels.size
            levels.forEachIndexed { index, l ->
                val amp = if (l <= 0f) 2f else 8f + l * size.height * .82f
                val top = mid - amp / 2
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(BrandBlue, BrandViolet)),
                    topLeft = Offset(index * (barW + gap), top), size = Size(barW.coerceAtLeast(2f), amp),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2, barW / 2)
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(6.dp)); Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MeetingsScreen(db: MaiDb, refresh: Int, onMeeting: (String) -> Unit) {
    val meetings = remember(refresh) { db.listMeetings() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Meetings", fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)) }
        items(meetings) { m -> MeetingCard(m) { onMeeting(m.id) } }
        if (meetings.isEmpty()) item { EmptyState("No meetings") }
    }
}

@Composable
private fun ActionsScreen(db: MaiDb, refresh: Int) {
    val meetings = remember(refresh) { db.listMeetings() }
    val actions = remember(meetings) { meetings.flatMap { m -> m.actions.map { m to it } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Actions", fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)) }
        items(actions) { (m, a) ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(a.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row { a.owner?.let { Text(it, color = BrandBlue, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(10.dp)) }; a.due?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    Text(m.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                }
            }
        }
        if (actions.isEmpty()) item { EmptyState("No actions") }
    }
}

@Composable
private fun SettingsScreen(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE) }
    var retention by remember { mutableIntStateOf(prefs.getInt("audio_retention_days", 7)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item {
            SettingsCard("Audio", "Auto-delete") {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(1, 7, 15, 30).forEach { day ->
                        val selected = retention == day
                        if (selected) Button(onClick = {}, contentPadding = PaddingValues(horizontal = 11.dp)) { Text("$day d") }
                        else OutlinedButton(onClick = { retention = day; prefs.edit().putInt("audio_retention_days", day).apply() }, contentPadding = PaddingValues(horizontal = 11.dp)) { Text("$day d") }
                    }
                }
                Spacer(Modifier.height(8.dp)); Text("MOM stays until you delete the meeting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
        }
        item {
            SettingsCard("Theme", null) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ThemeButton(themeMode == 0, "System", Icons.Default.WbAuto) { onTheme(0); prefs.edit().putInt("theme", 0).apply() }
                    ThemeButton(themeMode == 1, "Light", Icons.Default.LightMode) { onTheme(1); prefs.edit().putInt("theme", 1).apply() }
                    ThemeButton(themeMode == 2, "Dark", Icons.Default.DarkMode) { onTheme(2); prefs.edit().putInt("theme", 2).apply() }
                }
            }
        }
        item { SettingsCard("Speech", "Offline") { Text("English", fontWeight = FontWeight.SemiBold) } }
    }
}

@Composable
private fun ThemeButton(selected: Boolean, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    if (selected) Button(onClick, contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(text) }
    else OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(text) }
}

@Composable
private fun SettingsCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row { Text(title, fontWeight = FontWeight.Bold); subtitle?.let { Spacer(Modifier.width(8.dp)); Text(it, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) } }
            Spacer(Modifier.height(12.dp)); content()
        }
    }
}

@Composable
private fun MeetingDetailScreen(db: MaiDb, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var meeting by remember(id) { mutableStateOf(db.getMeeting(id)) }
    var tab by remember { mutableIntStateOf(0) }
    val m = meeting
    if (m == null) { EmptyState("Meeting unavailable"); return }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TopBack(m.title, onBack)
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.startedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("MOM", "Transcript", "Audio").forEachIndexed { i, name ->
                if (tab == i) Button({ tab = i }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(name) }
                else OutlinedButton({ tab = i }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(name) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> MomTab(m, Modifier.weight(1f))
            1 -> Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp)) { LazyColumn(Modifier.padding(16.dp)) { item { Text(m.transcript.ifBlank { "No speech detected." }, lineHeight = 22.sp) } } }
            2 -> AudioTab(m, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { PdfShare.share(context, m) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share PDF") }
    }
}

@Composable
private fun MomTab(m: MeetingRecord, modifier: Modifier = Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionCard("Summary", listOf(m.summary)) }
        if (m.decisions.isNotEmpty()) item { SectionCard("Decisions", m.decisions) }
        if (m.actions.isNotEmpty()) item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Actions", fontWeight = FontWeight.Bold, color = BrandViolet); Spacer(Modifier.height(10.dp))
                    m.actions.forEachIndexed { index, a ->
                        Text(a.text, fontWeight = FontWeight.SemiBold)
                        Row { a.owner?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BrandBlue) }; if (a.owner != null && a.due != null) Text(" · "); a.due?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        if (index != m.actions.lastIndex) { Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp)) }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("People", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); m.participants.forEach { Text("${it.name} · ${it.phone}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, lines: List<String>) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = BrandBlue); Spacer(Modifier.height(8.dp)); lines.filter { it.isNotBlank() }.forEach { Text(if (lines.size > 1) "• $it" else it, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun AudioTab(m: MeetingRecord, modifier: Modifier = Modifier) {
    val path = m.audioPath
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(path) { onDispose { runCatching { player?.release() } } }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (path == null || !File(path).exists()) {
                Icon(Icons.Default.DeleteOutline, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)); Spacer(Modifier.height(10.dp)); Text("Audio expired")
            } else {
                Icon(Icons.Default.Mic, null, Modifier.size(58.dp), tint = BrandBlue); Spacer(Modifier.height(14.dp))
                FilledTonalButton(onClick = {
                    if (playing) { player?.pause(); playing = false }
                    else {
                        if (player == null) {
                            player = MediaPlayer().apply { setDataSource(path); prepare(); setOnCompletionListener { playing = false } }
                        }
                        player?.start(); playing = true
                    }
                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Play") }
            }
        }
    }
}

@Composable
private fun TopBack(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(text: String) { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) } }

@Composable
private fun GradientText(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = TextStyle(fontSize = size, fontWeight = weight, brush = Brush.linearGradient(listOf(BrandBlue, BrandViolet))))
}

private fun formatElapsed(ms: Long): String {
    val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
