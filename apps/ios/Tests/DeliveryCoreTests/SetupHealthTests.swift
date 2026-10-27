import Foundation
import XCTest
@testable import DeliveryCore

final class SetupHealthTests: XCTestCase {
    let start = Date(timeIntervalSince1970: 1_000)
    func event(_ stage: String, revision: Int? = 8, after seconds: Double = 0) -> DeliveryEvent {
        DeliveryEvent(stage: stage, revision: revision, detail: "test", timestamp: start.addingTimeInterval(seconds))
    }
    func testPermissionLossOverridesPastSuccess() {
        let history = [event("local_notification_scheduled"), event("shortcut_reported_applied", after: 10)]
        XCTAssertEqual(SetupHealth.assess(access: .denied, events: history).state, .blocked)
        XCTAssertEqual(SetupHealth.assess(access: .noPresentation, events: history).state, .blocked)
        XCTAssertEqual(SetupHealth.assess(access: .notRequested, events: history).state, .needsPermission)
    }
    func testManualRunAndFetchDoNotProveNotificationSetup() {
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: [event("shortcut_reported_applied")]).state, .untested)
        let history = [event("shortcut_reported_applied", after: -5), event("local_notification_scheduled"), event("image_ready", after: 10)]
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: history, now: start.addingTimeInterval(20)).state, .waiting)
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: history, now: start.addingTimeInterval(46)).state, .incomplete)
    }
    func testLatestProbeRequiresMatchingReceiptAfterScheduling() {
        let history = [event("local_notification_scheduled", revision: 7, after: -20),
                       event("shortcut_reported_applied", revision: 7, after: -10), event("local_notification_scheduled")]
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: history, now: start.addingTimeInterval(46)).state, .incomplete)
        let completed = history + [event("shortcut_reported_applied", after: 50)]
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: completed).state, .responded)
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: completed + [event("notification_settings_changed", after: 51)]).state, .settingsChanged)
    }
    func testRepairRunDoesNotMakeDisabledAutomationLookWorking() {
        let history = [event("local_notification_scheduled"), event("manual_shortcut_requested", after: 50),
                       event("shortcut_reported_applied", after: 55)]
        XCTAssertEqual(SetupHealth.assess(access: .allowed, events: history).state, .untested)
    }
    func testCleanupKeepsNewerMalformedAndUnrelatedNotices() {
        let notices = [
            WallpaperNotification(identifier: "old", revision: 7, isWallpaperUpdate: true),
            WallpaperNotification(identifier: "applied", revision: 8, isWallpaperUpdate: true),
            WallpaperNotification(identifier: "next", revision: 9, isWallpaperUpdate: true),
            WallpaperNotification(identifier: "invite", revision: 7, isWallpaperUpdate: false),
            WallpaperNotification(identifier: "missing", revision: nil, isWallpaperUpdate: true),
            WallpaperNotification(identifier: "invalid", revision: 0, isWallpaperUpdate: true)
        ]
        XCTAssertEqual(NotificationCleanupPolicy.identifiers(through: 8, from: notices), ["old", "applied"])
    }
}
