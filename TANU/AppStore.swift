import Combine
import Foundation

@MainActor
final class AppStore: ObservableObject {
    enum StoreError: LocalizedError {
        case invalidParticipant

        var errorDescription: String? {
            "Name and a valid WhatsApp/mobile number are required. Email is optional."
        }
    }

    @Published private(set) var participants: [Participant] = []
    @Published private(set) var meetings: [Meeting] = []
    @Published private(set) var activeMeetingID: UUID?
    @Published private(set) var recordingElapsed = 0
    @Published private(set) var queuedChunks = 0

    private let files: SecureFileStore
    private let keychain: KeychainStore
    private let recorder: RecordingCoordinator
    private let openAI = OpenAIService()
    private var cancellables = Set<AnyCancellable>()
    private let apiKeyName = "openai_api_key"

    init() {
        let files = SecureFileStore()
        self.files = files
        self.keychain = KeychainStore(service: "com.tanu.personal")
        self.recorder = RecordingCoordinator(files: files)
        self.participants = files.load([Participant].self, fileName: "participants.json") ?? []
        self.meetings = files.load([Meeting].self, fileName: "meetings.json") ?? []

        recorder.$elapsedSeconds
            .sink { [weak self] value in self?.recordingElapsed = value }
            .store(in: &cancellables)
        recorder.$queuedChunks
            .sink { [weak self] value in self?.queuedChunks = value }
            .store(in: &cancellables)
    }

    var activeMeeting: Meeting? {
        guard let activeMeetingID else { return nil }
        return meetings.first(where: { $0.id == activeMeetingID })
    }

    var hasAPIKey: Bool {
        !(keychain.string(for: apiKeyName) ?? "").isEmpty
    }

    func addParticipant(name: String, whatsapp: String, email: String, company: String) throws {
        guard ParticipantRules.canSave(name: name, whatsapp: whatsapp, email: email) else {
            throw StoreError.invalidParticipant
        }
        let participant = Participant(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            whatsapp: ParticipantRules.normalizeWhatsApp(whatsapp),
            email: email.trimmingCharacters(in: .whitespacesAndNewlines),
            company: company.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        participants.append(participant)
        persistParticipants()
    }

    func startMeeting(title: String, participantIDs: [UUID]) async {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let meeting = Meeting(
            title: cleanTitle.isEmpty ? "Meeting \(Date().formatted(date: .abbreviated, time: .shortened))" : cleanTitle,
            participantIDs: participantIDs,
            status: .recording
        )
        meetings.insert(meeting, at: 0)
        activeMeetingID = meeting.id
        persistMeetings()

        do {
            try await recorder.start(meetingID: meeting.id.uuidString) { [weak self] text in
                self?.appendTranscript(text, meetingID: meeting.id)
            }
        } catch {
            failMeeting(meeting.id, message: error.localizedDescription)
            activeMeetingID = nil
        }
    }

    func stopActiveMeeting() async {
        guard let id = activeMeetingID else { return }
        updateMeeting(id) { meeting in
            meeting.status = .transcribing
            meeting.endedAt = Date()
        }

        let transcriptionCompleted = await recorder.stop(finalizationTimeoutSeconds: 120)

        guard let index = meetings.firstIndex(where: { $0.id == id }) else { return }
        let transcript = meetings[index].transcript.map(\.text).joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transcript.isEmpty else {
            failMeeting(id, message: recorder.lastError ?? "No speech was recognized. TANU kept the audio chunks for recovery instead of creating an empty MOM.")
            activeMeetingID = nil
            return
        }

        var finalMOM = MomEngine.generate(from: transcript)
        if let apiKey = keychain.string(for: apiKeyName), !apiKey.isEmpty {
            if let cloudMOM = try? await openAI.generateMOM(transcript: transcript, apiKey: apiKey) {
                finalMOM = cloudMOM
            }
        }

        updateMeeting(id) { meeting in
            meeting.mom = finalMOM
            meeting.status = .ready
            meeting.errorMessage = recorder.lastError
        }

        if transcriptionCompleted, recorder.lastError == nil {
            recorder.cleanup(meetingID: id)
        }
        activeMeetingID = nil
    }

    func saveAPIKey(_ value: String) throws {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            try keychain.delete(apiKeyName)
        } else {
            try keychain.set(clean, for: apiKeyName)
        }
        objectWillChange.send()
    }

    func clearAPIKey() {
        try? keychain.delete(apiKeyName)
        objectWillChange.send()
    }

    func participantNames(for meeting: Meeting) -> [String] {
        meeting.participantIDs.compactMap { id in participants.first(where: { $0.id == id })?.name }
    }

    func shareText(for meeting: Meeting) -> String {
        guard let mom = meeting.mom else { return meeting.transcript.map(\.text).joined(separator: "\n") }
        var text = "TANU — Minutes of Meeting\n\(meeting.title)\n"
        let names = participantNames(for: meeting)
        if !names.isEmpty { text += "Participants: \(names.joined(separator: ", "))\n" }
        text += "\nSUMMARY\n\(mom.summary)\n"
        if !mom.decisions.isEmpty { text += "\nDECISIONS\n" + mom.decisions.map { "• \($0)" }.joined(separator: "\n") + "\n" }
        if !mom.actions.isEmpty { text += "\nACTIONS\n" + mom.actions.map { "• \($0)" }.joined(separator: "\n") + "\n" }
        if !mom.followUps.isEmpty { text += "\nFOLLOW-UP\n" + mom.followUps.map { "• \($0)" }.joined(separator: "\n") + "\n" }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func appendTranscript(_ text: String, meetingID: UUID) {
        updateMeeting(meetingID) { meeting in
            meeting.transcript.append(TranscriptSegment(text: text))
        }
    }

    private func failMeeting(_ id: UUID, message: String) {
        updateMeeting(id) { meeting in
            meeting.status = .failed
            meeting.errorMessage = message
            meeting.endedAt = meeting.endedAt ?? Date()
        }
    }

    private func updateMeeting(_ id: UUID, mutate: (inout Meeting) -> Void) {
        guard let index = meetings.firstIndex(where: { $0.id == id }) else { return }
        mutate(&meetings[index])
        persistMeetings()
    }

    private func persistParticipants() {
        try? files.save(participants, fileName: "participants.json")
    }

    private func persistMeetings() {
        try? files.save(meetings, fileName: "meetings.json")
    }
}
