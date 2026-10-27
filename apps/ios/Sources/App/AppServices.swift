import Foundation

/// All entry points (UI, App Intent, notification callbacks) use the same services.
final class AppServices: @unchecked Sendable {
    static let shared = AppServices()
    let journal: DeliveryJournal
    let imageDirectory: URL

    private init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        imageDirectory = documents.appendingPathComponent("CanvasImages")
        do { journal = try DeliveryJournal(directory: documents) }
        catch { fatalError("Cannot open the delivery journal: \(error)") }
    }

    var server: String {
        get { UserDefaults.standard.string(forKey: "serverURL") ?? "http://127.0.0.1:8787" }
        set { UserDefaults.standard.set(newValue, forKey: "serverURL") }
    }

    func service() throws -> DeliveryService {
        DeliveryService(client: try CanvasClient(server: server), journal: journal, directory: imageDirectory)
    }

    func log(_ stage: String, revision: Int? = nil, detail: String) async {
        do { try await journal.append(DeliveryEvent(stage: stage, revision: revision, detail: detail)) }
        catch { NSLog("Delivery journal failed: %@", error.localizedDescription) }
    }
}
