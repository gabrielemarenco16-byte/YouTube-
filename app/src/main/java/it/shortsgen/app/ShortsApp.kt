package it.shortsgen.app

import android.app.Application
import it.shortsgen.app.pipeline.GenerationRepository
import it.shortsgen.app.pipeline.JobStore

class ShortsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ricarica l'ultimo video creato, così resta visibile dopo un riavvio dell'app.
        GenerationRepository.setLastResult(JobStore(this).loadLastResult())
    }
}
