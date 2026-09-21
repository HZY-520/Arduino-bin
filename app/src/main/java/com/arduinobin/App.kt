package com.arduinobin

import android.app.Application
import com.arduinobin.termux.TermuxEnv

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TermuxEnv.setup(this)
    }
}