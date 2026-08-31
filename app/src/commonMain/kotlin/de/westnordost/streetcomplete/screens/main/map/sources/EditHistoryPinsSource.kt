package de.westnordost.streetcomplete.screens.main.map.sources

import de.westnordost.streetcomplete.data.edithistory.Edit
import de.westnordost.streetcomplete.data.edithistory.EditHistorySource
import de.westnordost.streetcomplete.data.edithistory.EditKey
import de.westnordost.streetcomplete.data.edithistory.ElementEditKey
import de.westnordost.streetcomplete.data.edithistory.NoteEditKey
import de.westnordost.streetcomplete.data.edithistory.QuestHiddenKey
import de.westnordost.streetcomplete.data.osm.edits.ElementEdit
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import de.westnordost.streetcomplete.data.osm.osmquests.OsmQuestHidden
import de.westnordost.streetcomplete.data.osmnotes.edits.NoteEdit
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestHidden
import de.westnordost.streetcomplete.data.quest.OsmNoteQuestKey
import de.westnordost.streetcomplete.data.quest.OsmQuestKey
import de.westnordost.streetcomplete.screens.main.edithistory.icon
import de.westnordost.streetcomplete.screens.main.map.layers.Pin
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class EditHistoryPinsSource(
    private val editHistorySource: EditHistorySource
) {
    val pins: Flow<Collection<Pin>> = callbackFlow {
        /* The three callbacks below arrive on three different threads - onAdded on whichever
           coroutine stored the answer, onDeleted on the uploader's and on the cleaner's,
           onInvalidated on a third - and they all reach this one map. Unguarded that is the same
           concurrent-HashMap corruption as the caches fixed in e8295e70b and 3a5f83f21, on the
           rhythm of ordinary surveying: answering a quest while an upload started seconds ago is
           deleting synced edits.

           What is sent is a copy, taken under the same lock as the change that produced it. It was
           sending `values`, a live view of the map, which the layers then iterate twice on the main
           thread while these callbacks are still writing to it. Snapshotting inside the lock also
           keeps two threads from sending their versions out of order. */
        val lock = ReentrantLock()
        var pinsByKey = getAllEdits()
            .withIndex()
            .associateTo(HashMap()) { (index, edit) -> edit.key to edit.toEditPin(index) }

        fun changeAndSnapshot(change: () -> Unit): List<Pin> =
            lock.withLock { change(); pinsByKey.values.toList() }

        val listener = object : EditHistorySource.Listener {
            override fun onAdded(added: Edit) {
                trySend(changeAndSnapshot { pinsByKey[added.key] = added.toEditPin(pinsByKey.size) })
            }
            override fun onSynced(synced: Edit) {  }
            override fun onDeleted(deleted: List<Edit>) {
                trySend(changeAndSnapshot { deleted.forEach { pinsByKey.remove(it.key) } })
            }
            override fun onInvalidated() {
                launch {
                    // fetched before taking the lock: it reads the database
                    val edits = getAllEdits()
                    // ... and sent, which it never was, so the pins stayed as they were
                    trySend(changeAndSnapshot {
                        pinsByKey = edits
                            .withIndex()
                            .associateTo(HashMap()) { (index, edit) -> edit.key to edit.toEditPin(index) }
                    })
                }
            }
        }

        send(lock.withLock { pinsByKey.values.toList() })
        editHistorySource.addListener(listener)
        awaitClose {
            editHistorySource.removeListener(listener)
        }
    }

    private suspend fun getAllEdits(): List<Edit> =
        withContext(Dispatchers.IO) { editHistorySource.getAll() }

    fun getEditKey(properties: JsonObject): EditKey? =
        properties.toEditKey()
}

private const val MARKER_EDIT_TYPE = "edit_type"

private const val MARKER_ELEMENT_TYPE = "element_type"
private const val MARKER_ELEMENT_ID = "element_id"
private const val MARKER_QUEST_TYPE = "quest_type"
private const val MARKER_NOTE_ID = "note_id"
private const val MARKER_ID = "id"

private const val EDIT_TYPE_ELEMENT = "element"
private const val EDIT_TYPE_NOTE = "note"
private const val EDIT_TYPE_HIDE_OSM_NOTE_QUEST = "hide_osm_note_quest"
private const val EDIT_TYPE_HIDE_OSM_QUEST = "hide_osm_quest"

private fun Edit.toEditPin(order: Int) = Pin(position, icon!!, toProperties(), order)

private fun Edit.toProperties() = JsonObject(when (this) {
    is ElementEdit -> mapOf(
        MARKER_EDIT_TYPE to JsonPrimitive(EDIT_TYPE_ELEMENT),
        MARKER_ID to JsonPrimitive(id)
    )
    is NoteEdit -> mapOf(
        MARKER_EDIT_TYPE to JsonPrimitive(EDIT_TYPE_NOTE),
        MARKER_ID to JsonPrimitive(id)
    )
    is OsmNoteQuestHidden -> mapOf(
        MARKER_EDIT_TYPE to JsonPrimitive(EDIT_TYPE_HIDE_OSM_NOTE_QUEST),
        MARKER_NOTE_ID to JsonPrimitive(note.id)
    )
    is OsmQuestHidden -> mapOf(
        MARKER_EDIT_TYPE to JsonPrimitive(EDIT_TYPE_HIDE_OSM_QUEST),
        MARKER_ELEMENT_TYPE to JsonPrimitive(elementType.name),
        MARKER_ELEMENT_ID to JsonPrimitive(elementId),
        MARKER_QUEST_TYPE to JsonPrimitive(questType.name)
    )
    else -> throw IllegalArgumentException()
})

private fun JsonObject.toEditKey(): EditKey? {
    val editType = get(MARKER_EDIT_TYPE)?.jsonPrimitive?.contentOrNull
    return when (editType) {
        EDIT_TYPE_ELEMENT ->
            ElementEditKey(getValue(MARKER_ID).jsonPrimitive.long)
        EDIT_TYPE_NOTE ->
            NoteEditKey(getValue(MARKER_ID).jsonPrimitive.long)
        EDIT_TYPE_HIDE_OSM_QUEST ->
            QuestHiddenKey(OsmQuestKey(
                ElementType.valueOf(getValue(MARKER_ELEMENT_TYPE).jsonPrimitive.content),
                getValue(MARKER_ELEMENT_ID).jsonPrimitive.long,
                getValue(MARKER_QUEST_TYPE).jsonPrimitive.content
            ))
        EDIT_TYPE_HIDE_OSM_NOTE_QUEST ->
            QuestHiddenKey(OsmNoteQuestKey(getValue(MARKER_NOTE_ID).jsonPrimitive.long))
        else -> null
    }
}
