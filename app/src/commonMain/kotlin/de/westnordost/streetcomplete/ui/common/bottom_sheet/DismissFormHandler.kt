package de.westnordost.streetcomplete.ui.common.bottom_sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import de.westnordost.streetcomplete.ui.common.NonPredictiveBackHandler
import de.westnordost.streetcomplete.ui.common.quest.LocalLastMapClick

/** Handles the two ways in which the user can ask to close a bottom sheet form without answering
 *  it: the back gesture, and a click on the map next to the form.
 *
 *  The latter is what IsCloseableBottomSheet.onClickMapAt did in the view-based app (v63 and
 *  earlier): a click on the map is offered to the form first, and only when the form does not want
 *  it does it close the form. A form that uses map clicks for something itself - picking up the
 *  name of the road that was clicked, for example - sets [consumesMapClicks] and is then not
 *  dismissed by them.
 *
 *  [onDismissRequest] is a request, not an order: it is up to the form whether to close right away
 *  or to first ask whether the input made so far should really be discarded.
 *  */
@Composable
fun DismissFormHandler(
    consumesMapClicks: Boolean = false,
    onDismissRequest: () -> Unit,
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    NonPredictiveBackHandler { currentOnDismissRequest() }

    /* Only a click made while this form is shown counts. The last click is cleared whenever another
       bottom sheet is shown, but not when one form replaces another within the same bottom sheet -
       e.g. "leave a note instead" after a click picked up the name of a road - and that click must
       not dismiss the form that opens next. */
    val mapClick = LocalLastMapClick.current
    val mapClickBeforeShown = remember { mapClick }
    LaunchedEffect(mapClick) {
        if (mapClick != null && mapClick !== mapClickBeforeShown && !consumesMapClicks) {
            currentOnDismissRequest()
        }
    }
}
