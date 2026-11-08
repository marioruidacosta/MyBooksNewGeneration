package com.mcosta.mybooks

/** The Android application, which owns the app's [AppContainer] (screens outside the shared UI, like the reader, use it). */
interface ContainerOwner {
    val container: AppContainer
}
