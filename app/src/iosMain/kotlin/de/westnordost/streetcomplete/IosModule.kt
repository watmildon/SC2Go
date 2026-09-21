package de.westnordost.streetcomplete

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.ObservableSettings
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.data.Cleaner
import de.westnordost.streetcomplete.data.Database
import de.westnordost.streetcomplete.data.DatabaseImpl
import de.westnordost.streetcomplete.data.IosPeriodicCleaner
import de.westnordost.streetcomplete.data.PeriodicCleaner
import de.westnordost.streetcomplete.data.StreetCompleteDatabaseConfigurator
import de.westnordost.streetcomplete.data.connection.ActiveNetworkConnection
import de.westnordost.streetcomplete.data.connection.IosActiveNetworkConnection
import de.westnordost.streetcomplete.data.download.DownloadController
import de.westnordost.streetcomplete.data.download.IosDownloadController
import de.westnordost.streetcomplete.data.initialize
import de.westnordost.streetcomplete.data.maptiles.IosMapTilesDownloader
import de.westnordost.streetcomplete.data.maptiles.MapTilesDownloader
import de.westnordost.streetcomplete.data.power.IosPowerSaveSource
import de.westnordost.streetcomplete.data.power.PowerSaveSource
import de.westnordost.streetcomplete.data.upload.IosUploadController
import de.westnordost.streetcomplete.data.upload.UploadController
import de.westnordost.streetcomplete.screens.about.AppStoreInfo
import de.westnordost.streetcomplete.screens.about.IosAppStoreInfo
import de.westnordost.streetcomplete.ui.util.measure.ArSupportChecker
import de.westnordost.streetcomplete.ui.util.measure.IosArSupportChecker
import de.westnordost.streetcomplete.util.error_reporting.CrashReportHolder
import de.westnordost.streetcomplete.util.error_reporting.EmptyCrashReportHolder
import de.westnordost.streetcomplete.util.sound.IosSoundEffectPlayer
import de.westnordost.streetcomplete.util.sound.SoundEffectPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.maplibre.compose.location.IosLocationProvider
import org.maplibre.compose.location.IosSystemSettingsLauncher
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.SystemSettingsLauncher
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

private val COMPOSE_FILES_DIR = NSBundle.mainBundle.resourcePath +
    "/compose-resources/composeResources/de.westnordost.streetcomplete.resources/files"

@OptIn(ExperimentalForeignApi::class)
val iosModule = module {

    // metadata

    single<de.westnordost.countryboundaries.CountryBoundaries> {
        val file = Path(COMPOSE_FILES_DIR + "/boundaries.ser")
        val source = SystemFileSystem.source(file).buffered()
        de.westnordost.countryboundaries.CountryBoundaries.deserializeFrom(source)
    }

    single<FeatureDictionary> {
        FeatureDictionary.create(
            fileSystem = SystemFileSystem,
            presetsBasePath = COMPOSE_FILES_DIR + "/osmfeatures/default",
            brandPresetsBasePath = COMPOSE_FILES_DIR + "/osmfeatures/brands"
        )
    }

    // error reporting

    single<CrashReportHolder> { EmptyCrashReportHolder }

    // database

    single<Database> {
        val appSupportUrl = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )!!
        val databaseUrl = appSupportUrl.URLByAppendingPathComponent(ApplicationConstants.DATABASE_NAME)!!
        val databaseFilePath = databaseUrl.path!!
        val databaseConnection = BundledSQLiteDriver().open(databaseFilePath)
        DatabaseImpl(databaseConnection).apply { initialize(StreetCompleteDatabaseConfigurator) }
    } onClose { it?.close() }

    // avatars cache dir

    factory(named("AvatarsCacheDirectory")) {
        val cacheUrl = NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )!!
        val avatarsCacheUrl = cacheUrl.URLByAppendingPathComponent(ApplicationConstants.AVATARS_CACHE_DIRECTORY)!!
        Path(avatarsCacheUrl.path!!)
    }

    // app store info

    single<AppStoreInfo> { IosAppStoreInfo }

    // AR

    factory<ArSupportChecker> { IosArSupportChecker() }

    // location

    factory<LocationProvider> { IosLocationProvider() }
    factory<SystemSettingsLauncher> { IosSystemSettingsLauncher() }

    // settings

    single<ObservableSettings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }

    // sound

    single<SoundEffectPlayer> { IosSoundEffectPlayer(COMPOSE_FILES_DIR) }

    // connection

    factory<ActiveNetworkConnection> { IosActiveNetworkConnection() }

    // power

    // a single: it registers a process-lifetime observer, see the class
    single<PowerSaveSource> { IosPowerSaveSource() }

    // map tiles

    factory<MapTilesDownloader> { IosMapTilesDownloader() }

    // background jobs

    /* Uploader/Downloader are resolved eagerly (upstream's shape): nothing in either one's
       dependency graph needs UploadController or DownloadController - only MainViewModelImpl and
       AutoSyncer do - so there is no Koin cycle and the lazy `() -> Uploader` provider our
       controllers used to take is no longer needed. */
    single<UploadController> { IosUploadController(get()) } onClose { (it as? IosUploadController)?.close() }

    single<DownloadController> { IosDownloadController(get()) } onClose { (it as? IosDownloadController)?.close() }

    /* ChangesetAutoCloser is gone on every platform (upstream #7123): the OSM API closes an idle
       changeset after an hour by itself. */

    single { IosPeriodicCleaner { get<Cleaner>().cleanOld() } } onClose { it?.close() }

    single<PeriodicCleaner> { get<IosPeriodicCleaner>() }
}
