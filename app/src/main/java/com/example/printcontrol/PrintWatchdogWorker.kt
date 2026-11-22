package com.example.printcontrol

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * CoroutineWorker je špeciálna trieda Androidu, ktorú systém spustí na pozadí
 * len raz za stanovený interval, vykoná kontrolu za 1 sekundu a znova uspí procesor.
 */
class PrintWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = SmartPrintRepository()

    override suspend fun doWork(): Result {
        return try {
            // Skontrolujeme stav tlačiarne, Hlídača tisku a prípadne vypneme Tapo zásuvku
            val state = repository.fetchLatestData()
            Log.d("PrintWatchdog", "Kontrola na pozadí úspešná. Tlač: ${state.printer.progressPercent}%")
            Result.success()
        } catch (e: Exception) {
            // Ak telefón práve nemá signál, WorkManager to automaticky skúsi neskôr
            Result.retry()
        }
    }

    companion object {
        // Túto funkciu zavoláme pri štarte aplikácie – naplánuje úspornú kontrolu každých 15 minút
        fun scheduleBackgroundWatchdog(context: Context) {
            val request = PeriodicWorkRequestBuilder<PrintWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "BambuTapoWatchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}