// swift-tools-version:5.9
import PackageDescription

// The code the iPhone app and the Apple Watch app share: the catch detector,
// the Regularity score, the watch message codec and the session history
// format. It is the Swift counterpart of the Kotlin `shared/` module and has
// no dependency on UIKit, SwiftUI, CoreMotion or WatchConnectivity, so its
// tests run with plain `swift test`.
let package = Package(
    name: "JugglingCore",
    platforms: [
        .iOS(.v17),
        .watchOS(.v10),
        .macOS(.v14),
    ],
    products: [
        .library(name: "JugglingCore", targets: ["JugglingCore"]),
    ],
    targets: [
        .target(name: "JugglingCore"),
        .testTarget(name: "JugglingCoreTests", dependencies: ["JugglingCore"]),
    ]
)
