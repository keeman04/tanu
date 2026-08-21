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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mai.app.intelligence.AskEngine
import com.mai.app.pipeline.CloudApi
import com.mai.app.pipeline.MaiPipelineDatabase
import com.mai.app.recording.RecordingBus
import com.mai.app.recording.RecordingService
import com.mai.app.recording.RecordingSnapshot
import com.mai.app.share.PdfShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

private val BrandBlue = Color(0xFF356CFF)
private val BrandViolet = Color(0xFF7652F4)
private val BrandNavy = Color(0xFF0B1534)
private val DarkSurface = Color(0xFF121C3D)
private val SoftBlue = Color(0xFFEFF3FF)
private val SafeGreen = Color(0xFF1F9D69)
private val WarningAmber = Color(0xFFD97706)

private sealed interface Screen {
    data object Home : Screen
    data object Meetings : Screen
    data object Ask : Screen
    data object Actions : Screen
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
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8AA8FF), secondary = Color(0xFFA88CFF), background = BrandNavy,
        surface = DarkSurface, onBackground = Color.White, onSurface = Color.White
    ) else lightColorScheme(
        primary = BrandBlue, secondary = BrandViolet, background = Color(0xFFF7F9FD),
        surface = Color.White, onBackground = BrandNavy, onSurface = BrandNavy
    )
    MaterialTheme(colorScheme = colors) {
        var splash by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(1_250); splash = false }
        if (splash) Splash(dark) else PermissionGate(themeMode) { mode ->
            themeMode = mode; prefs.edit().putInt("theme", mode).apply()
        }
    }
}

@Composable
private fun Splash(dark: Boolean) {
    val scale = remember { Animatable(.72f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(600, easing = EaseOutCubic)); alpha.animateTo(1f, tween(350)) }
    Box(Modifier.fillMaxSize().background(if (dark) BrandNavy else Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.mai_brand_mark), "MAI", Modifier.size(110.dp).graphicsLayer {
                scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
            })
            Spacer(Modifier.height(10.dp))
            Text("MAI", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = if (dark) Color.White else BrandNavy)
            Text("Meeting Assistant Intelligence", color = if (dark) Color(0xFFB7C0DD) else Color(0xFF727B96))
        }
    }
}

@Composable
private fun PermissionGate(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    if (!micGranted) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.mai_brand_mark), "MAI", Modifier.size(84.dp))
                Spacer(Modifier.height(18.dp)); Text("MAI needs your microphone", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Audio is saved first. Intelligence never blocks recording.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = {
                    val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                    launcher.launch(ask.toTypedArray())
                }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Allow microphone") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
            }
        }
    } else MaiApp(themeMode, onTheme)
    revision.hashCode()
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
            revision++; screen = Screen.Detail(recording.meetingId!!)
        }
    }
    val root = screen in listOf(Screen.Home, Screen.Meetings, Screen.Ask, Screen.Actions, Screen.Settings)
    Scaffold(bottomBar = {
        if (root) NavigationBar {
            NavigationBarItem(screen == Screen.Home, { screen = Screen.Home }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(screen == Screen.Meetings, { screen = Screen.Meetings }, { Icon(Icons.Default.Description, null) }, label = { Text("Meetings") })
            NavigationBarItem(screen == Screen.Ask, { screen = Screen.Ask }, { Icon(Icons.Default.AutoAwesome, null) }, label = { Text("Ask") })
            NavigationBarItem(screen == Screen.Actions, { screen = Screen.Actions }, { Icon(Icons.Default.ListAlt, null) }, label = { Text("Actions") })
            NavigationBarItem(screen == Screen.Settings, { screen = Screen.Settings }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                Screen.Home -> Home(db, revision, recording.active, { screen = Screen.NewMeeting }, { screen = Screen.Detail(it) }, { screen = Screen.Recording }, { screen = Screen.Ask })
                Screen.Meetings -> Meetings(db, revision) { screen = Screen.Detail(it) }
                Screen.Ask -> AskPage(db, revision)
                Screen.Actions -> Actions(db, revision)
                Screen.Settings -> SettingsPage(themeMode, onTheme)
                Screen.NewMeeting -> NewMeeting(db, { screen = Screen.Home }) { screen = Screen.Recording }
                Screen.Recording -> RecordingPage(recording) { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
                is Screen.Detail -> MeetingDetail(db, current.id, { revision++; screen = Screen.Meetings }, { revision++ })
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mai_brand_mark), "MAI", Modifier.size(44.dp)); Spacer(Modifier.width(9.dp))
        Column { Text("MAI", fontSize = 23.sp, fontWeight = FontWeight.Bold); Text("Meeting Assistant Intelligence", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) }
    }
}

@Composable
private fun Home(db: MaiDb, revision: Int, recording: Boolean, onNew: () -> Unit, onMeeting: (String) -> Unit, onResume: () -> Unit, onAsk: () -> Unit) {
    val meetings = remember(revision) { db.listMeetings() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            BrandHeader(); Spacer(Modifier.height(24.dp))
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(22.dp)) {
                    Text("Listen. Understand.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Summarize. Action.", fontSize = 23.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = if (recording) onResume else onNew, Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (recording) "Return to recording" else "Start meeting", fontWeight = FontWeight.Bold)
                    }
                    if (meetings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onAsk, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask MAI") }
                    }
                }
            }
        }
        if (meetings.isNotEmpty()) item { Text("Recent", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        items(meetings.take(5)) { MeetingCard(it) { onMeeting(it.id) } }
    }
}

