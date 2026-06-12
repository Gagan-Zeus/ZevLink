package com.zevclip.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

class MacDiscoveryManager(
    context: Context,
    private val onStatusChanged: (String, Boolean) -> Unit,
    private val onEndpointResolved: (String, Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val services = linkedMapOf<String, NsdServiceInfo>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    private val selectService = Runnable { selectAndResolveService() }
    private val discoveryTimeout = Runnable {
        if (services.isEmpty()) {
            stopDiscovery()
            updateStatus("No ZevLink Mac receiver found. Confirm the Mac receiver is running.", false)
        } else {
            selectAndResolveService()
        }
    }

    fun discover() {
        stop()

        if (!LocalNetworkAccess.canReachLocalPeers(connectivityManager)) {
            updateStatus(LocalNetworkAccess.unavailableMessage(), false)
            return
        }

        services.clear()
        resolving = false
        updateStatus("Searching for ZevLink Mac receivers…", true)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post { handleServiceFound(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                handler.post {
                    services.remove(serviceInfo.serviceName)
                    Log.i(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    if (discoveryListener === this) {
                        discoveryListener = null
                    }
                    updateStatus("Could not start Mac discovery (error $errorCode).", false)
                    Log.w(TAG, "NSD discovery start failed: $errorCode")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    if (discoveryListener === this) {
                        discoveryListener = null
                    }
                    updateStatus("Could not stop Mac discovery cleanly (error $errorCode).", false)
                    Log.w(TAG, "NSD discovery stop failed: $errorCode")
                }
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)
        } catch (error: RuntimeException) {
            discoveryListener = null
            updateStatus("Could not start Mac discovery: ${error.message ?: "unknown error"}", false)
            Log.w(TAG, "NSD discovery threw an exception", error)
        }
    }

    private fun handleServiceFound(serviceInfo: NsdServiceInfo) {
        if (normalizeServiceType(serviceInfo.serviceType) != normalizeServiceType(SERVICE_TYPE)) {
            return
        }

        services[serviceInfo.serviceName] = serviceInfo
        val count = services.size
        updateStatus(
            if (count == 1) {
                "Found ${serviceInfo.serviceName}. Resolving…"
            } else {
                "Found $count Mac receivers. Selecting ${preferredService().serviceName}…"
            },
            true
        )

        handler.removeCallbacks(selectService)
        handler.postDelayed(selectService, SELECTION_DELAY_MS)
    }

    fun stop() {
        handler.removeCallbacks(selectService)
        handler.removeCallbacks(discoveryTimeout)
        stopDiscovery()
        services.clear()
        resolving = false
    }

    @Suppress("DEPRECATION")
    private fun selectAndResolveService() {
        if (resolving || services.isEmpty()) {
            return
        }

        resolving = true
        handler.removeCallbacks(selectService)
        handler.removeCallbacks(discoveryTimeout)

        val service = preferredService()
        val serviceCount = services.size
        stopDiscovery()
        updateStatus(
            if (serviceCount > 1) {
                "Found $serviceCount Mac receivers. Resolving ${service.serviceName}…"
            } else {
                "Resolving ${service.serviceName}…"
            },
            true
        )

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    resolving = false
                    updateStatus(
                        "Found ${serviceInfo.serviceName}, but resolve failed (error $errorCode).",
                        false
                    )
                    Log.w(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handler.post { handleResolvedService(serviceInfo, serviceCount) }
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
            updateStatus("Could not resolve ${service.serviceName}: ${error.message ?: "unknown error"}", false)
            Log.w(TAG, "NSD resolve threw an exception", error)
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo, serviceCount: Int) {
        val port = serviceInfo.port
        val hosts = EndpointSelector.serviceHosts(serviceInfo)

        if (hosts.isEmpty() || NetworkInputValidator.parsePort(port.toString()) == null) {
            resolving = false
            updateStatus("Found ${serviceInfo.serviceName}, but it has no usable host or port.", false)
            Log.w(TAG, "NSD resolved without a usable endpoint: $serviceInfo")
            return
        }

        updateStatus("Checking ${serviceInfo.serviceName} reachability…", true)
        thread(name = "ZevClipDiscoveryProbe") {
            val host = EndpointSelector.selectReachableHost(hosts, port)
            handler.post {
                resolving = false
                if (host == null) {
                    updateStatus(
                        "Found ${serviceInfo.serviceName}, but none of its addresses are reachable from this network.",
                        false
                    )
                    Log.w(TAG, "NSD resolved unreachable endpoints for ${serviceInfo.serviceName}: $hosts")
                    return@post
                }

                val multipleNote = if (serviceCount > 1) " Chose 1 of $serviceCount receivers." else ""
                updateStatus(
                    "Discovered ${serviceInfo.serviceName} at ${host.formatEndpointHost()}:$port.$multipleNote",
                    false
                )
                onEndpointResolved(host, port)
                Log.i(TAG, "NSD resolved ${serviceInfo.serviceName} to $host:$port from $hosts")
            }
        }
    }

    private fun preferredService(): NsdServiceInfo {
        return services.values.sortedWith(
            compareBy<NsdServiceInfo> {
                if (it.serviceName == PREFERRED_SERVICE_NAME) 0 else 1
            }.thenBy { it.serviceName }
        ).first()
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null

        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "NSD discovery was already stopped", error)
        }
    }

    private fun updateStatus(status: String, isDiscovering: Boolean) {
        handler.post {
            ZevClipPreferences.setDiscoveryStatus(appContext, status)
            onStatusChanged(status, isDiscovering)
        }
    }

    private fun normalizeServiceType(serviceType: String): String {
        return serviceType.trimEnd('.')
    }

    private fun String.formatEndpointHost(): String {
        return if (contains(':')) "[$this]" else this
    }

    private companion object {
        const val TAG = "ZevClip"
        const val SERVICE_TYPE = "_zevclip._tcp."
        const val PREFERRED_SERVICE_NAME = "ZevLink Mac Receiver"
        const val SELECTION_DELAY_MS = 1_500L
        const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
