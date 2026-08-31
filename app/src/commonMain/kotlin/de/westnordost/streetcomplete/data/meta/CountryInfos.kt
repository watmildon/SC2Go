package de.westnordost.streetcomplete.data.meta

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.ui.ktx.readYamlOrNull
import de.westnordost.streetcomplete.util.countryboundaries.CountryBoundaries
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.runBlocking

class CountryInfos(private val res: Res) {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false, // ignore unknown properties
        )
    )

    /* Guarded for the same reason as FilterExpressionCache in MapDataXt: quest creation runs one
       async per quest type on Dispatchers.Default (OsmQuestController.createQuestsForBBox), and
       several quest types look up the country info per element from getApplicableElements. Without
       the lock the concurrent check-then-put corrupts the map and throws
       ArrayIndexOutOfBoundsException, which on Kotlin/Native - where the default dispatcher really
       is multi-threaded - surfaces as a download error the first time a region is not cached yet.

       Only the map is locked, not the loading: that is what the Lazy is for, which also keeps the
       blocking YAML read off whichever thread happens to be holding the lock. */
    private val lock = ReentrantLock()
    private val countryInfos = mutableMapOf<String, Lazy<IncompleteCountryInfo?>>()
    private val default: IncompleteCountryInfo by lazy { load("default")!! }

    /** Get the info by a list of country codes sorted by size. E.g. DE-NI,DE gets the info
     *  for Lower Saxony in Germany and uses defaults from Germany */
    fun get(regionCode: List<String>): CountryInfo =
        CountryInfo(regionCode.firstOrNull(), regionCode.mapNotNull { get(it) } + default)

    private fun get(regionCode: String): IncompleteCountryInfo? =
        lock.withLock { countryInfos.getOrPut(regionCode) { lazy { load(regionCode) } } }.value

    private fun load(regionCode: String): IncompleteCountryInfo? {
        return runBlocking {
            res.readYamlOrNull<IncompleteCountryInfo>("files/country_metadata/$regionCode.yml", yaml)
        }
    }
}

fun CountryInfos.get(countryBoundaries: CountryBoundaries, position: LatLon): CountryInfo =
    get(countryBoundaries.getIds(position))
