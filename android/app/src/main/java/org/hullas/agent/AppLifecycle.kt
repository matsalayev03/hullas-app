package org.hullas.agent

import androidx.lifecycle.LifecycleOwner

object AppLifecycle {
    @Volatile
    var mainActivity: LifecycleOwner? = null

    @Volatile
    var serviceLifecycle: LifecycleOwner? = null

    fun cameraLifecycle(): LifecycleOwner =
        serviceLifecycle ?: mainActivity
        ?: throw Exception("Kamera: monitoring yo'q")
}