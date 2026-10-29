import Foundation
import XCTest
@testable import DeliveryCore

final class DeliveryCoreTests: XCTestCase {
    func testJournalPersistsDistinctDeliveryStages() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let journal = try DeliveryJournal(directory: folder)
        try await journal.append(DeliveryEvent(stage: "image_ready", revision: 3, detail: "fetch only"))
        let restored = try DeliveryJournal(directory: folder)
        let events = await restored.all()
        XCTAssertEqual(events.map(\.stage), ["image_ready"])
        XCTAssertFalse(events.contains { $0.stage == "shortcut_reported_applied" })
    }
}
