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
    const val APP_NAME = "StreetComplete"

    /** The OAuth 2 client registered on openstreetmap.org. This is what the user is shown when
     *  they are asked to grant the app write access to their account, so while it is upstream's
     *  client id, everyone logging in is told they are authorising *StreetComplete*.
     *
     *  Replace with this fork's own registered client. [OAUTH2_REDIRECT_URI_SCHEME] has to match
     *  the redirect URI registered against it. */
    const val OAUTH2_CLIENT_ID_LIVE = "Yyk4PmTopczrr3BWZYvLK_M-KBloCQwXgPGEzqUYTc8"

    /** As [OAUTH2_CLIENT_ID_LIVE], for `master.apis.dev.openstreetmap.org`. Only used when
     *  `ApplicationConstants.USE_TEST_API` is on. */
    const val OAUTH2_CLIENT_ID_TEST = "ObZ7yPf4lfs4XJ3NWysI3ukJMN0SHey1oPnNQnLmvw8"

    /** The app's custom URL scheme, used for the OAuth callback (`<scheme>://oauth`) and for quest
     *  preset sharing links (`<scheme>://s`).
     *
     *  A scheme is first come, first served on a device: while this is `streetcomplete`, a user who
     *  has the real StreetComplete installed as well may have their OAuth callback delivered to the
     *  wrong app, and which one wins is not defined. Changing it means changing, together:
     *  this constant, `CFBundleURLSchemes` in `iosApp/iosApp/Info.plist`, the Android manifest's
     *  intent filter, and the redirect URI registered against the OAuth client above. */
    const val URL_SCHEME = "streetcomplete"

    // -------------------------------------------------------------------- borrowed services ----

    /* The four services below run on upstream's infrastructure at streetcomplete.app. Upstream has
       said this fork may use the photo endpoint and the map tile key. They are gathered here so
       that pointing them somewhere else is a one-line change per service if that ever changes, or
       if the traffic from this fork ever becomes enough to be worth not sending them. */

    /** Jawg account the vector tiles are billed to; every tile any user loads counts against it.
     *  Note this token is extractable from the shipped binary, as it is in any map app. */
    const val JAWG_ACCESS_TOKEN = "mL9X4SwxfsAGfojvGiion9hPKuGLKxPbogLyMbtakA2gJ3X88gcVlTSQ7OD6OfbZ"

    /** Where photos attached to OSM notes are uploaded to, and then served from. The one service
     *  here that stores user content rather than just answering queries. Must have a trailing `/`. */
    const val PHOTO_SERVICE_URL = "https://streetcomplete.app/photo-upload/"

    /** Read-only: a GET carrying the user's own OSM id, to show their edit statistics. */
    const val STATISTICS_URL = "https://streetcomplete.app/statistics/"

    /** A kill switch. The app asks whether its own `USER_AGENT` is on this list and refuses to
     *  upload if it is, which means upstream can disable builds of this fork. */
    const val BANNED_VERSIONS_URL = "https://streetcomplete.app/banned_versions.txt"

    /** The `https` half of quest preset sharing links - the half that works when the recipient does
     *  not have the app installed, and so the one that appears in anything shared publicly. */
    const val URL_CONFIG_WEB_URL = "https://streetcomplete.app/s"
}
