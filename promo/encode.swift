import AVFoundation
import AppKit
// encode <framesdir> <count> <fps> <audio.m4a> <out.mp4>
let a = CommandLine.arguments
let dir = a[1], count = Int(a[2])!, fps = Int32(a[3])!
let audioURL = URL(fileURLWithPath: a[4]), outURL = URL(fileURLWithPath: a[5])
let tmpURL = outURL.deletingPathExtension().appendingPathExtension("video.mov")
try? FileManager.default.removeItem(at: tmpURL); try? FileManager.default.removeItem(at: outURL)
let W = 1080, H = 1920
let writer = try! AVAssetWriter(outputURL: tmpURL, fileType: .mov)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 3_200_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel]])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB, kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H])
writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)
for i in 0..<count {
    while !input.isReadyForMoreMediaData { usleep(2000) }
    let img = NSImage(contentsOfFile: String(format: "%@/%05d.jpg", dir, i))!
    var rect = CGRect(x: 0, y: 0, width: W, height: H)
    let cg = img.cgImage(forProposedRect: &rect, context: nil, hints: nil)!
    var pb: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
    CVPixelBufferLockBaseAddress(pb!, [])
    let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb!), width: W, height: H, bitsPerComponent: 8,
        bytesPerRow: CVPixelBufferGetBytesPerRow(pb!), space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: W, height: H))
    CVPixelBufferUnlockBaseAddress(pb!, [])
    adaptor.append(pb!, withPresentationTime: CMTime(value: Int64(i), timescale: fps))
}
input.markAsFinished()
let sem = DispatchSemaphore(value: 0)
writer.finishWriting { sem.signal() }; sem.wait()
let comp = AVMutableComposition()
let v = AVURLAsset(url: tmpURL), au = AVURLAsset(url: audioURL)
let dur = v.duration
let vt = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
try! vt.insertTimeRange(CMTimeRange(start: .zero, duration: dur), of: v.tracks(withMediaType: .video)[0], at: .zero)
let at = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
try! at.insertTimeRange(CMTimeRange(start: .zero, duration: CMTimeMinimum(dur, au.duration)), of: au.tracks(withMediaType: .audio)[0], at: .zero)
let ex = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetPassthrough)!
ex.outputURL = outURL; ex.outputFileType = .mp4
ex.exportAsynchronously { sem.signal() }; sem.wait()
print("export", ex.status.rawValue)
try? FileManager.default.removeItem(at: tmpURL)