@Composable
private fun MeetingCard(meeting: MeetingRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(SoftBlue, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = BrandBlue) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Text(meeting.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            }
            when (meeting.status) {
                "ready" -> Icon(Icons.Default.Check, "Ready", tint = SafeGreen)
                "processing" -> Text("AI", color = WarningAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
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
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) contactsOpen = true }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        BackHeader("New Meeting", onBack); Spacer(Modifier.height(14.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Meeting name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp)); Text("Auto language · English + Tamil + Tanglish", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        Spacer(Modifier.height(18.dp)); Text("Participants", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Add at least one person before recording.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) contactsOpen = true
                else contactPermission.launch(Manifest.permission.READ_CONTACTS)
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Groups, null); Spacer(Modifier.width(5.dp)); Text("Contacts") }
            OutlinedButton(onClick = { manualOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(5.dp)); Text("Manual") }
        }
        Spacer(Modifier.height(8.dp))
        people.forEachIndexed { index, person -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleInitial(person.name); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(person.name, fontWeight = FontWeight.SemiBold); Text(person.phone, fontSize = 12.sp) }
                IconButton(onClick = { people.removeAt(index) }) { Icon(Icons.Default.DeleteOutline, "Remove") }
            }
        } }
        Spacer(Modifier.weight(1f)); Text("Record only with the participants' knowledge/consent where required.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), modifier = Modifier.padding(bottom = 8.dp))
        Button(onClick = {
            val id = db.createMeeting(title, people.toList())
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_MEETING_ID, id))
            onStarted()
        }, enabled = people.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start recording", fontWeight = FontWeight.Bold)
        }
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
private fun CircleInitial(name: String) { Box(Modifier.size(38.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = BrandBlue, fontWeight = FontWeight.Bold) } }

