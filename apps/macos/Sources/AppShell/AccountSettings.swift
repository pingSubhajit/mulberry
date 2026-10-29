import AppKit
import ImageIO
import ProductCore
import Sync
import UniformTypeIdentifiers

/// Profile, partner and canvas changes made in Settings, saved through the backend one at a time.
@MainActor
final class AccountSettings: ObservableObject {
    @Published private(set) var busy = false
    @Published var errorMessage: String?

    private let api: () -> BackendClient
    private let apply: (BootstrapState) -> Void

    init(api: @escaping () -> BackendClient, apply: @escaping (BootstrapState) -> Void) {
        self.api = api
        self.apply = apply
    }

    func updateName(_ name: String) async -> Bool {
        await perform { try await $0.updateDisplayName(name) }
    }

    func updatePhoto(_ jpeg: Data) async -> Bool {
        await perform { try await $0.updatePhoto(jpeg: jpeg) }
    }

    func updatePartner(name: String, anniversary: String, jpeg: Data?) async -> Bool {
        await perform { try await $0.updatePartner(name: name, anniversary: anniversary, jpeg: jpeg) }
    }

    func setDryBrush(_ enabled: Bool) async -> Bool {
        await perform { try await $0.setStrokeRenderMode(enabled ? "dry" : "round") }
    }

    func unpair() async -> Bool {
        await perform { try await $0.disconnect() }
    }

    private func perform(_ operation: (BackendClient) async throws -> BootstrapState) async -> Bool {
        guard !busy else { return false }
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            apply(try await operation(api()))
            return true
        } catch is CancellationError {
            return false
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}

/// A profile photo as the iPhone uploads it: at most 1024 pixels on its longer side, as JPEG.
enum ProfilePhoto {
    static let contentTypes: [UTType] = [.image]

    enum Failure: LocalizedError {
        case unreadable

        var errorDescription: String? { "That file isn't a photo Mulberry can use." }
    }

    static func jpeg(from url: URL) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                      kCGImageSourceCreateThumbnailFromImageAlways: true,
                      kCGImageSourceCreateThumbnailWithTransform: true,
                      kCGImageSourceThumbnailMaxPixelSize: 1024
                  ] as CFDictionary) else { throw Failure.unreadable }
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
                throw Failure.unreadable
            }
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { throw Failure.unreadable }
            return data as Data
        }.value
    }
}

/// Anniversaries are calendar days, read and written without a time zone shift.
enum AnniversaryDay {
    private static let formatter: DateFormatter = {
        let result = DateFormatter()
        result.locale = Locale(identifier: "en_US_POSIX")
        result.calendar = Calendar(identifier: .gregorian)
        result.dateFormat = "yyyy-MM-dd"
        return result
    }()

    static func date(_ value: String?) -> Date? { value.flatMap { formatter.date(from: $0) } }
    static func string(_ date: Date) -> String { formatter.string(from: date) }
}

extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
