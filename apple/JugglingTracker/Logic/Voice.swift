import AVFoundation

/// Speaks catch counts and sync results, as the Android app's TextToSpeech does.
final class Voice {
    private let synthesizer = AVSpeechSynthesizer()

    init() {
        // Speak over music and other apps, lowering them for a moment.
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
    }

    /// Says `text`, cutting off whatever was still being said.
    func speak(_ text: String) {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        try? AVAudioSession.sharedInstance().setActive(true)
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
    }
}
