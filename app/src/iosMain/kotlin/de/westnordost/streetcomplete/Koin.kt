package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.screens.about.ChangelogViewModel
import de.westnordost.streetcomplete.screens.about.ChangelogViewModelImpl
import de.westnordost.streetcomplete.screens.about.CreditsViewModel
import de.westnordost.streetcomplete.screens.about.CreditsViewModelImpl
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/* Only what already works on iOS is registered here. Most of the modules Android registers in
 * StreetCompleteApplication either are not multiplatform yet or require a Database, which does
 * not exist for iOS yet. */
internal val iosAppModule = module {
    single<Res> { Res }

    viewModel<ChangelogViewModel> { ChangelogViewModelImpl(get()) }
    viewModel<CreditsViewModel> { CreditsViewModelImpl(get()) }
}

/** Starts Koin and hands the [Koin] back to [initApp], which does the rest of the startup.
 *
 *  Upstream's `initKoin` resolves an `ApplicationInitializer` and calls `initialize()` on it here.
 *  That is deliberately *not* done on iOS: [initApp] already does everything it does - the
 *  preloader, the edit-history pruning, the feeds update, the resurvey intervals, the logger
 *  instances and the new-version tile invalidation - and running both would do each of them twice.
 *  `ApplicationInitializer` is therefore unused on iOS; it is a `factory` in `CommonModule`, so
 *  nothing is constructed as long as nobody asks for it. If it is ever adopted here, the
 *  overlapping half of [initApp] has to go in the same change. */
internal fun initKoin(): Koin =
    startKoin {
        modules(
            commonModule,
            iosModule,
            iosAppModule,
        )
    }.koin
