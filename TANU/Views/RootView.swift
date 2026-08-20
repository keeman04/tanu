import SwiftUI

private extension Color {
    static let tanuBlue = Color(red: 37/255, green: 99/255, blue: 235/255)
    static let tanuPurple = Color(red: 124/255, green: 58/255, blue: 237/255)
    static let tanuPink = Color(red: 236/255, green: 72/255, blue: 153/255)
    static let tanuInk = Color(red: 15/255, green: 23/255, blue: 42/255)
}

struct RootView: View {
    var body: some View {
        TabView {
            NavigationStack { HomeView() }
                .tabItem { Label("Home", systemImage: "house.fill") }
            NavigationStack { MeetingsView() }
                .tabItem { Label("Meetings", systemImage: "folder.fill") }
            NavigationStack { PeopleView() }
                .tabItem { Label("People", systemImage: "person.2.fill") }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .tint(.tanuBlue)
    }
}

private struct HomeView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showNewMeeting = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 15)
                            .fill(LinearGradient(colors: [.tanuBlue, .tanuPurple, .tanuPink], startPoint: .topLeading, endPoint: .bottomTrailing))
                        Image(systemName: "waveform.and.mic")
                            .font(.title2.bold())
                            .foregroundStyle(.white)
                    }
                    .frame(width: 52, height: 52)
                    VStack(alignment: .leading) {
                        Text("TANU").font(.title2.bold()).foregroundStyle(Color.tanuInk)
                        Text("iPhone meeting assistant").font(.caption).foregroundStyle(.secondary)
                    }
                }

                if let active = store.activeMeeting {
                    RecordingCard(meeting: active)
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("What should TANU remember?")
                            .font(.title.bold())
                        Text("Record an in-person meeting. TANU transcribes short audio chunks while you talk, then creates the MOM when you stop.")
                            .foregroundStyle(.secondary)
                        Button {
                            showNewMeeting = true
                        } label: {
                            Label("Start Meeting", systemImage: "mic.fill")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.tanuBlue)
                    }
                    .padding(20)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 24))
                }

                Text("Recent meetings").font(.title3.bold())
                if store.meetings.isEmpty {
                    ContentUnavailableView("No meetings yet", systemImage: "waveform", description: Text("Your completed meetings will appear here."))
                } else {
                    ForEach(store.meetings.prefix(6)) { meeting in
                        NavigationLink {
                            MeetingDetailView(meetingID: meeting.id)
                        } label: {
                            MeetingRow(meeting: meeting)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding()
        }
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showNewMeeting) {
            NewMeetingView()
        }
    }
}

private struct RecordingCard: View {
    @EnvironmentObject private var store: AppStore
    let meeting: Meeting

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tanuPink)
            Text(meeting.title).font(.title3.bold())
            Text(formatDuration(store.recordingElapsed))
                .font(.system(.title2, design: .monospaced).bold())
                .foregroundStyle(.red)
            Text("TANU is recording and transcribing in short chunks.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if store.queuedChunks > 0 {
                ProgressView("Transcribing \(store.queuedChunks) audio chunk\(store.queuedChunks == 1 ? "" : "s")…")
                    .font(.caption)
            }
            if let last = meeting.transcript.last?.text {
                Text(last)
                    .font(.callout)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
            }
            Button(role: .destructive) {
                Task { await store.stopActiveMeeting() }
            } label: {
                Label("Stop & Create MOM", systemImage: "stop.fill")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(20)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 24))
    }
}

