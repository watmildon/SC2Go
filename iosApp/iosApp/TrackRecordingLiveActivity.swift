import ActivityKit
import Foundation
import StreetComplete

// The Swift half of the Live Activity. ActivityKit is Swift-only - Kotlin/Native cannot call it -
// so Kotlin decides what to show (see TrackRecordingBridge.kt) and this turns each snapshot into
// an ActivityKit call: a snapshot where there was none starts an activity, a changed one updates
// it, and nil ends it.
//
// Everything is gated on iOS 16.2: the app's deployment target is 15.0, and the widget that draws
// the activity is a separate target that deploys 17.0. On anything older this is simply inert -
// the recording itself does not depend on it.
final class TrackRecordingLiveActivityController {

    static let shared = TrackRecordingLiveActivityController()

    /// Held for the life of the process. Cancelling it would stop the reporting, and there is
    /// nowhere in an iOS app's life that this should stop before the process does.
    private var observation: TrackRecordingObservation?

    /// The running activity, as Any because Activity<TrackRecordingAttributes> is itself only
    /// available from 16.2 and a stored property cannot carry an availability annotation.
    private var activity: Any?

    /// Whether starting an activity for the current recording has already been refused, so that
    /// it is not attempted again until the next one. Recording runs for hours in the background,
    /// where ActivityKit rejects a request outright, and a snapshot arrives every five seconds:
    /// retrying each time would be thousands of doomed requests and log lines per survey.
    private var activityRequestFailed = false

    /// How long after an update the activity is to be shown as out of date. The app is updating
    /// it at most every five seconds, so anything approaching this means it has stopped - it was
    /// killed while in the background, most likely - and the widget then says so rather than
    /// counting a walk up that nobody is recording. Not shorter, because iOS also throttles
    /// updates from a backgrounded app.
    private static let staleAfter: TimeInterval = 120

    private init() {}

    /// Called once from iOSApp.init, after doInitApp() has started Koin - the bridge resolves the
    /// recorder out of it.
    func start() {
        guard #available(iOS 16.2, *) else { return }
        // an activity from a previous run of the app: the recording it belonged to is long gone
        endStaleActivities()
        observation = TrackRecordingBridgeKt.observeTrackRecording { [weak self] snapshot in
            self?.apply(snapshot)
        }
    }

    private func apply(_ snapshot: TrackRecordingSnapshot?) {
        guard #available(iOS 16.2, *) else { return }
        guard let snapshot else {
            end()
            // the next recording gets a fresh attempt: the user may have turned them back on
            activityRequestFailed = false
            return
        }
        let state = TrackRecordingAttributes.ContentState(
            distanceMeters: snapshot.distanceMeters,
            nearbyQuestCount: Int(snapshot.nearbyQuestCount),
            nearestQuestTitle: snapshot.nearestQuestTitle,
            // negative means there is none: Kotlin/Native would box a Double? into an NSNumber
            nearestQuestDistanceMeters: snapshot.nearestQuestDistanceMeters >= 0
                ? snapshot.nearestQuestDistanceMeters
                : nil,
            startedAt: Date(timeIntervalSince1970: Double(snapshot.startedAtEpochMillis) / 1000)
        )
        let content = ActivityContent(
            state: state,
            staleDate: Date().addingTimeInterval(Self.staleAfter)
        )
        if let activity = activity as? Activity<TrackRecordingAttributes> {
            Task { await activity.update(content) }
            return
        }
        // the user can turn Live Activities off for the app; recording still works, it just shows
        // nothing, so this is not worth reporting anywhere
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard !activityRequestFailed else { return }
        do {
            activity = try Activity.request(
                attributes: TrackRecordingAttributes(),
                content: content,
                pushType: nil
            )
        } catch {
            // once per recording, see activityRequestFailed: a request from the background is
            // refused, and that is where most of this recording will be spent
            activityRequestFailed = true
            NSLog("Could not start the track recording Live Activity: \(error)")
        }
    }

    @available(iOS 16.2, *)
    private func end() {
        guard let activity = activity as? Activity<TrackRecordingAttributes> else { return }
        self.activity = nil
        // immediate: the recording is over, and a banner lingering on the Lock Screen afterwards
        // reads as if it were still running
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    /// Ends any activity of ours left over from an earlier run of the app. ActivityKit outlives
    /// the process - an app killed mid-recording leaves its activity on the Lock Screen, counting
    /// up a walk that nothing is recording any more.
    @available(iOS 16.2, *)
    private func endStaleActivities() {
        for activity in Activity<TrackRecordingAttributes>.activities {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }
}
