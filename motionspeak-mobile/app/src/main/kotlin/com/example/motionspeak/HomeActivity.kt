package com.example.motionspeak

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.motionspeak.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Home (spec §3, §5), the first screen after SplashActivity's intro.
 * Reads only data already on the phone — history,
 * favorites and the cached word bank — so it works offline and never waits on
 * the network. Recomputed on every onResume so a translation made a moment ago
 * shows up immediately.
 */
// How long after Home is created the background preloads start: long enough
// for launch and the entrance animations to finish first.
private const val PRELOAD_DELAY_MS = 1500L

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings.getInstance(this)

        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            HandLandmarkHelper.Cache.prewarm(app)
            ModelUpdateManager.prefetch(app)
        }, PRELOAD_DELAY_MS)

        // First launch (or "Replay tutorial"): onboarding comes first and finishes
        // back into Home. Moved here from MainActivity, which is no longer the launcher.
        if (!appSettings.isOnboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BottomNavHelper.setup(this, Tab.HOME)
        binding.btnHeroStart.setOnClickListener { BottomNavHelper.openTranslator(this) }
        binding.actionSignDictionary.setOnClickListener { BottomNavHelper.open(this, Tab.WORD_BANK) }
        binding.actionMediSignReference.setOnClickListener { BottomNavHelper.open(this, Tab.WORD_BANK) }
        binding.actionAppSettings.setOnClickListener { BottomNavHelper.open(this, Tab.SETTINGS) }

        // Home is the root: back leaves the app. finishAffinity() also closes the
        // other tab screens kept alive behind it by the bottom bar.
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refresh()
    }

    private fun refresh() {
        renderGreeting()

        lifecycleScope.launch {
            val words = withContext(Dispatchers.IO) {
                ModelUpdateManager.loadCachedWordBank(this@HomeActivity)
            }.orEmpty()
            val count = if (words.isNotEmpty()) words.size else 37
            binding.tvSignDictionaryCount.text = "$count medical FSL signs"
        }
    }

    private fun renderGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = homeGreeting(hour, appSettings.userName)
        binding.tvGreetingTitle.text = greeting.title
        binding.tvGreetingSubtitle.text = greeting.subtitle.orEmpty()
        binding.tvGreetingSubtitle.isVisible = greeting.subtitle != null

        val name = appSettings.userName?.trim()?.takeIf { it.isNotEmpty() }
        binding.tvAvatar.isVisible = name != null
        binding.tvAvatar.text = name?.take(1)?.uppercase().orEmpty()
    }
}
