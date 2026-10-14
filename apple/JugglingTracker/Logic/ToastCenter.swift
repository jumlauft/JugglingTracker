import Foundation
import Observation

/// The short message the screen shows for a moment at the bottom, like an
/// Android toast. Any screen or model can show one.
@MainActor
@Observable
final class ToastCenter {
    var message: String?

    func show(_ text: String) {
        message = text
    }
}
