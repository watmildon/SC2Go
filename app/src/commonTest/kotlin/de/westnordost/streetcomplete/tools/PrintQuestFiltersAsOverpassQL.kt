/* Each of these developer scripts keeps its own `main`, so they need their own
   packages: Kotlin/Native links all of commonTest into one test binary, where two
   top level `main` functions in the same package are a declaration clash. */
package de.westnordost.streetcomplete.tools.overpass

import de.westnordost.streetcomplete.data.elementfilter.toOverpassQLString
import de.westnordost.streetcomplete.data.osm.osmquests.OsmElementQuestType
import de.westnordost.streetcomplete.data.osm.osmquests.OsmFilterQuestType
import de.westnordost.streetcomplete.quests.questTypeRegistry
import dev.mokkery.mock

fun main() {
    val registry = questTypeRegistry(mock(), mock(), mock(), mock())

    for (questType in registry) {
        if (questType is OsmElementQuestType<*>) {
            println("### " + questType.name)
            try {
                if (questType is OsmFilterQuestType<*>) {
                    val query = "[bbox:{{bbox}}];\n" + questType.filter.toOverpassQLString() + "\nout meta geom;"
                    println("```\n$query\n```")
                } else {
                    println("Not available, see source code")
                }
            } catch (e: Exception) {
                println("Error: Not available, see source code")
            }
            println()
        }
    }
}
