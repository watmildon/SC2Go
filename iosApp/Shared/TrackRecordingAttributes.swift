import ActivityKit
import Foundation

// What the Live Activity for a track recording shows. Compiled into BOTH targets - the app, which
// starts and updates the activity, and the widget extension, which draws it - because ActivityKit
// matches an activity to its widget by this type. It is the only thing the two share: the
// extension must not link the Kotlin framework (155 MB, static), so everything it draws has to
// arrive as a plain value in here.
//
// Available from 16.2 rather than ActivityKit's own 16.1, because that is the first version with
// the ActivityContent API the app uses to push updates.
@available(iOS 16.2, *)
struct TrackRecordingAttributes: ActivityAttributes {
    // Nothing fixed for the life of the activity: everything about a recording changes as it runs.
    public struct ContentState: Codable, Hashable {
        /// how far the user has walked since the recording started
        var distanceMeters: Double
        /// how many quests are within 50 m of the user
        var nearbyQuestCount: Int
        /// the nearest quest's type, already translated - the extension cannot resolve resources
        var nearestQuestTitle: String?
        var nearestQuestDistanceMeters: Double?
        /// when the recording started; the elapsed time is counted up from this by SwiftUI rather
        /// than pushed, so the clock runs without costing an activity update per second
        var startedAt: Date
    }
}

/// Metres up to a kilometre, then kilometres with one decimal - the same rule the app's own
/// distance labels follow. No unit choice: the app is metric throughout.
func formatTrackDistance(_ meters: Double) -> String {
    if meters < 1000 {
        return "\(Int(meters.rounded())) m"
    }
    return String(format: "%.1f km", meters / 1000)
}
