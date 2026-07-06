package org.hullas.agent

import androidx.lifecycle.LifecycleOwner

object AppLifecycle {
    @Volatile
    var mainActivity: LifecycleOwner? = null
}