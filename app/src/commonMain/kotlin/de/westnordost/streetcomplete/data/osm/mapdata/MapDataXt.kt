package de.westnordost.streetcomplete.data.osm.mapdata

import de.westnordost.streetcomplete.data.elementfilter.ElementFilterExpression
import de.westnordost.streetcomplete.data.elementfilter.toElementFilterExpression
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

fun MapData.filter(expr: String): Sequence<Element> =
    filter(FilterExpressionCache.get(expr))

fun MapData.filter(expr: ElementFilterExpression): Sequence<Element> {
    /* this is a considerate performance improvement over just iterating over the whole MapData
     * because filters that only include one (or two) element types, any filter checks
     * are completely avoided */
    return sequence {
        if (expr.includesElementType(ElementType.NODE)) yieldAll(nodes)
        if (expr.includesElementType(ElementType.WAY)) yieldAll(ways)
        if (expr.includesElementType(ElementType.RELATION)) yieldAll(relations)
    }.filter(expr::matches)
}

private object FilterExpressionCache {
    /* Guarded because quests are created for every quest type in parallel - each gets its own
       async on Dispatchers.Default in OsmQuestController.createQuestsForBBox - and they all come
       through here. Without the lock the concurrent getOrPut corrupts the map and throws
       ArrayIndexOutOfBoundsException, which on Kotlin/Native happens on more or less every
       download, the default dispatcher there really being multi-threaded.

       Only the map is locked, not the parsing: that is what the Lazy is for. */
    private val lock = ReentrantLock()
    private val cache = mutableMapOf<String, Lazy<ElementFilterExpression>>()

    fun get(expr: String): ElementFilterExpression =
        lock.withLock { cache.getOrPut(expr) { lazy { expr.toElementFilterExpression() } } }.value
}