@Composable
private fun ContactsDialog(onDismiss: () -> Unit, onPick: (Participant) -> Unit) {
    val context = LocalContext.current
    val contacts = remember { readContacts(context) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Contacts") }, text = {
        Column(Modifier.height(430.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
            LazyColumn { items(contacts.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }) { person ->
                Row(Modifier.fillMaxWidth().clickable { onPick(person) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleInitial(person.name); Spacer(Modifier.width(9.dp)); Column { Text(person.name, fontWeight = FontWeight.SemiBold); Text(person.phone, fontSize = 12.sp) }
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private fun readContacts(context: Context): List<Participant> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = LinkedHashMap<String, Participant>()
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
        val ni = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val pi = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(ni)?.trim().orEmpty(); val phone = cursor.getString(pi)?.trim().orEmpty()
            if (name.isNotBlank() && phone.isNotBlank()) out.putIfAbsent(normalizePhone(phone), Participant(name, phone))
        }
    }
    return out.values.toList()
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onAdd: (Participant) -> Unit) {
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    val valid = name.trim().length >= 2 && normalizePhone(phone).filter(Char::isDigit).length >= 7
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add person") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); Spacer(Modifier.height(8.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp number") }, singleLine = true)
    } }, confirmButton = { Button(onClick = { onAdd(Participant(name.trim(), phone.trim())) }, enabled = valid) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun normalizePhone(value: String): String = value.filter { it.isDigit() || it == '+' }

@Composable
private fun RecordingPage(state: RecordingSnapshot, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandHeader(); Spacer(Modifier.height(28.dp)); Text(formatElapsed(state.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(18.dp)); VolumeWaveform(state.levels, Modifier.fillMaxWidth().height(120.dp)); Spacer(Modifier.height(8.dp))
        Text(if (state.level <= 0f) "Listening" else "Voice detected", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusChip("Recording", BrandBlue); StatusChip(if (state.audioSafe) "Audio safe" else "Saving", if (state.audioSafe) SafeGreen else BrandViolet) }
        Spacer(Modifier.height(14.dp)); Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp)) {
            LazyColumn(Modifier.padding(17.dp)) { item { Text("Live preview", fontWeight = FontWeight.Bold); Text("Final multilingual transcript is refined after each safe chunk.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)); Spacer(Modifier.height(8.dp)) }
                item { val text = buildString { append(state.transcript); if (state.partial.isNotBlank()) { if (isNotBlank()) append('\n'); append(state.partial) } }; Text(text.ifBlank { "…" }, lineHeight = 22.sp) }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }; Spacer(Modifier.height(10.dp))
        Button(onClick = onStop, enabled = state.status !in setOf("finishing", "securing"), modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB5273E)), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(if (state.status in setOf("finishing", "securing")) "Finishing…" else "Stop meeting", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VolumeWaveform(levels: List<Float>, modifier: Modifier = Modifier) { Canvas(modifier) {
    val mid = size.height / 2f; val values = if (levels.isEmpty()) List(48) { 0f } else levels
    if (values.all { it <= .001f }) drawLine(BrandBlue.copy(alpha = .55f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 4f, cap = StrokeCap.Round)
    else { val gap = 4f; val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(2f); values.forEachIndexed { index, level ->
        val amplitude = if (level <= 0f) 2f else 8f + level * size.height * .8f
        drawRoundRect(Brush.verticalGradient(listOf(BrandBlue, BrandViolet)), Offset(index * (width + gap), mid - amplitude / 2f), Size(width, amplitude), CornerRadius(width / 2f, width / 2f))
    } }
} }

@Composable
private fun StatusChip(text: String, color: Color) { Row(Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(5.dp)); Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
} }

@Composable
private fun Meetings(db: MaiDb, revision: Int, onMeeting: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val meetings = remember(revision, query) { if (query.isBlank()) db.listMeetings() else db.searchMeetings(query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(16.dp)); Text("Meetings", fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search topic, person, action…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        items(meetings) { MeetingCard(it) { onMeeting(it.id) } }
        if (meetings.isEmpty()) item { Empty(if (query.isBlank()) "No meetings" else "No matching meetings") }
    }
}

@Composable
private fun AskPage(db: MaiDb, revision: Int) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val meetings = remember(revision) { db.listMeetings() }
    var question by remember { mutableStateOf("") }; var answer by remember { mutableStateOf("") }; var sources by remember { mutableStateOf<List<String>>(emptyList()) }; var loading by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(16.dp)); Text("Ask MAI", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Search across MOMs, transcripts, decisions, actions and people.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f), fontSize = 13.sp) }
        item { OutlinedTextField(question, { question = it }, label = { Text("What do you want to know?") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        item { Button(onClick = { loading = true; scope.launch { val result = AskEngine.ask(context, question, meetings); answer = result.answer; sources = result.sources; loading = false } }, enabled = question.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (loading) "Thinking…" else "Ask") } }
        if (answer.isNotBlank()) item { Card(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(17.dp)) { Text(answer, lineHeight = 22.sp); if (sources.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text("Sources · ${sources.joinToString(", ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) } } } }
        if (answer.isBlank()) item { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { listOf("What decisions did I make this week?", "Show overdue commitments", "What did we discuss about pricing?", "Find meetings mentioning Ravi").forEach { prompt -> OutlinedButton(onClick = { question = prompt }, modifier = Modifier.fillMaxWidth()) { Text(prompt) } } } }
    }
}

@Composable
private fun Actions(db: MaiDb, revision: Int) {
    val today = LocalDate.now(); val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy")
    val rows = remember(revision) { db.listMeetings().flatMap { m -> m.actions.map { m to it } } }
    fun dueDate(a: ActionRecord): LocalDate? = a.due?.let { raw -> listOf(DateTimeFormatter.ISO_LOCAL_DATE, fmt).firstNotNullOfOrNull { runCatching { f -> LocalDate.parse(raw, f) }.getOrNull() } }
    val sorted = rows.sortedWith(compareBy<Pair<MeetingRecord, ActionRecord>> { dueDate(it.second) ?: LocalDate.MAX }.thenBy { it.first.startedAt })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(16.dp)); Text("Actions", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        items(sorted) { (meeting, action) -> val due = dueDate(action); Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(action.text, fontWeight = FontWeight.SemiBold); if (action.owner != null || action.due != null) { Spacer(Modifier.height(4.dp)); Text(listOfNotNull(action.owner, action.due).joinToString(" · "), fontSize = 12.sp, color = if (due != null && !due.isAfter(today)) WarningAmber else BrandBlue) }
            Text(meeting.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f))
        } } }
        if (rows.isEmpty()) item { Empty("No actions yet") }
    }
}

