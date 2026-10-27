import Foundation
import XCTest
@testable import DeliveryCore

final class DeliveryCoreTests: XCTestCase {
    func testServerRejectsCredentialsAndInvalidSchemes() throws {
        for server in ["file:///tmp/image", "http://name:secret@localhost", "http://localhost?a=1", "not a url"] {
            XCTAssertThrowsError(try CanvasClient(server: server))
        }
        _ = try CanvasClient(server: "http://127.0.0.1:8787")
    }

    func testReceiptUsesOriginalRevisionNotCurrentServerState() throws {
        XCTAssertEqual(try DeliveryService.revision(from: "mulberry-r12.png"), 12)
        for name in ["image.png", "mulberry-r0.png", "mulberry-r1.png.old", "mulberry-r-1.png"] {
            XCTAssertThrowsError(try DeliveryService.revision(from: name))
        }
    }

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

    func testFailedFetchNeverReportsImageOrWallpaperSuccess() async throws {
        guard let address = ProcessInfo.processInfo.environment["MULBERRY_TEST_SERVER"] else {
            throw XCTSkip("Set MULBERRY_TEST_SERVER to run the HTTP integration test")
        }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let journal = try DeliveryJournal(directory: folder)
        let service = DeliveryService(client: try CanvasClient(server: address + "/missing"),
                                      journal: journal, directory: folder)
        do {
            _ = try await service.prepare(source: "failure_test")
            XCTFail("Expected HTTP failure")
        } catch { }
        let stages = await journal.all().map(\.stage)
        XCTAssertTrue(stages.contains("fetch_failed"))
        XCTAssertFalse(stages.contains("image_ready"))
        XCTAssertFalse(stages.contains("shortcut_reported_applied"))
    }

    // Opt in to the running mock service. These exercise real HTTP and file persistence.
    func testMockServerRoundTrip() async throws {
        guard let address = ProcessInfo.processInfo.environment["MULBERRY_TEST_SERVER"] else {
            throw XCTSkip("Set MULBERRY_TEST_SERVER to run the HTTP integration test")
        }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let client = try CanvasClient(server: address)
        let published = try await client.publish()
        let journal = try DeliveryJournal(directory: folder)
        let service = DeliveryService(client: client, journal: journal, directory: folder)
        let image = try await service.prepare(source: "integration_test")
        XCTAssertEqual(image.revision, published.revision)
        XCTAssertTrue(try Data(contentsOf: image.fileURL).starts(with: [137, 80, 78, 71]))
        // Publish a newer canvas before receiving the receipt for the old image.
        _ = try await client.publish()
        try await service.recordShortcutCompletion(filename: image.fileURL.lastPathComponent)
        let events = await journal.all()
        XCTAssertEqual(events.last?.revision, published.revision)
        XCTAssertEqual(events.last?.stage, "shortcut_reported_applied")
    }
}
