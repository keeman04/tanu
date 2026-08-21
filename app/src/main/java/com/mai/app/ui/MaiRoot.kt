package com.mai.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mai.app.R
import com.mai.app.data.ActionRecord
import com.mai.app.data.MaiDb
import com.mai.app.data.MeetingRecord
import com.mai.app.data.Participant
import com.mai.app.recording.RecordingBus
import com.mai.app.recording.RecordingService
import com.mai.app.recording.RecordingSnapshot
import com.mai.app.share.PdfShare
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

private val BrandDark = Color(0xFF102826)
private val BrandTeal = Color(0xFF20A98F)
private val BrandLime = Color(0xFFD8F36A)
private val BrandCream = Color(0xFFF6F6EF)
private val DarkSurface = Color(0xFF183431)
private val SoftMint = Color(0xFFE8F5F1)
private val SafeGreen = Color(0xFF1F9D69)

private sealed interface Screen {
    data object Home : Screen
    data object Meetings : Screen
    data object Actions : Screen
    data object Ask : Screen
    data object Settings : Screen
    data object NewMeeting : Screen
    data object Recording : Screen
    data class Detail(val id: String) : Screen
}

@Composable
fun MaiRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val colors = if (dark) {
        darkColorScheme(
            primary = BrandLime,
            secondary = BrandTeal,
            background = BrandDark,
            surface = DarkSurface,
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = BrandDark
        )
    } else {
        lightColorScheme(
            primary = BrandDark,
            secondary = BrandTeal,
            background = BrandCream,
            surface = Color.White,
            onBackground = BrandDark,
            onSurface = BrandDark,
            onPrimary = Color.White
        )
    }

    MaterialTheme(colorScheme = colors) {
        var splash by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(1250); splash = false }
        if (splash) Splash(dark) else PermissionGate(themeMode) { mode ->
            themeMode = mode
            prefs.edit().putInt("theme", mode).apply()
        }
    }
}

@Composable
private fun Splash(dark: Boolean) {
    val scale = remember { Animatable(.78f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(520, easing = EaseOutCubic))
        alpha.animateTo(1f, tween(360))
    }
    Box(
        Modifier.fillMaxSize().background(if (dark) BrandDark else BrandCream),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.mai_brand_mark),
                "MAI",
                Modifier.size(108.dp).graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
                }
            )
            Spacer(Modifier.height(12.dp))
            Text("MAI", fontSize = 42.sp, fontWeight = FontWeight.Black)
            Text("Meeting Assistant Intelligence", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
    }
}

@Composable
private fun PermissionGate(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val required = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
    val granted = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }

    if (!granted) {
        PermissionScreen(
            onAllow = {
                val ask = required.toMutableList()
                if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(ask.toTypedArray())
            },
            onSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        )
    } else MaiApp(themeMode, onTheme)
    revision.hashCode()
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.mai_brand_mark), "MAI", Modifier.size(82.dp))
            Spacer(Modifier.height(15.dp))
            Text("MAI", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Microphone and contacts stay under your control.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            Spacer(Modifier.height(26.dp))
            PermissionRow(Icons.Default.Mic, "Microphone", "Record meetings")
            PermissionRow(Icons.Default.People, "Contacts", "Add participants")
            Spacer(Modifier.height(22.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
            TextButton(onClick = onSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(SoftMint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = BrandTeal)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(label, fontWeight = FontWeight.SemiBold); Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) }
    }
}

