import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO
import UIKit
import UniformTypeIdentifiers
import Vision

/// Gets a taken or picked photo ready for the canvas: upright, at most 1600 pixels on its long
/// edge (enough for a photo filling an iPad's canvas), and as the JPEG that is uploaded; and cuts
/// its subject out for the sticker style. The full-size original never leaves the device.
enum CanvasPhotoProcessing {
    static let maxPixels: CGFloat = 1600

    /// The photo drawn upright at upload size.
    nonisolated static func prepared(_ image: UIImage) -> UIImage {
        let size = image.size
        let scale = min(1, maxPixels / max(1, max(size.width * image.scale, size.height * image.scale)))
        let target = CGSize(width: (size.width * image.scale * scale).rounded(), height: (size.height * image.scale * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// The photo picked from the library, decoded straight to upload size.
    nonisolated static func prepared(data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: Int(maxPixels)
              ] as CFDictionary) else { return nil }
        return prepared(UIImage(cgImage: image))
    }

    nonisolated static func jpeg(_ image: UIImage) -> Data? { image.jpegData(compressionQuality: 0.8) }

    nonisolated static func png(_ image: UIImage) -> Data? { image.pngData() }

    /// The photo's subject, lifted as the Photos app lifts it, inside a white outline and cropped
    /// close around it. Nil when there is no clear subject.
    nonisolated static func cutout(_ image: UIImage) -> UIImage? {
        guard let cgImage = image.cgImage else { return nil }
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
        let context = CIContext(options: [.workingColorSpace: CGColorSpace(name: CGColorSpace.sRGB)!])
        guard let rendered = context.createCGImage(composed, from: crop, format: .RGBA8,
                                                   colorSpace: CGColorSpace(name: CGColorSpace.sRGB)) else { return nil }
        return UIImage(cgImage: rendered)
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
