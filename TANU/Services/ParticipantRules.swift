import Foundation

enum ParticipantRules {
    static func normalizeWhatsApp(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasPlus = trimmed.hasPrefix("+")
        let digits = trimmed.filter(\.isNumber)
        return hasPlus ? "+\(digits)" : digits
    }

    static func isValidWhatsApp(_ raw: String) -> Bool {
        let normalized = normalizeWhatsApp(raw)
        let digits = normalized.filter(\.isNumber)
        return (7...15).contains(digits.count)
    }

    static func isValidEmail(_ raw: String) -> Bool {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return true }
        let pattern = #"^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$"#
        return value.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }

    static func canSave(name: String, whatsapp: String, email: String) -> Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && isValidWhatsApp(whatsapp)
            && isValidEmail(email)
    }
}
