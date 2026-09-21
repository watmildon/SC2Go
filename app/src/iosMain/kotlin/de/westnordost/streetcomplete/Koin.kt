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
 *  preloader, the edit-history pruning, the resurvey intervals, the logger instances and the
 *  new-version tile invalidation - and running both would do each of them twice.
 *  `ApplicationInitializer` is therefore unused on iOS; it is a `factory` in `CommonModule`, so
 *  nothing is constructed as long as nobody asks for it. If it is ever adopted here, the
 *  overlapping half of [initApp] has to go in the same change.
 *
 *  The one thing it does that [initApp] deliberately leaves out is `FeedsUpdater.updateNow()`.
 *  MainViewModel now calls `updateAtMostDaily()` instead, and `updateNow()` writes the same
 *  `lastFeedUpdate` that gates it - so doing it at launch would not add an update, it would
 *  replace the gated one with an unconditional fetch on every cold start, including the headless
 *  launches iOS makes to run the background cleanup, where there is no UI to show a message in. */
internal fun initKoin(): Koin =
    startKoin {
        modules(
            commonModule,
            iosModule,
            iosAppModule,
        )
    }.koin
