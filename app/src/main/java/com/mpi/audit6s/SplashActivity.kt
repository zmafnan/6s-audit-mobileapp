package com.mpi.audit6s

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash screen activity that displays when the app is launched.
 * Shows the company logo for a set duration before transitioning to the main activity.
 */
class SplashActivity : AppCompatActivity() {

    // How long to show the splash screen (in milliseconds)
    private val SPLASH_DURATION = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Use a handler to delay the transition to the main activity
        Handler(Looper.getMainLooper()).postDelayed({
            // Create an intent to launch the main activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)

            // Close this activity so it's removed from the back stack
            finish()

            // Optional: Add a fade transition
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, SPLASH_DURATION)
    }
}
