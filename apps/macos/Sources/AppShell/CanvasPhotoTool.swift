@preconcurrency import AVFoundation
import AppKit
import CoreImage
import ImageIO
import ProductCore
import SwiftUI
import UniformTypeIdentifiers
import Vision

// MARK: Processing

/// Gets a taken or picked photo ready for the canvas, as the iPhone and iPad do: upright, at most
/// 1600 pixels on its long edge, as the JPEG that is uploaded; and cuts its subject out for the
/// sticker style. The full-size original never leaves the Mac.
enum CanvasPhotoProcessing {
    static let maxPixels: CGFloat = 1600

    /// A camera still at upload size, mirrored as the camera's preview shows it when `mirrored`.
    nonisolated static func prepared(_ image: CGImage, mirrored: Bool) -> CGImage? {
        let scale = min(1, maxPixels / CGFloat(max(1, max(image.width, image.height))))
        let width = Int((CGFloat(image.width) * scale).rounded()), height = Int((CGFloat(image.height) * scale).rounded())
        guard width > 0, height > 0,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        if mirrored {
            context.translateBy(x: CGFloat(width), y: 0)
            context.scaleBy(x: -1, y: 1)
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// A picked file decoded straight to upload size, turned upright by its orientation.
    nonisolated static func prepared(data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxPixels)
        ] as CFDictionary)
    }

    nonisolated static func jpeg(_ image: CGImage) -> Data? { encode(image, type: .jpeg, quality: 0.8) }

    nonisolated static func png(_ image: CGImage) -> Data? { encode(image, type: .png, quality: nil) }

    private nonisolated static func encode(_ image: CGImage, type: UTType, quality: Double?) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { return nil }
        let options = quality.map { [kCGImageDestinationLossyCompressionQuality: $0] as CFDictionary }
        CGImageDestinationAddImage(destination, image, options)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// The photo's subject, lifted as the Photos app lifts it, inside a white outline and cropped
    /// close around it. Nil when there is no clear subject.
    nonisolated static func cutout(_ cgImage: CGImage) -> CGImage? {
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up)
        let request = VNGenerateForegroundInstanceMaskRequest()
        guard (try? handler.perform([request])) != nil, let observation = request.results?.first,
              !observation.allInstances.isEmpty,
              let buffer = try? observation.generateScaledMaskForImage(forInstances: observation.allInstances, from: handler),
              let subjectBounds = bounds(of: buffer) else { return nil }

        let extent = CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height)
        let outline = max(6, max(subjectBounds.width, subjectBounds.height) * 0.028)
        let padded = extent.insetBy(dx: -outline * 2, dy: -outline * 2)
        let clear = CIImage(color: .clear).cropped(to: padded)
        let black = CIImage(color: .black).cropped(to: padded)

        let mask = CIImage(cvPixelBuffer: buffer).composited(over: black)
        let subject = CIImage(cgImage: cgImage).applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: clear, kCIInputMaskImageKey: mask
        ])
        // The outline: the subject's shape grown outward, its edge softened a little.
        let grown = mask.applyingFilter("CIMorphologyMaximum", parameters: [kCIInputRadiusKey: outline])
            .applyingGaussianBlur(sigma: 1.2)
            .cropped(to: padded)
        let white = CIImage(color: .white).cropped(to: padded).applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: clear, kCIInputMaskImageKey: grown
        ])
        let composed = subject.composited(over: white)

        // Vision's buffer runs top to bottom; Core Image's coordinates run bottom to top.
        let flipped = CGRect(x: subjectBounds.minX, y: extent.height - subjectBounds.maxY,
                             width: subjectBounds.width, height: subjectBounds.height)
        let crop = flipped.insetBy(dx: -outline * 1.6, dy: -outline * 1.6).integral.intersection(padded)
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return CIContext(options: [.workingColorSpace: space]).createCGImage(composed, from: crop, format: .RGBA8, colorSpace: space)
    }

    /// Where the mask is set, in the buffer's top-down pixels.
    private nonisolated static func bounds(of buffer: CVPixelBuffer) -> CGRect? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_OneComponent32Float,
              let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        var minX = width, minY = height, maxX = -1, maxY = -1
        for y in 0..<height {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: Float32.self)
            for x in 0..<width where row[x] > 0.5 {
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
    }
}

extension NSImage {
    /// The bitmap behind a decoded canvas photo.
    var canvasCGImage: CGImage? { cgImage(forProposedRect: nil, context: nil, hints: nil) }

