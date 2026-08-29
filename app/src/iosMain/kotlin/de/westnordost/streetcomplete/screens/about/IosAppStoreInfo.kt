package de.westnordost.streetcomplete.screens.about

object IosAppStoreInfo : AppStoreInfo {
    /** null because the app is not on the App Store yet, so there is nothing to rate it in.
     *  Once it is, this is
     *  "https://apps.apple.com/app/id<the app id>?action=write-review" */
    override fun getRatingUri(): String? =
        null

    override fun disallowsInAppDonationLinks(): Boolean =
        true
}
