import AVFoundation
import AppKit
// frames <movie> <outdir> <fps>: one JPEG per 1/fps second.
let args = CommandLine.arguments
let asset = AVURLAsset(url: URL(fileURLWithPath: args[1]))
let out = args[2]; let fps = Double(args[3])!
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
let gen = AVAssetImageGenerator(asset: asset)
gen.requestedTimeToleranceBefore = .zero; gen.requestedTimeToleranceAfter = .zero
gen.appliesPreferredTrackTransform = true
let dur = CMTimeGetSeconds(asset.duration)
var i = 0
while Double(i) / fps < dur - 0.05 {
    let t = CMTime(seconds: Double(i) / fps, preferredTimescale: 600)
    if let cg = try? gen.copyCGImage(at: t, actualTime: nil) {
        let rep = NSBitmapImageRep(cgImage: cg)
        let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.92])!
        try! data.write(to: URL(fileURLWithPath: String(format: "%@/%05d.jpg", out, i)))
    }
    i += 1
}
print("frames", i, "duration", dur)
