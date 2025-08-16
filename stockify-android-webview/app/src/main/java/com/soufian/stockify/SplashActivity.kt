package com.soufian.stockify

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the splash theme for instant colored background
        setTheme(R.style.Theme_Stockify_Splash)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Play the fade/zoom animation
        val logo: ImageView = findViewById(R.id.splashLogo)
        logo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_in))

        // Short delay then open MainActivity and fade out
        Handler(Looper.getMainLooper()).postDelayed({
            overridePendingTransition(0, R.anim.splash_out)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 600) // total splash time ~600ms; tweak if you want
    }
}
