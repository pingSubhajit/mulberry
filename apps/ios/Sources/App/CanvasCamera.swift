import AVFoundation
import OSLog
import SwiftUI
import UIKit

private let log = Logger(subsystem: "com.subhajit.mulberry", category: "CanvasCamera")

/// The camera behind the canvas's Camera tool: front or back, flash on or off, one still at a time.
/// The session runs on its own queue; everything published is on the main actor.
@MainActor
final class CanvasCamera: NSObject, ObservableObject {
    enum Access { case unknown, granted, denied }

    @Published private(set) var access: Access = .unknown
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published var flashOn = false
    @Published private(set) var hasFlash = false
    /// Whether the preview is live, so the shutter only works on a running camera.
    @Published private(set) var running = false
    /// Why the camera stopped while on screen, such as another app using it.
    @Published private(set) var interruption: AVCaptureSession.InterruptionReason?
    /// Whether the camera should run: closing it while it is still starting stops it once started.
    private var wanted = false
    private var observers: [NSObjectProtocol] = []

    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "mulberry.canvas-camera")
    private var input: AVCaptureDeviceInput?
    private var configured = false
    private var pending: CheckedContinuation<UIImage?, Never>?

    /// Whether iOS will still show its own prompt; once answered, only Settings can change it.
    var canAsk: Bool { AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined }

    func start() async {
        wanted = true
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: access = .granted
        case .notDetermined: access = await AVCaptureDevice.requestAccess(for: .video) ? .granted : .denied
        default: access = .denied
        }
        log.info("camera access \(String(describing: self.access), privacy: .public)")
        guard access == .granted, wanted else { return }
        observe()
        let position = position
        let session = session, output = output
        let configured = configured
        let result: (AVCaptureDeviceInput?, Bool) = await withCheckedContinuation { continuation in
            queue.async {
                var input: AVCaptureDeviceInput?
                if !configured {
                    session.beginConfiguration()
                    session.sessionPreset = .photo
                    input = Self.input(for: position)
                    if let input, session.canAddInput(input) { session.addInput(input) }
                    if session.canAddOutput(output) { session.addOutput(output) }
                    output.maxPhotoQualityPrioritization = .balanced
                    session.commitConfiguration()
                }
                if !session.isRunning { session.startRunning() }
                log.info("camera session running=\(session.isRunning, privacy: .public) inputs=\(session.inputs.count, privacy: .public) outputs=\(session.outputs.count, privacy: .public) device=\(input?.device.localizedName ?? "kept", privacy: .public)")
                continuation.resume(returning: (input, session.isRunning))
            }
        }
        if !configured {
            self.configured = true
            input = result.0
            hasFlash = result.0?.device.hasFlash ?? false
        }
        running = result.1
        // Closed while it was starting.
        if !wanted { stop() }
    }

    func stop() {
        wanted = false
        let session = session
        running = false
        queue.async { if session.isRunning { session.stopRunning() } }
    }

    /// Follows interruptions and failures, so the screen can say why the camera went dark.
    private func observe() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int
            let reason = raw.flatMap(AVCaptureSession.InterruptionReason.init(rawValue:))
            log.error("camera interrupted reason=\(raw ?? -1, privacy: .public)")
            MainActor.assumeIsolated { self?.interruption = reason }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) { [weak self] _ in
            log.info("camera interruption ended")
            MainActor.assumeIsolated { self?.interruption = nil }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
            log.error("camera runtime error \(error?.localizedDescription ?? "unknown", privacy: .public)")
            // A media services reset is recoverable: start again.
            guard error?.code == .mediaServicesWereReset else { return }
            MainActor.assumeIsolated { if self?.wanted == true { Task { await self?.start() } } }
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        let session = session
        if session.isRunning { DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() } }
    }

    /// Turns to the other camera, keeping the session running.
    func flip() {
        guard configured else { return }
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        let session = session, current = input
        queue.async {
            guard let replacement = Self.input(for: next) else { return }
            session.beginConfiguration()
            if let current { session.removeInput(current) }
            if session.canAddInput(replacement) { session.addInput(replacement) } else if let current { session.addInput(current) }
            session.commitConfiguration()
            Task { @MainActor in
                self.input = replacement
                self.position = next
                self.hasFlash = replacement.device.hasFlash
            }
        }
    }

    /// Takes a still, mirrored for the front camera as the preview shows it.
    func capture() async -> UIImage? {
        guard running, pending == nil else { return nil }
        let output = output, front = position == .front
        let settings = AVCapturePhotoSettings()
        if hasFlash, output.supportedFlashModes.contains(.on) { settings.flashMode = flashOn ? .on : .off }
        settings.photoQualityPrioritization = .balanced
        return await withCheckedContinuation { continuation in
            pending = continuation
            queue.async {
                if let connection = output.connection(with: .video) {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.isVideoMirrored = front
                }
                output.capturePhoto(with: settings, delegate: self)
            }
        }
    }

    private nonisolated static func input(for position: AVCaptureDevice.Position) -> AVCaptureDeviceInput? {
        let types: [AVCaptureDevice.DeviceType] = position == .back
            ? [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
            : [.builtInTrueDepthCamera, .builtInWideAngleCamera]
        let device = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: position).devices.first
        return device.flatMap { try? AVCaptureDeviceInput(device: $0) }
    }

    private func finish(_ image: UIImage?) {
        pending?.resume(returning: image)
        pending = nil
    }
}

extension CanvasCamera: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = error == nil ? photo.fileDataRepresentation().flatMap(UIImage.init(data:)) : nil
        Task { @MainActor in self.finish(image) }
    }
}

/// The live camera image, filling its frame.
struct CanvasCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
