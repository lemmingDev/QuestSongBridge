package com.questsongbridge.app

import android.app.Application
import android.content.Context

object AppContext {
    lateinit var app: Application
        private set
    fun init(a: Application) { app = a }
    val context: Context get() = app
}
