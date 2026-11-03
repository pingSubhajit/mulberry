import CoreGraphics
import Foundation

// Usage: pointer tap X Y | pointer hold X Y SECONDS   (global screen points)
let args = Array(CommandLine.arguments.dropFirst())
guard args.count >= 3, let x = Double(args[1]), let y = Double(args[2]) else {
    FileHandle.standardError.write("usage: pointer tap X Y | pointer hold X Y SECONDS\n".data(using: .utf8)!)
    exit(2)
}
let point = CGPoint(x: x, y: y)
let source = CGEventSource(stateID: .hidSystemState)
func post(_ type: CGEventType) {
    CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
}
let seconds = args[0] == "hold" ? Double(args.count > 3 ? args[3] : "1.2") ?? 1.2 : 0.09
post(.mouseMoved); usleep(80_000)
post(.leftMouseDown); usleep(useconds_t(seconds * 1_000_000))
post(.leftMouseUp)
