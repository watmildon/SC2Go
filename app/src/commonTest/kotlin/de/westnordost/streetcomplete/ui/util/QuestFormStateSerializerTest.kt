package de.westnordost.streetcomplete.ui.util

import de.westnordost.streetcomplete.data.meta.LengthUnit
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.osm.address.StreetOrPlaceName
import de.westnordost.streetcomplete.osm.cycleway.CyclewayFormSelectionMode
import de.westnordost.streetcomplete.osm.duration.DurationUnit
import de.westnordost.streetcomplete.osm.opening_hours.HierarchicOpeningHours
import de.westnordost.streetcomplete.quests.bbq_fuel.BbqFuel
import de.westnordost.streetcomplete.quests.board_type.BoardType
import de.westnordost.streetcomplete.quests.internet_access.InternetAccess
import de.westnordost.streetcomplete.quests.recycling_material.RecyclingMaterial
import de.westnordost.streetcomplete.quests.steps_ramp.StepsRamp
import de.westnordost.streetcomplete.ui.common.opening_hours.TimeMode
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertNotNull

/* `rememberSerializable` resolves its serializer through the reified `serializer<T>()` default
   argument, which is evaluated *during composition* - before the try/catch in SerializableSaver,
   which only guards save and restore. On the JVM a type without @Serializable still resolves
   through the reflective fallback; on Kotlin/Native there is none, so it throws
   SerializationException, the exception escapes composition, and the whole Compose scene dies -
   a black screen with the process still alive, rather than a crash.

   This has now happened twice in the field:

     BoardType  - every tourism=information + information=board node (fixed 2026-09-03)
     TimeMode   - the postbox collection times quest (fixed 2026-09-05, found in the device's
                  own log database, days after the first one was called the only offender)

   The first sweep for siblings only looked at types passed as `items =` to the generic select
   forms, so a state type declared directly at a `rememberSerializable` call site was invisible to
   it. That is why this test enumerates the *state types* instead: every concrete type that any
   `rememberSerializable` in commonMain stores. Add to it whenever a new one appears. */
class QuestFormStateSerializerTest {

    @Test fun boardTypeSetIsSerializable() {
        assertNotNull(serializer<Set<BoardType>>())
    }

    @Test fun bbqFuelSetIsSerializable() {
        assertNotNull(serializer<Set<BbqFuel>>())
    }

    /** The postbox collection times form. Black-screened on device before this was annotated. */
    @Test fun timeModeIsSerializable() {
        assertNotNull(serializer<TimeMode>())
    }

    @Test fun internetAccessSetIsSerializable() {
        assertNotNull(serializer<Set<InternetAccess>>())
    }

    @Test fun recyclingMaterialSetIsSerializable() {
        assertNotNull(serializer<Set<RecyclingMaterial>>())
    }

    @Test fun stepsRampSetIsSerializable() {
        assertNotNull(serializer<Set<StepsRamp>>())
    }

    @Test fun cyclewayFormSelectionModeIsSerializable() {
        assertNotNull(serializer<CyclewayFormSelectionMode>())
    }

    @Test fun durationUnitIsSerializable() {
        assertNotNull(serializer<DurationUnit>())
    }

    @Test fun lengthUnitIsSerializable() {
        assertNotNull(serializer<LengthUnit>())
    }

    @Test fun streetOrPlaceNameIsSerializable() {
        assertNotNull(serializer<StreetOrPlaceName>())
    }

    @Test fun hierarchicOpeningHoursIsSerializable() {
        assertNotNull(serializer<HierarchicOpeningHours>())
    }

    /** The edit history sidebar keeps the selected element in saveable state. */
    @Test fun nullableElementIsSerializable() {
        assertNotNull(serializer<Element?>())
    }
}
