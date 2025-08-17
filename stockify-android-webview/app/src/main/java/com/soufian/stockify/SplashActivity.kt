package com.soufian.stockify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immediately jump to MainActivity
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        // Optional fade to feel smoother
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