private struct NewMeetingView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var selected = Set<UUID>()
    @State private var showParticipant = false

    var body: some View {
        NavigationStack {
            List {
                Section("Meeting") {
                    TextField("Meeting title", text: $title)
                }
                Section("Participants") {
                    if store.participants.isEmpty {
                        Text("No saved participants yet.").foregroundStyle(.secondary)
                    }
                    ForEach(store.participants) { participant in
                        Button {
                            if selected.contains(participant.id) { selected.remove(participant.id) }
                            else { selected.insert(participant.id) }
                        } label: {
                            HStack {
                                Image(systemName: selected.contains(participant.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected.contains(participant.id) ? Color.tanuBlue : .secondary)
                                VStack(alignment: .leading) {
                                    Text(participant.name).foregroundStyle(.primary)
                                    Text(participant.whatsapp).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    Button { showParticipant = true } label: {
                        Label("Add participant from Contacts", systemImage: "person.crop.circle.badge.plus")
                    }
                }
                Section {
                    Text("Every saved participant requires a name and WhatsApp/mobile number. Email is optional.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("New Meeting")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") {
                        let ids = Array(selected)
                        Task {
                            await store.startMeeting(title: title, participantIDs: ids)
                            dismiss()
                        }
                    }
                }
            }
            .sheet(isPresented: $showParticipant) { ParticipantFormView() }
        }
    }
}

private struct MeetingsView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        List {
            if store.meetings.isEmpty {
                ContentUnavailableView("No meetings", systemImage: "folder", description: Text("Start a meeting from Home."))
            } else {
                ForEach(store.meetings) { meeting in
                    NavigationLink {
                        MeetingDetailView(meetingID: meeting.id)
                    } label: {
                        MeetingRow(meeting: meeting)
                    }
                }
            }
        }
        .navigationTitle("Meetings")
    }
}

private struct MeetingRow: View {
    let meeting: Meeting

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: meeting.status == .ready ? "checkmark.circle.fill" : meeting.status == .failed ? "exclamationmark.triangle.fill" : "sparkles")
                .foregroundStyle(meeting.status == .failed ? .red : Color.tanuBlue)
            VStack(alignment: .leading, spacing: 3) {
                Text(meeting.title).font(.headline).foregroundStyle(.primary)
                Text(meeting.startedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption).foregroundStyle(.secondary)
                Text(statusLabel(meeting.status)).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

private struct MeetingDetailView: View {
    @EnvironmentObject private var store: AppStore
    let meetingID: UUID

    var body: some View {
        if let meeting = store.meetings.first(where: { $0.id == meetingID }) {
            List {
                if let error = meeting.errorMessage, !error.isEmpty {
                    Section("Status") {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(meeting.status == .failed ? .red : .secondary)
                    }
                }
                if let mom = meeting.mom {
                    Section("Summary") { Text(mom.summary) }
                    if !mom.decisions.isEmpty {
                        Section("Decisions") { ForEach(mom.decisions, id: \.self) { Text("• \($0)") } }
                    }
                    if !mom.actions.isEmpty {
                        Section("Actions") { ForEach(mom.actions, id: \.self) { Text("• \($0)") } }
                    }
                    if !mom.followUps.isEmpty {
                        Section("Follow-up") { ForEach(mom.followUps, id: \.self) { Text("• \($0)") } }
                    }
                    Section {
                        ShareLink(item: store.shareText(for: meeting)) {
                            Label("Share MOM / WhatsApp", systemImage: "square.and.arrow.up")
                        }
                    } footer: {
                        Text("The iOS share sheet lets you choose WhatsApp, Mail, Messages, or another installed app.")
                    }
                    Section("MOM engine") {
                        Text(mom.source == "openai" ? "OpenAI-enhanced with on-device fallback" : "On-device deterministic fallback")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } else if meeting.status == .transcribing {
                    Section { ProgressView("Finishing transcript and MOM…") }
                }

                Section("Transcript") {
                    if meeting.transcript.isEmpty {
                        Text("No transcript available yet.").foregroundStyle(.secondary)
                    } else {
                        ForEach(meeting.transcript) { segment in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(segment.createdAt.formatted(date: .omitted, time: .shortened))
                                    .font(.caption2).foregroundStyle(.secondary)
                                Text(segment.text)
                            }
                        }
                    }
                }
            }
            .navigationTitle(meeting.title)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("Meeting not found", systemImage: "questionmark.folder")
        }
    }
}

private struct PeopleView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showParticipant = false

    var body: some View {
        List {
            Section {
                Text("Name and WhatsApp/mobile number are required for every participant. Email is optional.")
                    .font(.caption).foregroundStyle(.secondary)
                Button { showParticipant = true } label: {
                    Label("Add participant", systemImage: "person.badge.plus")
                }
            }
            Section("Saved people") {
                if store.participants.isEmpty {
                    Text("No participants saved.").foregroundStyle(.secondary)
                } else {
                    ForEach(store.participants) { participant in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(participant.name).font(.headline)
                            Text("WhatsApp: \(participant.whatsapp)").font(.caption).foregroundStyle(.secondary)
                            if !participant.email.isEmpty { Text(participant.email).font(.caption).foregroundStyle(.secondary) }
                            if !participant.company.isEmpty { Text(participant.company).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        }
        .navigationTitle("People")
        .sheet(isPresented: $showParticipant) { ParticipantFormView() }
    }
}

private struct ParticipantFormView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var whatsapp = ""
    @State private var email = ""
    @State private var company = ""
    @State private var showPicker = false
    @State private var attempted = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button { showPicker = true } label: {
                        Label("Choose from Contacts", systemImage: "person.crop.circle.badge.checkmark")
                    }
                }
                Section("Participant") {
                    TextField("Name *", text: $name)
                    TextField("WhatsApp / mobile number *", text: $whatsapp)
                        .keyboardType(.phonePad)
                    TextField("Email (optional)", text: $email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                    TextField("Company (optional)", text: $company)
                }
                if attempted && !ParticipantRules.canSave(name: name, whatsapp: whatsapp, email: email) {
                    Section {
                        Text("Enter a name and a valid 7–15 digit WhatsApp/mobile number. If email is entered, it must be valid.")
                            .font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Add Participant")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        attempted = true
                        do {
                            try store.addParticipant(name: name, whatsapp: whatsapp, email: email, company: company)
                            dismiss()
                        } catch { }
                    }
                }
            }
            .sheet(isPresented: $showPicker) {
                ContactPicker { contact in
                    name = contact.name
                    whatsapp = contact.phone
                    if email.isEmpty { email = contact.email }
                    showPicker = false
                }
                .ignoresSafeArea()
            }
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var apiKey = ""
    @State private var message = ""

    var body: some View {
        Form {
            Section("MOM generation") {
                Label(store.hasAPIKey ? "OpenAI connected" : "On-device MOM fallback active", systemImage: store.hasAPIKey ? "checkmark.shield.fill" : "iphone")
                    .foregroundStyle(store.hasAPIKey ? Color.tanuBlue : .primary)
                Text("TANU always creates a deterministic local MOM when a transcript exists. An OpenAI key can optionally improve the final MOM.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("OpenAI API key") {
                SecureField("Paste personal API key", text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Save securely in Keychain") {
                    do {
                        try store.saveAPIKey(apiKey)
                        apiKey = ""
                        message = "Saved in iOS Keychain."
                    } catch {
                        message = error.localizedDescription
                    }
                }
                if store.hasAPIKey {
                    Button("Disconnect OpenAI", role: .destructive) {
                        store.clearAPIKey()
                        message = "OpenAI key removed."
                    }
                }
                if !message.isEmpty { Text(message).font(.caption).foregroundStyle(.secondary) }
            }
            Section("Security") {
                Label("Keychain-protected API secret", systemImage: "key.fill")
                Label("Protected private meeting files", systemImage: "lock.doc.fill")
                Label("User-selected Contacts only", systemImage: "person.crop.circle")
                Text("No API key is hard-coded into the repository or default app binary.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Version") { Text("TANU iOS 1.0.0") }
        }
        .navigationTitle("Settings")
    }
}

private func statusLabel(_ status: MeetingStatus) -> String {
    switch status {
    case .recording: return "Recording"
    case .transcribing: return "Creating MOM"
    case .ready: return "MOM ready"
    case .failed: return "Needs attention"
    }
}

private func formatDuration(_ seconds: Int) -> String {
    let hours = seconds / 3600
    let minutes = (seconds % 3600) / 60
    let secs = seconds % 60
    if hours > 0 { return String(format: "%02d:%02d:%02d", hours, minutes, secs) }
    return String(format: "%02d:%02d", minutes, secs)
}