    /// A photo at its pixel size, as the canvas's other photos are decoded.
    convenience init(canvasPhoto image: CGImage) {
        self.init(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}

// MARK: Camera

/// The Mac's camera, or a Continuity Camera iPhone: one still at a time.
@MainActor
final class CanvasCamera: ObservableObject {
    enum Access { case unknown, granted, denied }

    @Published private(set) var access: Access = .unknown
    @Published private(set) var running = false
    @Published private(set) var devices: [AVCaptureDevice] = []
    @Published private(set) var device: AVCaptureDevice?

    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var input: AVCaptureDeviceInput?
    private var capture: PhotoCapture?
    private let queue = DispatchQueue(label: "com.subhajit.mulberry.camera")

    /// Asks for access the first time, then starts the camera the Mac would choose.
    func start() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: access = .granted
        case .notDetermined: access = await AVCaptureDevice.requestAccess(for: .video) ? .granted : .denied
        default: access = .denied
        }
        guard access == .granted else { return }
        devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .continuityCamera, .external],
            mediaType: .video,
            position: .unspecified
        ).devices
        await use(device ?? AVCaptureDevice.default(for: .video) ?? devices.first)
    }

    /// Switches to another camera, such as an iPhone nearby.
    func use(_ next: AVCaptureDevice?) async {
        guard let next, let newInput = try? AVCaptureDeviceInput(device: next) else {
            running = false
            return
        }
        let session = session, output = output, oldInput = input
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            queue.async {
                session.beginConfiguration()
                session.sessionPreset = .photo
                if let oldInput { session.removeInput(oldInput) }
                if session.canAddInput(newInput) { session.addInput(newInput) }
                if session.outputs.isEmpty, session.canAddOutput(output) { session.addOutput(output) }
                session.commitConfiguration()
                if !session.isRunning { session.startRunning() }
                done.resume()
            }
        }
        input = newInput
        device = next
        running = session.isRunning
    }

    func stop() {
        let session = session
        queue.async { if session.isRunning { session.stopRunning() } }
        running = false
    }

    /// Whether the preview is mirrored, as a camera facing the person is.
    var mirrored: Bool { device?.position != .back && device?.deviceType != .continuityCamera }

    func takePhoto() async -> CGImage? {
        guard running else { return nil }
        let capture = PhotoCapture()
        self.capture = capture
        let output = output
        return await withCheckedContinuation { continuation in
            capture.continuation = continuation
            output.capturePhoto(with: AVCapturePhotoSettings(), delegate: capture)
        }
    }
}

/// Hands one captured still back.
private final class PhotoCapture: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    var continuation: CheckedContinuation<CGImage?, Never>?

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: (any Error)?) {
        continuation?.resume(returning: error == nil ? photo.cgImageRepresentation() : nil)
        continuation = nil
    }
}

private struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer = layer
        view.wantsLayer = true
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// The Camera tool's popover: the live camera, a file a click away, and the shutter. The photo goes
/// straight onto the canvas, selected, as on iPad.
struct CanvasCameraPanel: View {
    /// Uploads the photo and places it; throws when it couldn't be added.
    let add: (CGImage) async throws -> Void
    let cancel: () -> Void

