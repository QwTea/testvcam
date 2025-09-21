package com.example.virtualcam

import android.app.Application
import android.util.Log
import com.example.virtualcam.data.AppDatabase
import com.example.virtualcam.prefs.ModulePrefs
import java.util.concurrent.CopyOnWriteArrayList

// GREP: VCAM_TAG
const val VCAM_TAG = "VirtualCam"

// GREP: VCAM_LOGGER
fun logVcam(msg: String) {
    Log.i(VCAM_TAG, msg)
    VirtualCamLogBuffer.append(msg)
}

object VirtualCamLogBuffer {
    private const val MAX_LINES = 200
    private val buffer = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    fun append(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeFirst()
            }
            val snapshot = buffer.toList()
            listeners.forEach { it.invoke(snapshot) }
        }
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun register(listener: (List<String>) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun unregister(listener: (List<String>) -> Unit) {
        listeners -= listener
    }
}

class VirtualCamApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var modulePrefs: ModulePrefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.get(this)
        modulePrefs = ModulePrefs.getInstance(this)
        logVcam("VirtualCamApp initialized")
    }

    companion object {
        @Volatile
        private var instance: VirtualCamApp? = null

        fun get(): VirtualCamApp {
            return instance ?: throw IllegalStateException("VirtualCamApp not yet created")
        }
    }
}
