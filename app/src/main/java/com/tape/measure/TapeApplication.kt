package com.tape.measure

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's application entry point.
 * `@HiltAndroidApp` triggers Hilt's code generation so the dependency
 * graph exists by the time MainActivity is created.
 */
@HiltAndroidApp
class TapeApplication : Application()