@Composable
private fun SettingsPage(themeMode: Int, onTheme: (Int) -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE) }; val scope = rememberCoroutineScope()
    var retention by remember { mutableIntStateOf(prefs.getInt("audio_retention_days", 0)) }
    var backend by remember { mutableStateOf(prefs.getString("backend_url", "").orEmpty()) }; var token by remember { mutableStateOf(prefs.getString("backend_token", "").orEmpty()) }; var cloudStatus by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader(); Spacer(Modifier.height(16.dp)); Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { SettingsCard("Audio retention") { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Forever", 7 to "7d", 30 to "30d", 90 to "90d").forEach { (days, label) ->
                if (retention == days) Button(onClick = {}, contentPadding = PaddingValues(horizontal = 9.dp)) { Text(label) }
                else OutlinedButton(onClick = { retention = days; prefs.edit().putInt("audio_retention_days", days).apply() }, contentPadding = PaddingValues(horizontal = 9.dp)) { Text(label) }
            }
        }; Spacer(Modifier.height(6.dp)); Text("MOM, transcript, decisions and actions stay until you delete the meeting.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) } }
        item { SettingsCard("MAI Cloud Intelligence") {
            Text("Required for high-accuracy English + Tamil + Tanglish transcription and AI MOM. Never enter an OpenAI key here.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); Spacer(Modifier.height(8.dp))
            OutlinedTextField(backend, { backend = it }, label = { Text("Your MAI server URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(7.dp))
            OutlinedTextField(token, { token = it }, label = { Text("Server access token") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(onClick = { prefs.edit().putString("backend_url", backend.trim()).putString("backend_token", token.trim()).apply(); cloudStatus = "Saved" }) { Text("Save") }
                OutlinedButton(onClick = { prefs.edit().putString("backend_url", backend.trim()).putString("backend_token", token.trim()).apply(); cloudStatus = "Testing…"; scope.launch { cloudStatus = if (CloudApi(context).health()) "Connected" else "Not reachable" } }) { Text("Test") }
            }
            cloudStatus?.let { Text(it, color = if (it == "Connected") SafeGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        } }
        item { SettingsCard("Theme") { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeChoice(themeMode == 0, "System", Icons.Default.WbAuto) { onTheme(0) }; ThemeChoice(themeMode == 1, "Light", Icons.Default.LightMode) { onTheme(1) }; ThemeChoice(themeMode == 2, "Dark", Icons.Default.DarkMode) { onTheme(2) } } } }
        item { SettingsCard("Privacy") { Text("Audio is saved locally first. Cloud processing is used only when you configure your MAI server. The OpenAI API key stays on that server, never inside the APK.", fontSize = 12.sp) } }
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
    val context = LocalContext.current; val scope = rememberCoroutineScope(); var localRevision by remember { mutableIntStateOf(0) }; var tab by remember { mutableIntStateOf(0) }; var deleteConfirm by remember { mutableStateOf(false) }
    val meeting = remember(id, localRevision) { db.getMeeting(id) }
    LaunchedEffect(meeting?.status) {
        if (meeting?.status == "processing") repeat(40) { delay(3_000); localRevision++; if (db.getMeeting(id)?.status != "processing") return@LaunchedEffect }
    }
    if (meeting == null) { Empty("Meeting unavailable"); return }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        BackHeader(meeting.title, onBack); Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        if (meeting.status == "processing") { Spacer(Modifier.height(6.dp)); StatusChip("MAI refining transcript + MOM", WarningAmber) }
        Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("MOM", "Transcript", "Audio").forEachIndexed { index, label ->
            if (tab == index) Button(onClick = { tab = index }, contentPadding = PaddingValues(horizontal = 11.dp)) { Text(label) } else OutlinedButton(onClick = { tab = index }, contentPadding = PaddingValues(horizontal = 11.dp)) { Text(label) }
        } }
        Spacer(Modifier.height(9.dp)); when (tab) {
            0 -> Mom(meeting, Modifier.weight(1f))
            1 -> Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp)) { LazyColumn(Modifier.padding(15.dp)) { item { Text(meeting.transcript.ifBlank { "Transcript is still processing or no speech was detected." }, lineHeight = 22.sp) } } }
            else -> Audio(meeting, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = { PdfShare.shareWhatsApp(context, meeting) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("WhatsApp PDF") }
            OutlinedButton(onClick = { PdfShare.share(context, meeting) }) { Icon(Icons.Default.Share, "Share") }
            IconButton(onClick = { deleteConfirm = true }) { Icon(Icons.Default.DeleteOutline, "Delete meeting") }
        }
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("Delete meeting?") }, text = { Text("This removes the MOM, transcript and local audio from this phone.") }, confirmButton = { Button(onClick = {
        deleteConfirm = false; meeting.audioPath?.let { File(it).deleteRecursively() }; db.deleteMeeting(id); onChanged(); onBack()
        scope.launch(Dispatchers.IO) { runCatching { val dao = MaiPipelineDatabase.get(context).dao(); dao.deleteChunks(id); dao.deleteTranscript(id); dao.deleteMeeting(id) } }
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } })
}

@Composable
private fun Mom(meeting: MeetingRecord, modifier: Modifier) { LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { Section("Summary", listOf(meeting.summary)) }
    if (meeting.decisions.isNotEmpty()) item { Section("Decisions", meeting.decisions) }
    if (meeting.actions.isNotEmpty()) item { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text("Actions", fontWeight = FontWeight.Bold, color = BrandViolet); Spacer(Modifier.height(8.dp)); meeting.actions.forEachIndexed { index, action ->
        Text(action.text, fontWeight = FontWeight.SemiBold); listOfNotNull(action.owner, action.due).joinToString(" · ").takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = BrandBlue) }; if (index < meeting.actions.lastIndex) { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)) }
    } } } }
    if (meeting.followUps.isNotEmpty()) item { Section("Follow-up", meeting.followUps) }
    item { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text("People", fontWeight = FontWeight.Bold); Spacer(Modifier.height(7.dp)); meeting.participants.forEach { Text("${it.name} · ${it.phone}") } } } }
} }

