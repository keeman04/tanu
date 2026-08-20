import AVFoundation
import Combine
import Foundation

@MainActor
final class RecordingCoordinator: NSObject, ObservableObject, AVAudioRecorderDelegate {
    enum RecordingError: LocalizedError {
        case microphoneDenied
        case speechDenied
        case recorderFailed

        var errorDescription: String? {
            switch self {
            case .microphoneDenied: return "Microphone permission is required to record a meeting."
            case .speechDenied: return "Speech recognition permission is required to create a transcript."
            case .recorderFailed: return "TANU could not start the audio recorder."
            }
        }
    }

    @Published private(set) var isRecording = false
    @Published private(set) var elapsedSeconds = 0
    @Published private(set) var queuedChunks = 0
    @Published private(set) var lastError: String?

    private let files: SecureFileStore
    private let speech = SpeechTranscriber()
    private let audioSession = AVAudioSession.sharedInstance()
    private var recorder: AVAudioRecorder?
    private var currentChunkURL: URL?
    private var meetingID: String?
    private var chunkIndex = 0
    private var rotateTimer: DispatchSourceTimer?
    private var elapsedTask: Task<Void, Never>?
    private var transcriptionTail: Task<Void, Never>?
    private var onSegment: ((String) -> Void)?

    init(files: SecureFileStore) {
        self.files = files
        super.init()
    }

    func start(meetingID: String, onSegment: @escaping (String) -> Void) async throws {
        guard await requestMicrophonePermission() else { throw RecordingError.microphoneDenied }
        guard await speech.requestAuthorization() else { throw RecordingError.speechDenied }

        try audioSession.setCategory(.record, mode: .measurement, options: [.allowBluetoothHFP])
        try? audioSession.setPreferredSampleRate(16_000)
        try audioSession.setActive(true)

        self.meetingID = meetingID
        self.onSegment = onSegment
        chunkIndex = 0
        elapsedSeconds = 0
        queuedChunks = 0
        lastError = nil
        transcriptionTail = nil
        try startChunk()
        isRecording = true
        startTimers()
    }

    func stop() async {
        guard meetingID != nil else { return }
        rotateTimer?.cancel()
        rotateTimer = nil
        elapsedTask?.cancel()
        elapsedTask = nil
        isRecording = false

        let finalURL = currentChunkURL
        recorder?.stop()
        recorder = nil
        currentChunkURL = nil
        if let finalURL { enqueueTranscription(finalURL) }

        let tail = transcriptionTail
        await tail?.value
        try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        onSegment = nil
    }

    func cleanup(meetingID: UUID) {
        files.cleanupRecordings(meetingID: meetingID.uuidString)
    }

    private func startTimers() {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "com.tanu.recording.rotate", qos: .utility))
        timer.schedule(deadline: .now() + 55, repeating: 55)
        timer.setEventHandler { [weak self] in
            Task { @MainActor in
                self?.rotateChunk()
            }
        }
        rotateTimer = timer
        timer.resume()

        elapsedTask = Task { @MainActor [weak self] in
            while let self, self.isRecording, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if !Task.isCancelled, self.isRecording {
                    self.elapsedSeconds += 1
                }
            }
        }
    }

    private func rotateChunk() {
        guard isRecording else { return }
        let completedURL = currentChunkURL
        recorder?.stop()
        recorder = nil
        currentChunkURL = nil
        if let completedURL { enqueueTranscription(completedURL) }
        do {
            try startChunk()
        } catch {
            lastError = error.localizedDescription
            isRecording = false
            rotateTimer?.cancel()
            rotateTimer = nil
        }
    }

    private func startChunk() throws {
        guard let meetingID else { throw RecordingError.recorderFailed }
        let directory = try files.recordingDirectory(meetingID: meetingID)
        let url = directory.appendingPathComponent(String(format: "chunk-%05d.m4a", chunkIndex))
        chunkIndex += 1
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 48_000,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        let newRecorder = try AVAudioRecorder(url: url, settings: settings)
        newRecorder.delegate = self
        newRecorder.isMeteringEnabled = false
        newRecorder.prepareToRecord()
        guard newRecorder.record() else { throw RecordingError.recorderFailed }
        files.protectRecording(at: url)
        recorder = newRecorder
        currentChunkURL = url
    }

    private func enqueueTranscription(_ url: URL) {
        let previous = transcriptionTail
        queuedChunks += 1
        transcriptionTail = Task { [weak self] in
            _ = await previous?.value
            guard let self else { return }
            defer { self.queuedChunks = max(0, self.queuedChunks - 1) }
            do {
                let text = try await self.speech.transcribe(fileURL: url)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty {
                    self.onSegment?(text)
                }
            } catch {
                self.lastError = "A recording chunk is saved but could not be transcribed yet: \(error.localizedDescription)"
            }
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        switch audioSession.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                audioSession.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }
}
