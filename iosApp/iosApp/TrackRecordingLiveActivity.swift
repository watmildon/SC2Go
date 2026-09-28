import ActivityKit
import Foundation
import StreetComplete
import UIKit

// The Swift half of the Live Activity. ActivityKit is Swift-only - Kotlin/Native cannot call it -
// so Kotlin decides what to show (see TrackRecordingBridge.kt) and this turns each snapshot into
// an ActivityKit call: a snapshot where there was none starts an activity, a changed one updates
// it, and nil ends it.
//
// Everything is gated on iOS 17.0, which is what the widget extension that draws the activity
// deploys: an activity requested on 16.x would be accepted by ActivityKit and then drawn by
// nothing. The app's own deployment target is 15.0; on anything older this is simply inert - the
// recording itself does not depend on it.
final class TrackRecordingLiveActivityController {

    static let shared = TrackRecordingLiveActivityController()

    /// Held for the life of the process. Cancelling it would stop the reporting, and there is
    /// nowhere in an iOS app's life that this should stop before the process does.
    private var observation: TrackRecordingObservation?

    /// The running activity, as Any because Activity<TrackRecordingAttributes> is only available
    /// from 17.0 - the attributes' own floor - and a stored property cannot be marked as
    /// potentially unavailable.
    private var activity: Any?

    /// The last update handed to ActivityKit, so that the next one is queued behind it: each
    /// update is its own Task, and two of them on the global executor have no ordering between
    /// them - a reordered pair would leave an older distance on screen until the next change.
    private var updateTask: Task<Void, Never>?

    /// Whether the user swiped the activity off the Lock Screen during this recording. Their
    /// call: it is not requested again until the next recording.
    private var dismissedByUser = false

    /// Whether starting an activity has been refused since the app was last in the foreground,
    /// so that it is not attempted again until it is. Recording runs for hours in the background,
    /// where ActivityKit rejects a request outright, and a snapshot arrives at least every 45 s:
    /// retrying each time would be thousands of doomed requests and log lines per survey.
    /// Cleared on becoming active, because a request from the foreground can succeed.
    private var requestFailedInBackground = false

    /// How long after an update the activity is to be shown as out of date. Kotlin re-sends the
    /// last snapshot at least every 45 s (a heartbeat, see TrackRecordingBridge.kt), so anything
    /// approaching this means the process has stopped - killed while in the background, most
    /// likely - and the widget then says so rather than counting a walk up that nobody is
    /// recording. Not shorter, because iOS also throttles updates from a backgrounded app.
    private static let staleAfter: TimeInterval = 120

    private init() {}

    /// Called once from iOSApp.init, after doInitApp() has started Koin - the bridge resolves the
    /// recorder out of it.
    func start() {
        guard #available(iOS 17.0, *) else { return }
        // an activity from a previous run of the app: the recording it belonged to is long gone
        endStaleActivities()
        observation = TrackRecordingBridgeKt.observeTrackRecording { [weak self] snapshot in
            self?.apply(snapshot)
        }
        // a request refused in the background may succeed now; the next heartbeat retries it
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.requestFailedInBackground = false
        }
    }

    private func apply(_ snapshot: TrackRecordingSnapshot?) {
        guard #available(iOS 17.0, *) else { return }
        guard let snapshot else {
            end()
            // the next recording gets a fresh attempt: the user may have turned them back on
            dismissedByUser = false
            requestFailedInBackground = false
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
            switch activity.activityState {
            case .active, .stale:
                let previous = updateTask
                updateTask = Task {
                    await previous?.value
                    await activity.update(content)
                }
                return
            case .dismissed:
                // the user swiped it off the Lock Screen: their call, for the rest of this recording
                self.activity = nil
                dismissedByUser = true
            case .ended:
                /* the system ended it - eight hours is its limit - and an ended activity stays on
                   the Lock Screen for hours more, its clock still counting, unless dismissed. So
                   dismiss it, and request a new one below (which succeeds only in the foreground). */
                end()
            @unknown default:
                // a state this code does not know: safest is to leave it alone and not add a second
                self.activity = nil
                dismissedByUser = true
            }
        }
        // the user can turn Live Activities off for the app; recording still works, it just shows
        // nothing, so this is not worth reporting anywhere
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard !dismissedByUser, !requestFailedInBackground else { return }
        do {
            activity = try Activity.request(
                attributes: TrackRecordingAttributes(),
                content: content,
                pushType: nil
            )
        } catch {
            // once per trip into the background, see requestFailedInBackground: a request from
            // the background is refused, and that is where most of a recording is spent
            requestFailedInBackground = true
            NSLog("Could not start the track recording Live Activity: %@", String(describing: error))
        }
    }

    @available(iOS 17.0, *)
    private func end() {
        guard let activity = activity as? Activity<TrackRecordingAttributes> else { return }
        self.activity = nil
        /* queued behind the last update, like the updates behind each other: dropping the handle
           does not cancel an in-flight update, and one landing after the end would be ignored by
           ActivityKit anyway, but ordering it makes that certain rather than likely */
        let previous = updateTask
        updateTask = nil
        // immediate: the recording is over, and a banner lingering on the Lock Screen afterwards
        // reads as if it were still running
        Task {
            await previous?.value
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// Ends any activity of ours left over from an earlier run of the app. ActivityKit outlives
    /// the process - an app killed mid-recording leaves its activity on the Lock Screen, counting
    /// up a walk that nothing is recording any more.
    @available(iOS 17.0, *)
    private func endStaleActivities() {
        for activity in Activity<TrackRecordingAttributes>.activities {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }
}
