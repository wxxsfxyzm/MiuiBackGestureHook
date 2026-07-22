package dev.codex.miuibackgesturehook

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Collections
import java.util.IdentityHashMap

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        mainHandler.post {
            if (isDead(service)) {
                return@post
            }
            liveServices.removeAll { candidate -> candidate === service }
            liveServices.add(service)
            if (currentService !== service) {
                currentService = service
                notifyServiceStateChanged(service)
            }
        }
    }

    override fun onServiceDied(service: XposedService) {
        synchronized(deadServices) {
            deadServices.add(service)
        }
        mainHandler.post {
            liveServices.removeAll { candidate -> candidate === service }
            if (currentService === service) {
                currentService = liveServices.lastOrNull()
                notifyServiceStateChanged(currentService)
            }
        }
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val listeners = mutableSetOf<ServiceStateListener>()
        private val liveServices = ArrayList<XposedService>()
        private val deadServices = Collections.newSetFromMap(
            IdentityHashMap<XposedService, Boolean>(),
        )

        private var currentService: XposedService? = null

        fun addServiceStateListener(
            listener: ServiceStateListener,
            notifyImmediately: Boolean,
        ) {
            listeners.add(listener)
            if (notifyImmediately) {
                listener.onServiceStateChanged(currentService)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun notifyServiceStateChanged(service: XposedService?) {
            listeners.toList().forEach { it.onServiceStateChanged(service) }
        }

        private fun isDead(service: XposedService): Boolean = synchronized(deadServices) {
            deadServices.contains(service)
        }
    }
}
