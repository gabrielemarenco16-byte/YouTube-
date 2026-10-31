package it.shortsgen.app.pipeline

import android.app.Service
import android.content.Intent
import android.os.IBinder

class GenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
