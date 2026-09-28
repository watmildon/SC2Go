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
import de.westnordost.streetcomplete.data.location.IosRecordingLocationProvider
import de.westnordost.streetcomplete.data.location.LocationUpdatesSource
import de.westnordost.streetcomplete.data.location.RECORDING_LOCATION_PROVIDER
import de.westnordost.streetcomplete.data.maptiles.IosMapTilesDownloader
import de.westnordost.streetcomplete.data.maptiles.MapTilesDownloader
import de.westnordost.streetcomplete.data.osmtracks.IosTrackRecorder
import de.westnordost.streetcomplete.data.power.IosPowerSaveSource
import de.westnordost.streetcomplete.data.power.PowerSaveSource
import de.westnordost.streetcomplete.data.quest.VisibleQuestsSource
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

    /* A single, where upstream has a factory: IosRecordingLocationProvider below forwards the
       permission handling to this one rather than reimplementing it, and a factory would hand it
       a second IosLocationProvider with a second permission requester of its own. Nothing else on
       iOS resolves this - only LocationUpdatesSource does, and it is a single too - so making it
       one changes nothing else, and it finally gets closed. */
    single<LocationProvider> { IosLocationProvider() } onClose { it?.close() }

    /* What LocationUpdatesSource switches to while a track is being recorded, so that the fixes
       keep coming while the app is in the background. Registered under a name because the source
       is built in CommonModule and Android has no such provider; see RECORDING_LOCATION_PROVIDER. */
    single<LocationProvider>(named(RECORDING_LOCATION_PROVIDER)) { IosRecordingLocationProvider(get()) }

    factory<SystemSettingsLauncher> { IosSystemSettingsLauncher() }

    // track recording

    /* A single, not something the main screen owns: a recording keeps running while the app is in
       the background, which is the whole point of it. See IosTrackRecorder.

       Lazy dependencies, because this is resolved from iOSApp.init (the Live Activity bridge
       observes it from launch) and LocationUpdatesSource must not be built before the IosApp
       composable has parsed the GPS launch flags - see its binding in CommonModule. */
    single { IosTrackRecorder(lazy { get<LocationUpdatesSource>() }, lazy { get<VisibleQuestsSource>() }) }

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
