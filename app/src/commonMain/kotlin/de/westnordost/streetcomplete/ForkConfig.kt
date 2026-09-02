package de.westnordost.streetcomplete

/** Everything that identifies this fork to the outside world, plus the third-party services it
 *  borrows, gathered in one place.
 *
 *  Two reasons these are here rather than where upstream put them:
 *
 *  This is a fork of StreetComplete that is not StreetComplete, and several of these values tell
 *  servers and users otherwise - the OAuth consent screen, the `created_by` changeset tag, the HTTP
 *  User-Agent. They were spread over five files, so changing one meant finding all of them.
 *
 *  And upstream is merged from regularly, so every line changed in upstream's files is a future
 *  merge conflict. Each site now reads its value from here, which keeps the change over there to a
 *  single line.
 *
 *  Note that not every copy can be reached from Kotlin: the iOS `Info.plist` registers [URL_SCHEME]
 *  itself, and the Android map style JSON in `androidMain/assets/map_theme` has
 *  [JAWG_ACCESS_TOKEN] baked in by the `updateMapStyle` Gradle task. Both are commented to point
 *  back here. */
object ForkConfig {

    // ---------------------------------------------------------------------------- identity ----

    /** What the app calls itself. Users see it; servers see it too, because it is the whole of
     *  `ApplicationConstants.USER_AGENT` apart from the version, and that is what goes out as the
     *  HTTP User-Agent and as the `created_by` tag on every changeset uploaded to OSM.
     *
     *  Renaming this is the branding change. Read `ApplicationConstants.QUESTTYPE_TAG_KEY` first:
     *  it used to be derived from this name and deliberately no longer is. */
    const val APP_NAME = "SC2Go"

    /** This fork's own OAuth 2 client, registered on openstreetmap.org. It is what the user is
     *  shown when asked to grant write access to their OSM account, so it is the difference between
     *  them being told they are authorising this app or authorising *StreetComplete*.
     *
     *  Registered as a public client - the app uses PKCE (S256) and ships no secret - with
     *  `read_prefs`, `write_api`, `write_notes` and `write_gpx`, and with `sc2go://oauth` as the
     *  redirect URI, so it is tied to [URL_SCHEME]: changing one without the other breaks login. */
    const val OAUTH2_CLIENT_ID_LIVE = "Ap2iMgPyv-xsSyi0uotnEdMp0QLO08RAyqbQUBj3FZQ"

    /** As [OAUTH2_CLIENT_ID_LIVE], for `master.apis.dev.openstreetmap.org`. Only used when
     *  `ApplicationConstants.USE_TEST_API` is on.
     *
     *  Still upstream's, and unlike the live one it has *not* been re-registered: its redirect URI
     *  is `streetcomplete://oauth`, which is no longer this app's scheme. Turning USE_TEST_API on
     *  will therefore fail at login until a client is registered on the dev server with a
     *  `sc2go://oauth` redirect. */
    const val OAUTH2_CLIENT_ID_TEST = "ObZ7yPf4lfs4XJ3NWysI3ukJMN0SHey1oPnNQnLmvw8"

    /** The app's custom URL scheme, used for the OAuth callback (`<scheme>://oauth`) and for quest
     *  preset sharing links (`<scheme>://s`).
     *
     *  A scheme is first come, first served on a device and which app wins is undefined, which is
     *  why this is no longer `streetcomplete`: a user with the real StreetComplete installed could
     *  otherwise have had their OAuth callback delivered to it. Changing it means changing,
     *  together: this constant, `CFBundleURLSchemes` in `iosApp/iosApp/Info.plist`, the Android
     *  manifest's intent filter, `UrlConfigKtTest`, and the redirect URI registered against the
     *  OAuth client above - which is currently `sc2go://oauth`. */
    const val URL_SCHEME = "sc2go"

    // -------------------------------------------------------------------- borrowed services ----

