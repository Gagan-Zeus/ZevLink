package com.zevclip.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zevclip.sender.airplay.AirPlayTarget
import com.zevclip.sender.airplay.AirPlayTxt
import kotlin.concurrent.thread

data class AirPlayDiscoveredReceiver(
    val key: String,
    val target: AirPlayTarget,
    val serviceName: String
)

class AirPlayReceiverDiscoveryManager(
    context: Context,
    private val onReceiversChanged: (List<AirPlayDiscoveredReceiver>) -> Unit,
    private val onStatusChanged: (String, Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val receivers = linkedMapOf<String, AirPlayDiscoveredReceiver>()
    private val pendingServices = ArrayDeque<NsdServiceInfo>()
    private val seenServices = linkedSetOf<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    private val discoveryTimeout = Runnable {
        updateStatus(
            if (receivers.isEmpty()) {
                "No AirPlay audio receivers found. Check that AirPlay Receiver is enabled and on the same network."
            } else {
                "Found ${receivers.size} AirPlay receiver${if (receivers.size == 1) "" else "s"}."
            },
            false
        )
    }

    fun discover() {
        stop()

        if (!LocalNetworkAccess.canReachLocalPeers(connectivityManager)) {
            updateStatus(LocalNetworkAccess.unavailableMessage(), false)
            return
        }

        receivers.clear()
        pendingServices.clear()
        seenServices.clear()
        resolving = false
        onReceiversChanged(emptyList())
        updateStatus("Searching for AirPlay audio receivers...", true)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "AirPlay receiver discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post { handleServiceFound(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                handler.post {
                    val removed = receivers.values.firstOrNull { it.serviceName == serviceInfo.serviceName }
                    if (removed != null) {
                        receivers.remove(removed.key)
                        onReceiversChanged(receivers.values.sortedBy { it.target.name ?: it.serviceName })
                    }
                    Log.i(TAG, "AirPlay receiver lost: ${serviceInfo.serviceName}")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "AirPlay receiver discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    if (discoveryListener === this) discoveryListener = null
                    updateStatus("Could not start AirPlay discovery (error $errorCode).", false)
                    Log.w(TAG, "AirPlay discovery start failed: $errorCode")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    if (discoveryListener === this) discoveryListener = null
                    Log.w(TAG, "AirPlay discovery stop failed: $errorCode")
                }
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(RAOP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)
        } catch (error: RuntimeException) {
            discoveryListener = null
            updateStatus("Could not start AirPlay discovery: ${error.message ?: "unknown error"}", false)
            Log.w(TAG, "AirPlay discovery threw an exception", error)
        }
    }

    fun stop() {
        handler.removeCallbacks(discoveryTimeout)
        stopDiscovery()
        pendingServices.clear()
        seenServices.clear()
        resolving = false
    }

    private fun handleServiceFound(serviceInfo: NsdServiceInfo) {
        if (normalizeServiceType(serviceInfo.serviceType) != normalizeServiceType(RAOP_SERVICE_TYPE)) {
            return
        }
        if (!seenServices.add(serviceInfo.serviceName)) return
        pendingServices.addLast(serviceInfo)
        updateStatus("Resolving ${serviceInfo.displayName()}...", true)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val service = pendingServices.removeFirstOrNull() ?: return
        resolving = true

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    resolving = false
                    Log.w(TAG, "AirPlay resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handler.post { handleResolvedService(serviceInfo) }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(service, appContext.mainExecutor, listener)
            } else {
                nsdManager.resolveService(service, listener)
            }
        } catch (error: RuntimeException) {
            resolving = false
            Log.w(TAG, "AirPlay resolve threw an exception for ${service.serviceName}", error)
            resolveNext()
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val hosts = EndpointSelector.serviceHosts(serviceInfo)
        if (hosts.isEmpty() || NetworkInputValidator.parsePort(port.toString()) == null) {
            resolving = false
            resolveNext()
            return
        }

        thread(name = "ZevClipAirPlayProbe") {
            val host = EndpointSelector.selectReachableHost(hosts, port)
            handler.post {
                resolving = false
                if (host != null) {
                    val target = AirPlayTxt.targetFromTxt(
                        host = host,
                        port = port,
                        name = serviceInfo.displayName(),
                        txt = serviceInfo.attributes
                    )
                    val receiver = AirPlayDiscoveredReceiver(
                        key = keyFor(target),
                        target = target,
                        serviceName = serviceInfo.serviceName
                    )
                    receivers[receiver.key] = receiver
                    onReceiversChanged(receivers.values.sortedBy { it.target.name ?: it.serviceName })
                    updateStatus(
                        "Found ${receivers.size} AirPlay receiver${if (receivers.size == 1) "" else "s"}.",
                        true
                    )
                    Log.i(TAG, "Resolved AirPlay receiver ${serviceInfo.serviceName} to $host:$port from $hosts")
                }
                resolveNext()
            }
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "AirPlay discovery was already stopped", error)
        }
    }

    private fun updateStatus(status: String, isDiscovering: Boolean) {
        handler.post { onStatusChanged(status, isDiscovering) }
    }

    private fun normalizeServiceType(serviceType: String): String {
        return serviceType.trimEnd('.')
    }

    private fun NsdServiceInfo.displayName(): String {
        return serviceName.substringAfter('@', serviceName).ifBlank { serviceName }
    }

    companion object {
        fun keyFor(target: AirPlayTarget): String {
            return target.deviceId?.takeIf { it.isNotBlank() }
                ?: "${target.host}:${target.port}"
        }

        private const val TAG = "ZevClipAirPlayDiscovery"
        private const val RAOP_SERVICE_TYPE = "_raop._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
