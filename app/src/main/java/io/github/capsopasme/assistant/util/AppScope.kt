package io.github.capsopasme.assistant.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** For short clean-up work that must outlive the screen that started it (e.g. switching the keyboard back). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