    /* The services below run on upstream's infrastructure at streetcomplete.app. Upstream has said
       this fork may use the photo endpoint and the map tile key. They are gathered here so that
       pointing them somewhere else is a one-line change per service if that ever changes, or if the
       traffic from this fork ever becomes enough to be worth not sending them. */

    /** Host whose requests get [UPSTREAM_COMPAT_USER_AGENT] instead of this app's own User-Agent. */
    const val UPSTREAM_SERVICE_HOST = "streetcomplete.app"

    /** The User-Agent sent to [UPSTREAM_SERVICE_HOST], which is not this app's real one.
     *
     *  `/statistics/` refuses anything it does not recognise - `SC2Go ...` gets
     *  `403 {"error":"This is not a public API"}` where `StreetComplete ...` gets the data - so
     *  after the rename the statistics never synced and the user screen sat forever on "your
     *  statistics are still syncing". `/photo-upload/` may well gate the same way; it is POST-only,
     *  so that could not be established without actually uploading.
     *
     *  **This is a stopgap, and it is dishonest to upstream's servers**: it is the one thing the
     *  rest of this file exists to avoid, and it takes away their ability to tell this fork's
     *  traffic from their own on endpoints they pay to run. It is here because the alternative was
     *  a visibly broken user screen, and only until upstream is asked whether they will accept the
     *  real User-Agent. Delete this and [UPSTREAM_SERVICE_HOST], and the plugin in CommonModule
     *  that uses them, the moment that is settled.
     *
     *  Deliberately not [APP_NAME]-derived: it must stay "StreetComplete" to work, so it must not
     *  quietly follow a later rename. */
    val UPSTREAM_COMPAT_USER_AGENT = "StreetComplete " + BuildConfig.VERSION_NAME

    /** Jawg account the vector tiles are billed to; every tile any user loads counts against it.
     *  Note this token is extractable from the shipped binary, as it is in any map app. */
    const val JAWG_ACCESS_TOKEN = "mL9X4SwxfsAGfojvGiion9hPKuGLKxPbogLyMbtakA2gJ3X88gcVlTSQ7OD6OfbZ"

    /** Where photos attached to OSM notes are uploaded to, and then served from. The one service
     *  here that stores user content rather than just answering queries. Must have a trailing `/`. */
    const val PHOTO_SERVICE_URL = "https://streetcomplete.app/photo-upload/"

    /** Read-only: a GET carrying the user's own OSM id, to show their edit statistics. */
    const val STATISTICS_URL = "https://streetcomplete.app/statistics/"

    /** A kill switch: the app refuses to upload if its own [ApplicationConstants.USER_AGENT] appears
     *  as the first tab-separated field of a line here, with an optional reason in the second.
     *
     *  Pointed at this fork's own repository rather than upstream's list, so that this project can
     *  stop a bad build of its own - and so that upstream cannot, which cuts both ways and is worth
     *  being deliberate about.
     *
     *  Three things to know before relying on it. `banned_versions.txt` has to be on `main` in
     *  watmildon/SC2Go *and pushed*. raw.githubusercontent.com is CDN-cached, so a ban takes a few
     *  minutes to reach clients. And `VersionIsBannedChecker` swallows every exception and reports
     *  "not banned", so a typo here, a renamed repository or branch, or an offline device all fail
     *  open, silently - it is a courtesy brake, not a guarantee.
     *
     *  That last point has teeth: the repository was renamed from StreetComplete to SC2Go and the
     *  branch from master to main, and GitHub's redirect kept the old URL working, so nothing
     *  appeared to break. Those redirects stop the moment anyone creates a repository at the old
     *  name. Rename either again and this URL must be changed with it. */
    const val BANNED_VERSIONS_URL =
        "https://raw.githubusercontent.com/watmildon/SC2Go/main/banned_versions.txt"

    /** The `https` half of quest preset sharing links - the half that works when the recipient does
     *  not have the app installed, and so the one that appears in anything shared publicly. */
    const val URL_CONFIG_WEB_URL = "https://streetcomplete.app/s"
}
