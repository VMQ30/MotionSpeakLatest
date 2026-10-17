package com.example.sigla

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.react.shell.MainReactPackage
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

class SiglaApplication : Application(), ReactApplication {

    private val mReactNativeHost: ReactNativeHost = object : DefaultReactNativeHost(this) {
        override fun getUseDeveloperSupport(): Boolean = false

        override fun getPackages(): List<ReactPackage> = listOf(
            MainReactPackage(),
            SiglaPackage()
        )

        override fun getJSMainModuleName(): String = "index"

        override fun getBundleAssetName(): String = "index.android.bundle"
    }

    override val reactNativeHost: ReactNativeHost
        get() = mReactNativeHost

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, OpenSourceMergedSoMapping)
        val isDarkMode = AppSettings.getInstance(this).isDarkMode
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