    @StateObject private var camera = CanvasCamera()
    @State private var adding = false
    @State private var failed = false
    @State private var flash = false

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Color.black
                if camera.access == .granted {
                    CameraPreview(session: camera.session)
                }
                notice
                Color.white.opacity(flash ? 0.6 : 0).allowsHitTesting(false)
                if adding {
                    Color.black.opacity(0.35)
                    ProgressView().controlSize(.large)
                }
            }
            // 4:3, as the Mac's camera sees, and narrow enough for the window's portrait canvas.
            .frame(width: 400, height: 300)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .animation(.easeOut(duration: 0.2), value: adding)

            if failed {
                Text("Couldn’t add the photo. Try again.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
                Button("Cancel", role: .cancel, action: cancel)
                    .keyboardShortcut(.cancelAction)
                // Another camera, such as an iPhone nearby, as a compact menu so the buttons keep their words.
                if camera.devices.count > 1 {
                    Menu {
                        Picker("Camera", selection: Binding(get: { camera.device?.uniqueID }, set: { id in
                            Task { await camera.use(camera.devices.first { $0.uniqueID == id }) }
                        })) {
                            ForEach(camera.devices, id: \.uniqueID) { device in
                                Text(device.localizedName).tag(Optional(device.uniqueID))
                            }
                        }
                        .pickerStyle(.inline)
                    } label: {
                        Image(systemName: "arrow.triangle.2.circlepath.camera")
                    }
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Camera: \(camera.device?.localizedName ?? "")")
                    .accessibilityLabel("Camera")
                }
                Spacer()
                Button("Choose Photo…") { Self.chooseInPanel(add: add) }
                Button("Take Photo") { Task { await snap() } }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(!camera.running)
            }
            .disabled(adding)
        }
        .padding(20)
        .task { await camera.start() }
        .onDisappear { camera.stop() }
    }

    /// The open panel on its own: the popover closes as the panel takes focus, so the chosen photo
    /// is placed without it. A photo that can't be read or added beeps.
    private static func chooseInPanel(add: @escaping (CGImage) async throws -> Void) {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.begin { response in
            guard response == .OK, let url = panel.url else { return }
            Task { @MainActor in
                let accessing = url.startAccessingSecurityScopedResource()
                defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url),
                      let prepared = await Task.detached(priority: .userInitiated, operation: { CanvasPhotoProcessing.prepared(data: data) }).value
                else { NSSound.beep(); return }
                do { try await add(prepared) } catch { NSSound.beep() }
            }
        }
    }

    /// Why there is no picture: access not given, or no camera.
    @ViewBuilder private var notice: some View {
        if camera.access == .denied {
            ContentUnavailableView {
                Label("Camera access is off", systemImage: "camera")
            } description: {
                Text("Allow it in System Settings to snap photos for your canvas.")
            } actions: {
                Button("Open System Settings") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
        } else if camera.access == .granted, !camera.running, camera.devices.isEmpty {
            ContentUnavailableView("No camera", systemImage: "video.slash",
                                   description: Text("Choose a photo instead."))
        }
    }

    private func snap() async {
        withAnimation(.easeOut(duration: 0.06)) { flash = true }
        let mirrored = camera.mirrored
        let still = await camera.takePhoto()
        withAnimation(.easeOut(duration: 0.2)) { flash = false }
        guard let still else { return }
        let prepared = await Task.detached(priority: .userInitiated) {
            CanvasPhotoProcessing.prepared(still, mirrored: mirrored)
        }.value
        guard let prepared else { return }
        await place(prepared)
    }

    private func place(_ image: CGImage) async {
        adding = true
        failed = false
        do {
            try await add(image)
        } catch {
            adding = false
            failed = true
            NSSound.beep()
        }
    }
}

// MARK: Photo bar

/// The selected photo's bar, as the iPad's: its three styles, Straighten while tilted, Duplicate
/// and Delete.
struct PhotoBar: View {
    let photo: CanvasPhotoElement
    /// The sticker style's cut-out is being made.
    let cuttingOut: Bool
    /// The photo has no clear subject to cut out.
    let noSubject: Bool
    let onStyle: (CanvasPhotoStyle) -> Void
    let onStraighten: () -> Void
    let onDuplicate: () -> Void
    let onDelete: () -> Void

    private var tilted: Bool { abs(photo.rotationRad.remainder(dividingBy: 2 * .pi)) > 0.001 }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(CanvasPhotoStyle.allCases, id: \.self) { style in
                if style == .sticker, cuttingOut {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 28, height: 28)
                        .accessibilityLabel(Self.title(style))
                } else {
                    FormatButton(title: Self.title(style), symbol: Self.symbol(style), selected: photo.style == style,
                                 enabled: !cuttingOut && (style != .sticker || photo.cutoutPhotoId != nil || !noSubject)) {
                        onStyle(style)
                    }
                }
            }
            if tilted {
                FormatButton(title: "Straighten", symbol: "level", action: onStraighten)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
            FormatButton(title: "Duplicate (⌘D)", symbol: "plus.square.on.square", action: onDuplicate)
            Rectangle().fill(.primary.opacity(0.12)).frame(width: 1, height: 18).padding(.horizontal, 3)
            FormatButton(title: "Delete (⌫)", symbol: "trash", action: onDelete)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .glassEffect(.regular, in: Capsule())
        .fixedSize()
        .animation(.smooth(duration: 0.2), value: tilted)
    }

    /// The same names and symbols as on iPhone and iPad.
    static func title(_ style: CanvasPhotoStyle) -> String {
        switch style {
        case .frame: "Simple"
        case .polaroid: "Polaroid"
        case .sticker: "Sticker"
        }
    }

    static func symbol(_ style: CanvasPhotoStyle) -> String {
        switch style {
        case .frame: "photo.artframe"
        case .polaroid: "rectangle.portrait.tophalf.inset.filled"
        case .sticker: "person.and.background.dotted"
        }
    }
}
