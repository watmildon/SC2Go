package de.westnordost.streetcomplete.data.user

import de.westnordost.streetcomplete.ApplicationConstants.USE_TEST_API
import de.westnordost.streetcomplete.ForkConfig

private const val OAUTH2_HOST_LIVE = "https://www.openstreetmap.org/"
private const val OAUTH2_HOST_TEST = "https://master.apis.dev.openstreetmap.org/"

private const val OAUTH2_CLIENT_ID_LIVE = ForkConfig.OAUTH2_CLIENT_ID_LIVE
private const val OAUTH2_CLIENT_ID_TEST = ForkConfig.OAUTH2_CLIENT_ID_TEST

val OAUTH2_TOKEN_URL =
    (if (USE_TEST_API) OAUTH2_HOST_TEST else OAUTH2_HOST_LIVE) + "oauth2/token"
val OAUTH2_AUTHORIZATION_URL =
    (if (USE_TEST_API) OAUTH2_HOST_TEST else OAUTH2_HOST_LIVE) + "oauth2/authorize"
val OAUTH2_CLIENT_ID =
    if (USE_TEST_API) OAUTH2_CLIENT_ID_TEST else OAUTH2_CLIENT_ID_LIVE

const val OAUTH2_CALLBACK_SCHEME = ForkConfig.URL_SCHEME
const val OAUTH2_CALLBACK_HOST = "oauth"

val OAUTH2_REDIRECT_URI = "$OAUTH2_CALLBACK_SCHEME://$OAUTH2_CALLBACK_HOST"

val OAUTH2_REQUESTED_SCOPES = listOf(
    "read_prefs",
    "write_api",
    "write_notes",
    "write_gpx",
)

val OAUTH2_REQUIRED_SCOPES = listOf(
    "read_prefs",
    "write_api",
    "write_notes",
    /* the gps traces permissions is only required for "attaching" gpx track recordings
       to notes. People that feel uneasy to give these permission should still be able to
       use this app.
       If those then still use the "attach gpx track recordings" feature and try to upload,
       they will be prompted to re-authenticate (currently) without further explanation
       because the OSM API returned a HTTP 403 (forbidden) error.
     */
    // "write_gpx",
)
