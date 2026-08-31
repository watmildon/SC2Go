package de.westnordost.streetcomplete.util.ktx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// convenience shortcuts

private const val TAG = "ViewModel"

/* Nothing awaits what ViewModel.launch starts, so without a handler anything thrown inside it
   reaches the default one - and on Kotlin/Native an unhandled coroutine exception takes the whole
   app down, where on the JVM it would only have been logged. This is the single place that covers
   every view model in the app, which is what it is doing here rather than at each call site.

   Added first so that a handler passed in by the caller still wins: in a + b, b's elements are the
   ones kept. */
private val defaultExceptionHandler = CoroutineExceptionHandler { coroutineContext, e ->
    Log.e(TAG, "Uncaught exception in ${'$'}coroutineContext", e)
}

fun ViewModel.launch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch(defaultExceptionHandler + context, start, block)

/* No handler here on purpose: async holds what is thrown in the Deferred to rethrow at await, and
   a CoroutineExceptionHandler is ignored for it. */
fun <T> ViewModel.async(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> = viewModelScope.async(context, start, block)
