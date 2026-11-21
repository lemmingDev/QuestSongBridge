package com.questsongbridge.app

import android.app.Application

class QuestSongBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
    }
}
