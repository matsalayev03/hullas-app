package org.hullas.agent

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** CameraX uchun service ichida lifecycle. */
object ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle get() = registry
}