package com.ridelink.app

import android.app.Application
import com.ridelink.app.di.AppContainer

class RideLinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
