package de.westnordost.streetcomplete.ui.util

import de.westnordost.streetcomplete.quests.bbq_fuel.BbqFuel
import de.westnordost.streetcomplete.quests.board_type.BoardType
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertNotNull

/* `rememberSerializable` resolves its serializer through the reified `serializer<T>()` default
   argument, which is evaluated during composition - before the try/catch in SerializableSaver,
   which only guards save and restore. On the JVM an enum without @Serializable still resolves
   through the reflective fallback; on Kotlin/Native there is none, so it throws
   SerializationException, the exception escapes composition, and the whole Compose scene dies -
   a black screen with the process still alive, rather than a crash.

   That is what AddBoardTypeForm did on iOS for every tourism=information + information=board
   node. These assert the selection enums stay resolvable, on every target the suite runs on. */
class QuestFormStateSerializerTest {
    @Test fun boardTypeSetIsSerializable() {
        assertNotNull(serializer<Set<BoardType>>())
    }

    @Test fun bbqFuelSetIsSerializable() {
        assertNotNull(serializer<Set<BbqFuel>>())
    }
}
