package com.dataroute.app

import android.app.Application

class DataRouteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkMonitor.start(this)
    }
}
