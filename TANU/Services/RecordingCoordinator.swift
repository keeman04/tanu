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
    private var transcriptionTasks: [Task<Void, Never>] = []
    private var onSegment: ((String) -> Void)?

    init(files: SecureFileStore) {
        self.files = files
        super.init()
    }

    func start(meetingID: String, onSegment: @escaping (String) -> Void) async throws {
        guard await requestMicrophonePermission() else { throw RecordingError.microphoneDenied }
        guard await speech.requestAuthorization() else { throw RecordingError.speechDenied }

        transcriptionTasks.forEach { $0.cancel() }
        transcriptionTasks.removeAll()
        transcriptionTail = nil

        try audioSession.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
        try? audioSession.setPreferredSampleRate(16_000)
        try audioSession.setActive(true)

        self.meetingID = meetingID
        self.onSegment = onSegment
        chunkIndex = 0
        elapsedSeconds = 0
        queuedChunks = 0
        lastError = nil
        try startChunk()
        isRecording = true
        startTimers()
    }

    @discardableResult
    func stop(finalizationTimeoutSeconds: UInt64 = 120) async -> Bool {
        guard meetingID != nil else { return true }
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

        let completed = await waitForTranscription(transcriptionTail, timeoutSeconds: finalizationTimeoutSeconds)
        if !completed {
            transcriptionTasks.forEach { $0.cancel() }
            lastError = "TANU stopped waiting after two minutes. The MOM uses the transcript completed so far, and the meeting audio is kept for recovery."
        }

        try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        onSegment = nil
        meetingID = nil
        transcriptionTail = nil
        transcriptionTasks.removeAll()
        return completed
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
        let task = Task { [weak self] in
            _ = await previous?.value
            guard let self else { return }
            defer { self.queuedChunks = max(0, self.queuedChunks - 1) }
            guard !Task.isCancelled else { return }
            do {
                let text = try await self.speech.transcribe(fileURL: url)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !Task.isCancelled else { return }
                if !text.isEmpty {
                    self.onSegment?(text)
                }
            } catch {
                guard !Task.isCancelled else { return }
                self.lastError = "A recording chunk is saved but could not be transcribed: \(error.localizedDescription)"
            }
        }
        transcriptionTail = task
        transcriptionTasks.append(task)
    }

    private func waitForTranscription(_ task: Task<Void, Never>?, timeoutSeconds: UInt64) async -> Bool {
        guard let task else { return true }
        return await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await task.value
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: timeoutSeconds * 1_000_000_000)
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }
}
