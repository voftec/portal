package dev.voftec.airplaytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AirPlayService::class.java)
                )
            } catch (e: Exception) {
                // restricted-boot FGS start (Android 12+ background limits):
                // log and let the activity start it on first launch instead
                Log.e("AirPlayTV", "no se pudo iniciar el servicio al arrancar: $e")
            }
        }
    }
}