@Composable
private fun MaiApp(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    val db = remember { MaiDb(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var revision by remember { mutableIntStateOf(0) }
    val recording by RecordingBus.state.collectAsStateWithLifecycle()

    LaunchedEffect(recording.active, recording.status) {
        if (!recording.active && recording.status == "ready" && screen == Screen.Recording && recording.meetingId != null) {
            revision++
            screen = Screen.Detail(recording.meetingId!!)
        }
    }

    val root = screen in listOf(Screen.Home, Screen.Meetings, Screen.Actions, Screen.Ask, Screen.Settings)
    Scaffold(bottomBar = {
        if (root) NavigationBar {
            NavigationBarItem(screen == Screen.Home, { screen = Screen.Home }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(screen == Screen.Meetings, { screen = Screen.Meetings }, { Icon(Icons.Default.Description, null) }, label = { Text("Meetings") })
            NavigationBarItem(screen == Screen.Actions, { screen = Screen.Actions }, { Icon(Icons.Default.ListAlt, null) }, label = { Text("Actions") })
            NavigationBarItem(screen == Screen.Ask, { screen = Screen.Ask }, { Icon(Icons.Default.Search, null) }, label = { Text("Ask") })
            NavigationBarItem(screen == Screen.Settings, { screen = Screen.Settings }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                Screen.Home -> Home(db, revision, recording.active, { screen = Screen.NewMeeting }, { screen = Screen.Detail(it) }, { screen = Screen.Recording }, { screen = Screen.Ask })
                Screen.Meetings -> Meetings(db, revision) { screen = Screen.Detail(it) }
                Screen.Actions -> Actions(db, revision) { revision++ }
                Screen.Ask -> AskMai(db, revision) { screen = Screen.Detail(it) }
                Screen.Settings -> SettingsPage(themeMode, onTheme)
                Screen.NewMeeting -> NewMeeting(db, { screen = Screen.Home }) { screen = Screen.Recording }
                Screen.Recording -> RecordingPage(recording) {
                    context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                }
                is Screen.Detail -> MeetingDetail(db, current.id, { revision++; screen = Screen.Meetings }, { revision++ })
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mai_brand_mark), "MAI", Modifier.size(42.dp))
        Spacer(Modifier.width(9.dp))
        Column {
            Text("MAI", fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text("Meeting Assistant Intelligence", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
        }
    }
}

@Composable
private fun Home(
    db: MaiDb,
    revision: Int,
    recording: Boolean,
    onNew: () -> Unit,
    onMeeting: (String) -> Unit,
    onResume: () -> Unit,
    onAsk: () -> Unit
) {
    val meetings = remember(revision) { db.listMeetings() }
    val openActions = meetings.sumOf { it.actions.count { a -> !a.done } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            BrandHeader(); Spacer(Modifier.height(24.dp))
            Card(shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("Your meetings, remembered.", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Record → understand → act", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = if (recording) onResume else onNew,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (recording) "Return to recording" else "Start meeting", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onAsk, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(7.dp)); Text("Ask MAI")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(meetings.size.toString(), "Meetings", Modifier.weight(1f))
                StatCard(openActions.toString(), "Open actions", Modifier.weight(1f))
            }
        }
        if (meetings.isNotEmpty()) item { Text("Recent", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        items(meetings.take(5)) { MeetingCard(it) { onMeeting(it.id) } }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) { Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) }
    }
}

@Composable
private fun MeetingCard(meeting: MeetingRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(SoftMint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = BrandTeal) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meeting.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                if (meeting.status == "interrupted") Text("Recovered after interruption", fontSize = 11.sp, color = Color(0xFFB26A00))
            }
            if (meeting.status == "ready") Icon(Icons.Default.Check, null, tint = SafeGreen)
        }
    }
}

@Composable
private fun NewMeeting(db: MaiDb, onBack: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    val people = remember { mutableStateListOf<Participant>() }
    var contactsOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        BackHeader("New meeting", onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Meeting title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text("Participants", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("At least one person is required.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ contactsOpen = true }, Modifier.weight(1f)) { Icon(Icons.Default.Groups, null); Spacer(Modifier.width(5.dp)); Text("Contacts") }
            OutlinedButton({ manualOpen = true }, Modifier.weight(1f)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(5.dp)); Text("Manual") }
        }
        Spacer(Modifier.height(8.dp))
        people.forEachIndexed { index, person ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleInitial(person.name); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(person.name, fontWeight = FontWeight.SemiBold); Text(person.phone, fontSize = 12.sp) }
                    IconButton({ people.removeAt(index) }) { Icon(Icons.Default.DeleteOutline, null) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("By starting, make sure everyone knows the meeting is being recorded.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
        Spacer(Modifier.height(9.dp))
        Button(
            onClick = {
                val id = db.createMeeting(title, people.toList())
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_MEETING_ID, id))
                onStarted()
            },
            enabled = people.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start recording", fontWeight = FontWeight.Bold) }
    }

    if (contactsOpen) ContactsDialog({ contactsOpen = false }) { person ->
        if (people.none { normalizePhone(it.phone) == normalizePhone(person.phone) }) people += person
        contactsOpen = false
    }
    if (manualOpen) ManualDialog({ manualOpen = false }) { person ->
        if (people.none { normalizePhone(it.phone) == normalizePhone(person.phone) }) people += person
        manualOpen = false
    }
}

@Composable
private fun CircleInitial(name: String) {
    Box(Modifier.size(38.dp).background(SoftMint, CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = BrandTeal, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ContactsDialog(onDismiss: () -> Unit, onPick: (Participant) -> Unit) {
    val context = LocalContext.current
    val contacts = remember { readContacts(context) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contacts") },
        text = {
            Column(Modifier.height(430.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(contacts.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }) { person ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(person) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircleInitial(person.name); Spacer(Modifier.width(9.dp)); Column { Text(person.name, fontWeight = FontWeight.SemiBold); Text(person.phone, fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun readContacts(context: Context): List<Participant> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = LinkedHashMap<String, Participant>()
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex)?.trim().orEmpty()
            val phone = cursor.getString(phoneIndex)?.trim().orEmpty()
            if (name.isNotBlank() && phone.isNotBlank()) result.putIfAbsent(normalizePhone(phone), Participant(name, phone))
        }
    }
    return result.values.toList()
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onAdd: (Participant) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val valid = name.trim().length >= 2 && normalizePhone(phone).filter(Char::isDigit).length >= 7
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add participant") },
        text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp number") }, singleLine = true) } },
        confirmButton = { Button({ onAdd(Participant(name.trim(), phone.trim())) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun normalizePhone(value: String): String = value.filter { it.isDigit() || it == '+' }

@Composable
private fun RecordingPage(state: RecordingSnapshot, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandHeader(); Spacer(Modifier.height(30.dp))
        Text(formatElapsed(state.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(18.dp))
        VolumeWaveform(state.levels, Modifier.fillMaxWidth().height(120.dp))
        Text(if (state.level <= 0f) "Listening" else "Voice detected", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Recording", BrandTeal)
            StatusChip(if (state.audioSafe) "Audio checkpointed" else "Securing audio", if (state.audioSafe) SafeGreen else Color(0xFFB26A00))
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp)) {
            LazyColumn(Modifier.padding(17.dp)) {
                item { Text("Live transcript", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)) }
                item {
                    val text = buildString { append(state.transcript); if (state.partial.isNotBlank()) { if (isNotBlank()) append('\n'); append(state.partial) } }
                    Text(text.ifBlank { "MAI is recording. Live text appears when offline speech recognition is available." }, lineHeight = 22.sp)
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5273E)), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop meeting", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VolumeWaveform(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2f
        val values = if (levels.isEmpty()) List(48) { 0f } else levels
        if (values.all { it <= .001f }) drawLine(BrandTeal.copy(alpha = .5f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 4f, cap = StrokeCap.Round)
        else {
            val gap = 4f
            val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(2f)
            values.forEachIndexed { index, level ->
                val amplitude = if (level <= 0f) 2f else 8f + level * size.height * .8f
                drawRoundRect(Brush.verticalGradient(listOf(BrandTeal, BrandLime)), Offset(index * (width + gap), mid - amplitude / 2f), Size(width, amplitude), CornerRadius(width / 2f, width / 2f))
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(5.dp)); Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Meetings(db: MaiDb, revision: Int, onMeeting: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val meetings = remember(revision, query) { db.searchMeetings(query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Meetings", fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search meetings, people, decisions…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        items(meetings) { MeetingCard(it) { onMeeting(it.id) } }
        if (meetings.isEmpty()) item { Empty("No matching meetings") }
    }
}

@Composable
private fun Actions(db: MaiDb, revision: Int, onChanged: () -> Unit) {
    val meetings = remember(revision) { db.listMeetings() }
    val rows = remember(meetings) { meetings.flatMap { m -> m.actions.mapIndexed { index, action -> Triple(m, index, action) } }.sortedBy { it.third.done } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Actions", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Tap the circle when an action is completed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) }
        items(rows) { (meeting, index, action) ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ db.updateActionDone(meeting.id, index, !action.done); onChanged() }) { Icon(if (action.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (action.done) SafeGreen else BrandTeal) }
                    Column(Modifier.weight(1f)) {
                        Text(action.text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.done) .45f else 1f))
                        val meta = listOfNotNull(action.owner, action.due).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = BrandTeal)
                        Text(meeting.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Empty("No actions yet") }
    }
}

@Composable
private fun AskMai(db: MaiDb, revision: Int, onMeeting: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(revision, query) { if (query.isBlank()) emptyList() else db.searchMeetings(query) }
    val answer = remember(matches, query) { localAnswer(query, matches) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Ask MAI", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Search your meeting memory privately on this phone.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("What did we decide about pricing?") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (query.isBlank()) {
            item { Suggestion("Try: Ravi", { query = "Ravi" }); Suggestion("Try: pricing", { query = "pricing" }); Suggestion("Try: follow up", { query = "follow up" }) }
        } else {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .08f))) {
                    Column(Modifier.padding(16.dp)) { Text("MAI", fontWeight = FontWeight.Bold, color = BrandTeal); Spacer(Modifier.height(6.dp)); Text(answer, lineHeight = 21.sp) }
                }
            }
            if (matches.isNotEmpty()) item { Text("Source meetings", fontWeight = FontWeight.Bold) }
            items(matches.take(10)) { MeetingCard(it) { onMeeting(it.id) } }
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)) { Text(text) } }

private fun localAnswer(query: String, meetings: List<MeetingRecord>): String {
    if (meetings.isEmpty()) return "I couldn't find that in your saved meetings. Try a participant name, topic, decision, or action keyword."
    val q = query.lowercase()
    val lines = mutableListOf<String>()
    meetings.forEach { m ->
        m.decisions.filter { it.lowercase().contains(q) }.take(2).forEach { lines += "Decision — $it" }
        m.actions.filter { it.text.lowercase().contains(q) || it.owner?.lowercase()?.contains(q) == true }.take(2).forEach { a ->
            lines += "Action — ${a.text}${a.owner?.let { " · $it" } ?: ""}${a.due?.let { " · $it" } ?: ""}"
        }
        if (lines.size < 4 && m.summary.lowercase().contains(q)) lines += "${m.title} — ${m.summary}"
    }
    if (lines.isEmpty()) return "I found ${meetings.size} related meeting${if (meetings.size == 1) "" else "s"}. Open the source meetings below for the full context."
    return lines.distinct().take(6).joinToString("\n\n")
}

@Composable
private fun SettingsPage(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE) }
    var retention by remember { mutableIntStateOf(prefs.getInt("audio_retention_days", 0)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(18.dp)); Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item {
            SettingsCard("Audio retention") {
                Text("MOM, transcript, decisions and actions stay until you delete the meeting.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(0 to "Forever", 1 to "1 d", 7 to "7 d", 30 to "30 d").forEach { (days, label) ->
                        if (retention == days) Button(onClick = {}, contentPadding = PaddingValues(horizontal = 9.dp)) { Text(label) }
                        else OutlinedButton(onClick = { retention = days; prefs.edit().putInt("audio_retention_days", days).apply() }, contentPadding = PaddingValues(horizontal = 9.dp)) { Text(label) }
                    }
                }
            }
        }
        item {
            SettingsCard("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeChoice(themeMode == 0, "System", Icons.Default.WbAuto) { onTheme(0) }
                    ThemeChoice(themeMode == 1, "Light", Icons.Default.LightMode) { onTheme(1) }
                    ThemeChoice(themeMode == 2, "Dark", Icons.Default.DarkMode) { onTheme(2) }
                }
            }
        }
        item { SettingsCard("Speech") { Text("Offline English live transcript", fontWeight = FontWeight.SemiBold); Text("Audio is always saved even when speech recognition is unavailable.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) } }
        item { SettingsCard("Privacy") { Text("Meeting files and meeting memory are stored locally on this device in V1.", fontSize = 13.sp) } }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); content() } } }

@Composable
private fun ThemeChoice(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 9.dp)) { Icon(icon, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(label) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 9.dp)) { Icon(icon, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(label) }
}

@Composable
private fun MeetingDetail(db: MaiDb, id: String, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val meeting = remember(id, revision) { db.getMeeting(id) }
    var tab by remember { mutableIntStateOf(0) }
    var deleteOpen by remember { mutableStateOf(false) }
    if (meeting == null) { Empty("Meeting unavailable"); return }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Text(meeting.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton({ deleteOpen = true }) { Icon(Icons.Default.DeleteOutline, null) }
        }
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("MOM", "Transcript", "Audio").forEachIndexed { index, label ->
                if (tab == index) Button({ tab = index }, contentPadding = PaddingValues(horizontal = 11.dp)) { Text(label) }
                else OutlinedButton({ tab = index }, contentPadding = PaddingValues(horizontal = 11.dp)) { Text(label) }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> Mom(meeting, Modifier.weight(1f)) { index, done -> db.updateActionDone(meeting.id, index, done); revision++; onChanged() }
            1 -> Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp)) { LazyColumn(Modifier.padding(15.dp)) { item { Text(meeting.transcript.ifBlank { "No live transcript was available. Your saved audio may still contain the full meeting." }, lineHeight = 22.sp) } } }
            else -> Audio(meeting, Modifier.weight(1f))
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ PdfShare.share(context, meeting) }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("Share") }
            Button({ PdfShare.shareToWhatsApp(context, meeting) }, Modifier.weight(1f)) { Text("WhatsApp PDF") }
        }
    }

    if (deleteOpen) AlertDialog(
        onDismissRequest = { deleteOpen = false },
        title = { Text("Delete meeting?") },
        text = { Text("This removes the MOM, transcript, actions and saved audio from this device.") },
        confirmButton = { Button(onClick = { db.deleteMeeting(meeting.id); deleteOpen = false; onChanged(); onBack() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5273E))) { Text("Delete") } },
        dismissButton = { TextButton({ deleteOpen = false }) { Text("Cancel") } }
    )
}

@Composable
private fun Mom(meeting: MeetingRecord, modifier: Modifier, onAction: (Int, Boolean) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Section("Summary", listOf(meeting.summary.ifBlank { "Meeting saved." })) }
        if (meeting.decisions.isNotEmpty()) item { Section("Decisions", meeting.decisions) }
        if (meeting.actions.isNotEmpty()) item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(15.dp)) {
                    Text("Actions", fontWeight = FontWeight.Bold, color = BrandTeal); Spacer(Modifier.height(8.dp))
                    meeting.actions.forEachIndexed { index, action ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ onAction(index, !action.done) }) { Icon(if (action.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (action.done) SafeGreen else BrandTeal) }
                            Column(Modifier.weight(1f)) { Text(action.text, fontWeight = FontWeight.SemiBold); val meta = listOfNotNull(action.owner, action.due).joinToString(" · "); if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = BrandTeal) }
                        }
                        if (index < meeting.actions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text("Participants", fontWeight = FontWeight.Bold); Spacer(Modifier.height(7.dp)); meeting.participants.forEach { Text("${it.name} · ${it.phone}") } } } }
    }
}

@Composable
private fun Section(title: String, lines: List<String>) { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text(title, fontWeight = FontWeight.Bold, color = BrandTeal); Spacer(Modifier.height(7.dp)); lines.filter(String::isNotBlank).forEach { line -> Text(if (lines.size > 1) "• $line" else line, lineHeight = 21.sp) } } } }

@Composable
private fun Audio(meeting: MeetingRecord, modifier: Modifier) {
    val path = meeting.audioPath
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { runCatching { player?.release() } } }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (path == null || !File(path).exists()) { Icon(Icons.Default.DeleteOutline, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .3f)); Spacer(Modifier.height(8.dp)); Text("Audio not available") }
            else {
                Icon(Icons.Default.Mic, null, Modifier.size(56.dp), tint = BrandTeal); Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = {
                    if (playing) { player?.pause(); playing = false }
                    else {
                        if (player == null) player = MediaPlayer().apply { setDataSource(path); prepare(); setOnCompletionListener { playing = false } }
                        player?.start(); playing = true
                    }
                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Play") }
            }
        }
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }; Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

@Composable
private fun Empty(text: String) { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) } }

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000; val hours = total / 3600; val minutes = (total % 3600) / 60; val seconds = total % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
