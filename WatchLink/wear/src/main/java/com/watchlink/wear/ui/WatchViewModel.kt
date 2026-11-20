package com.watchlink.wear.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Holds which notification the detail / reply screens are showing. */
class WatchViewModel : ViewModel() {
    var selectedKey by mutableStateOf<String?>(null)
}
