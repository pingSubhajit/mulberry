import AppIntents
import UniformTypeIdentifiers

struct GetLatestWallpaperIntent: AppIntent {
    static var title: LocalizedStringResource = "Get latest Mulberry wallpaper"
    static var description = IntentDescription("Combine your shared canvas with the private background you chose in Mulberry. Use Set Wallpaper after this action.")
    static var openAppWhenRun: Bool = false
    static var authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed

    func perform() async throws -> some IntentResult & ReturnsValue<IntentFile> {
        let image = try await ProductWallpaperService.shared.prepare()
        let file = IntentFile(fileURL: image, filename: image.lastPathComponent, type: .png)
        return .result(value: file)
    }
}

struct RecordWallpaperResultIntent: AppIntent {
    static var title: LocalizedStringResource = "Record Mulberry wallpaper result"
    static var description = IntentDescription("Place AFTER Set Wallpaper. Records its completion, not visual proof.")
    static var openAppWhenRun: Bool = false
    static var authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed

    @Parameter(title: "Image") var image: IntentFile
    static var parameterSummary: some ParameterSummary { Summary("Record completion for \(\.$image)") }

    func perform() async throws -> some IntentResult {
        try await ProductWallpaperService.shared.record(filename: image.filename)
        return .result()
    }
}

struct MulberryShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: GetLatestWallpaperIntent(), phrases: ["Get the latest wallpaper from \(.applicationName)"],
                    shortTitle: "Get wallpaper", systemImageName: "photo")
    }
}
