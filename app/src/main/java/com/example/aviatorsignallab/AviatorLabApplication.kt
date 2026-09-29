package com.example.aviatorsignallab

import android.app.Application
import com.example.aviatorsignallab.db.ResearchDatabase

class AviatorLabApplication : Application() {

    val database: ResearchDatabase by lazy {
        ResearchDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AviatorLabApplication
            private set
    }
}
