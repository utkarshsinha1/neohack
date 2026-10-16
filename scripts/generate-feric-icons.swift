import AppKit
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let sourceURL = root.appendingPathComponent("logos/feric-wordmark.png")
let pngURL = root.appendingPathComponent("logos/feric-app-icon.png")
let icoURL = root.appendingPathComponent("logos/feric.ico")
let iconSize = 1024

guard
    let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
    let wordmark = CGImageSourceCreateImageAtIndex(source, 0, nil),
    let context = CGContext(
        data: nil,
        width: iconSize,
        height: iconSize,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    )
else {
    fatalError("Could not load the Feric wordmark or create its app icon.")
}

context.setFillColor(NSColor(calibratedRed: 0.96, green: 0.96, blue: 0.94, alpha: 1).cgColor)
context.fill(CGRect(x: 0, y: 0, width: iconSize, height: iconSize))
let scale = min(
    CGFloat(iconSize - 48) / CGFloat(wordmark.width),
    CGFloat(iconSize - 48) / CGFloat(wordmark.height)
)
let width = CGFloat(wordmark.width) * scale
let height = CGFloat(wordmark.height) * scale
let destination = CGRect(
    x: (CGFloat(iconSize) - width) / 2,
    y: (CGFloat(iconSize) - height) / 2,
    width: width,
    height: height
)
context.draw(wordmark, in: destination)

guard
    let appIcon = context.makeImage(),
    let pngDestination = CGImageDestinationCreateWithURL(
        pngURL as CFURL,
        UTType.png.identifier as CFString,
        1,
        nil
    )
else {
    fatalError("Could not encode the Feric PNG app icon.")
}
CGImageDestinationAddImage(pngDestination, appIcon, nil)
guard CGImageDestinationFinalize(pngDestination) else {
    fatalError("Could not write \(pngURL.path).")
}

func pngData(for size: Int) -> Data {
    let options = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceThumbnailMaxPixelSize: size,
        kCGImageSourceCreateThumbnailWithTransform: true,
    ] as CFDictionary
    guard let iconSource = CGImageSourceCreateWithURL(pngURL as CFURL, nil) else {
        fatalError("Could not load the Feric app icon for Windows.")
    }
    let data = NSMutableData()
    guard
        let thumbnail = CGImageSourceCreateThumbnailAtIndex(
            iconSource,
            0,
            options
        ),
        let destination = CGImageDestinationCreateWithData(
            data,
            UTType.png.identifier as CFString,
            1,
            nil
        )
    else {
        fatalError("Could not create a \(size)x\(size) Windows icon.")
    }
    CGImageDestinationAddImage(destination, thumbnail, nil)
    guard CGImageDestinationFinalize(destination) else {
        fatalError("Could not encode the \(size)x\(size) Windows icon.")
    }
    return data as Data
}

func appendLittleEndian<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
    var littleEndian = value.littleEndian
    withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
}

let imageData = pngData(for: 256)
var ico = Data()
appendLittleEndian(UInt16(0), to: &ico)
appendLittleEndian(UInt16(1), to: &ico)
appendLittleEndian(UInt16(1), to: &ico)
ico.append(contentsOf: [0, 0, 0, 0])
appendLittleEndian(UInt16(1), to: &ico)
appendLittleEndian(UInt16(32), to: &ico)
appendLittleEndian(UInt32(imageData.count), to: &ico)
appendLittleEndian(UInt32(22), to: &ico)
ico.append(imageData)
try ico.write(to: icoURL)