@Composable
private fun Section(title: String, lines: List<String>) { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text(title, fontWeight = FontWeight.Bold, color = BrandBlue); Spacer(Modifier.height(7.dp)); lines.filter(String::isNotBlank).forEach { Text(if (lines.size > 1) "• $it" else it, lineHeight = 21.sp) } } } }

@Composable
private fun Audio(meeting: MeetingRecord, modifier: Modifier) {
    val path = meeting.audioPath
    val files = remember(path) {
        if (path == null) emptyList() else File(path).let { root ->
            if (root.isDirectory) root.listFiles { f -> f.isFile && (f.extension.lowercase() in setOf("ogg", "m4a", "aac")) }?.sortedBy { it.name }.orEmpty() else if (root.exists()) listOf(root) else emptyList()
        }
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }; var playing by remember { mutableStateOf(false) }; var index by remember { mutableIntStateOf(0) }
    fun startAt(i: Int) {
        if (files.isEmpty()) return
        val safe = i.coerceIn(0, files.lastIndex); index = safe
        runCatching { player?.release() }; player = MediaPlayer().apply {
            setDataSource(files[safe].absolutePath); prepare(); setOnCompletionListener {
                if (safe < files.lastIndex) startAt(safe + 1) else { playing = false; index = 0 }
            }; start()
        }; playing = true
    }
    DisposableEffect(path) { onDispose { runCatching { player?.release() } } }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (files.isEmpty()) { Icon(Icons.Default.DeleteOutline, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .3f)); Spacer(Modifier.height(8.dp)); Text("Audio unavailable") }
        else { Icon(Icons.Default.Mic, null, Modifier.size(56.dp), tint = BrandBlue); Spacer(Modifier.height(8.dp)); Text("${files.size} safe audio chunk${if (files.size == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); Spacer(Modifier.height(10.dp)); FilledTonalButton(onClick = {
            if (playing) { player?.pause(); playing = false } else if (player == null) startAt(index) else { player?.start(); playing = true }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Play") } }
    } }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }; Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
@Composable
private fun Empty(text: String) { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) } }
private fun formatElapsed(ms: Long): String { val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60; return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s) }
