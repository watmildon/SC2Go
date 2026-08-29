package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.screens.about.ChangelogViewModel
import de.westnordost.streetcomplete.screens.about.ChangelogViewModelImpl
import de.westnordost.streetcomplete.screens.about.CreditsViewModel
import de.westnordost.streetcomplete.screens.about.CreditsViewModelImpl
import de.westnordost.streetcomplete.util.logs.DatabaseLogger
import de.westnordost.streetcomplete.util.logs.KermitLogger
import de.westnordost.streetcomplete.util.logs.Log
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/* Only what already works on iOS is registered here. Most of the modules Android registers in
 * StreetCompleteApplication either are not multiplatform yet or require a Database, which does
 * not exist for iOS yet. */
val iosAppModule = module {
    single<Res> { Res }

    viewModel<ChangelogViewModel> { ChangelogViewModelImpl(get()) }
    viewModel<CreditsViewModel> { CreditsViewModelImpl(get()) }
}

fun initKoin() {
    val koin = startKoin {
        modules(
            commonModule,
            iosModule,
            iosAppModule,
        )
    }.koin

    // without this, everything logged with Log goes nowhere on iOS
    Log.instances.add(KermitLogger())
    Log.instances.add(koin.get<DatabaseLogger>())
}
