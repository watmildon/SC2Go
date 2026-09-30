package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.data.IosPeriodicCleaner
import de.westnordost.streetcomplete.util.logs.Log
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import org.koin.core.context.startKoin

// called from iOSApp.swift
@OptIn(ExperimentalNativeApi::class)
fun initKoin() {
    val koinApp = startKoin {
        modules(iosModule, commonModule)
    }
    val koin = koinApp.koin
    koin.get<IosPeriodicCleaner>().register()
    koin.get<ApplicationInitializer>().initialize()

    /* Not crash reporting - it does not catch native crashes, and the app still terminates. But
       it puts uncaught Kotlin exceptions in the log before it does, so that they can be read back
       from the log screen instead of vanishing. Set only now, once initialize() has added the
       loggers: before that, Log.e would go nowhere and swallow the default stderr report. */
    setUnhandledExceptionHook { e -> Log.e("Koin", "Uncaught exception", e) }
}
