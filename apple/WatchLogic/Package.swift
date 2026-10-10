// swift-tools-version:5.9
import PackageDescription

// Everything with behaviour in the Apple Watch app: mode and ball selection,
// Juggle mode, Record mode, the 25 Hz throttle and the display strings. It is
// the Swift counterpart of `wearos/.../logic/` and, like it, has no UI, sensor
// or radio types, so its tests run with plain `swift test`. The watch app in
// `../Watch` draws it and feeds it CoreMotion samples.
let package = Package(
    name: "WatchLogic",
    platforms: [
        .iOS(.v17),
        .watchOS(.v10),
        .macOS(.v14),
    ],
    products: [
        .library(name: "WatchLogic", targets: ["WatchLogic"]),
    ],
    dependencies: [
        .package(path: "../JugglingCore"),
    ],
    targets: [
        .target(name: "WatchLogic", dependencies: ["JugglingCore"]),
        .testTarget(name: "WatchLogicTests", dependencies: ["WatchLogic"]),
    ]
)
