import Foundation

final class OpenAIService {
    enum APIError: LocalizedError {
        case invalidResponse
        case requestFailed(Int)
        case invalidMOM

        var errorDescription: String? {
            switch self {
            case .invalidResponse: return "OpenAI returned an invalid response."
            case .requestFailed(let code): return "OpenAI request failed with status \(code)."
            case .invalidMOM: return "OpenAI returned notes TANU could not parse."
            }
        }
    }

    func generateMOM(transcript: String, apiKey: String) async throws -> MOM {
        let boundedTranscript = String(transcript.prefix(120_000))
        let prompt = """
        You are TANU, a meeting minutes assistant. Convert the transcript below into concise minutes of meeting.
        Do not invent names, dates, decisions, owners, deadlines, or commitments that are not present in the transcript.
        Keep the summary concise and put concrete commitments in actions. Put explicit decisions in decisions and unresolved next steps in followUps.

        TRANSCRIPT:
        \(boundedTranscript)
        """

        let schema: [String: Any] = [
            "type": "object",
            "properties": [
                "summary": ["type": "string"],
                "decisions": ["type": "array", "items": ["type": "string"]],
                "actions": ["type": "array", "items": ["type": "string"]],
                "followUps": ["type": "array", "items": ["type": "string"]]
            ],
            "required": ["summary", "decisions", "actions", "followUps"],
            "additionalProperties": false
        ]

        let body: [String: Any] = [
            "model": "gpt-5.4-mini",
            "input": prompt,
            "store": false,
            "reasoning": ["effort": "none"],
            "text": [
                "format": [
                    "type": "json_schema",
                    "name": "tanu_mom",
                    "strict": true,
                    "schema": schema
                ]
            ]
        ]

        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/responses")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 75
        let session = URLSession(configuration: configuration)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.requestFailed(http.statusCode) }

        let envelope = try JSONDecoder().decode(ResponsesEnvelope.self, from: data)
        let outputText = envelope.output
            .flatMap { $0.content ?? [] }
            .first(where: { $0.type == "output_text" })?.text
        guard let outputText else { throw APIError.invalidResponse }

        guard let payloadData = outputText.data(using: .utf8),
              let payload = try? JSONDecoder().decode(MOMPayload.self, from: payloadData),
              !payload.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw APIError.invalidMOM
        }

        return MOM(
            summary: payload.summary,
            decisions: payload.decisions,
            actions: payload.actions,
            followUps: payload.followUps,
            generatedAt: Date(),
            source: "openai"
        )
    }
}

private struct ResponsesEnvelope: Decodable {
    let output: [OutputItem]

    struct OutputItem: Decodable {
        let content: [ContentItem]?
    }

    struct ContentItem: Decodable {
        let type: String
        let text: String?
    }
}

private struct MOMPayload: Decodable {
    let summary: String
    let decisions: [String]
    let actions: [String]
    let followUps: [String]
}
