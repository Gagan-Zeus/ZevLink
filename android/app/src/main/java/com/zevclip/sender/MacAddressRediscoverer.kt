package com.zevclip.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object MacAddressRediscoverer {
    private const val TAG = "ZevClip"
    private const val SERVICE_TYPE = "_zevclip._tcp."
    private const val DISCOVERY_TIMEOUT_MS = 8_000L

    fun resolvePairedMac(
        context: Context,
        targetDeviceId: String,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): Endpoint? {
        val normalizedTargetDeviceId = targetDeviceId.trim()
        if (normalizedTargetDeviceId.isEmpty()) {
            return null
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Refusing to run blocking NSD rediscovery on the main thread")
            return null
        }

        val appContext = context.applicationContext
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (!isWifiConnected(connectivityManager)) {
            Log.i(TAG, "Skipping paired Mac rediscovery because Wi-Fi is not connected")
            return null
        }

        val nsdManager = appContext.getSystemService(NsdManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val result = AtomicReference<Endpoint?>()
        val listenerRef = AtomicReference<NsdManager.DiscoveryListener?>()
        val pendingServices = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)

        fun stopDiscovery() {
            if (!stopped.compareAndSet(false, true)) {
                return
            }

            val listener = listenerRef.getAndSet(null) ?: return
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Paired Mac rediscovery was already stopped", error)
            }
        }

        fun maybeResolveNext() {
            if (stopped.get() || result.get() != null || !resolving.compareAndSet(false, true)) {
                return
            }

            val service = pendingServices.poll()
            if (service == null) {
                resolving.set(false)
                return
            }

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    handler.post {
                        Log.w(
                            TAG,
                            "Paired Mac rediscovery resolve failed for ${serviceInfo.serviceName}: $errorCode"
                        )
                        resolving.set(false)
                        maybeResolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        val resolvedDeviceId = serviceInfo.txtString("deviceId")
                        if (resolvedDeviceId == normalizedTargetDeviceId) {
                            val host = resolvedHost(serviceInfo)
                            val port = serviceInfo.port
                            if (host != null && NetworkInputValidator.parsePort(port.toString()) != null) {
                                val endpoint = Endpoint(host, port)
                                result.set(endpoint)
                                Log.i(
                                    TAG,
                                    "Rediscovered paired Mac ${serviceInfo.serviceName} at $host:$port"
                                )
                                stopDiscovery()
                                latch.countDown()
                                return@post
                            }

                            Log.w(
                                TAG,
                                "Paired Mac rediscovery matched deviceId but had no usable endpoint: $serviceInfo"
                            )
                        }

                        resolving.set(false)
                        maybeResolveNext()
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    nsdManager.resolveService(service, appContext.mainExecutor, resolveListener)
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(service, resolveListener)
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Paired Mac rediscovery resolve threw an exception", error)
                resolving.set(false)
                maybeResolveNext()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Paired Mac rediscovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (
                        !stopped.get() &&
                        normalizeServiceType(serviceInfo.serviceType) == normalizeServiceType(SERVICE_TYPE)
                    ) {
                        pendingServices.add(serviceInfo)
                        maybeResolveNext()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Paired Mac rediscovery service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Paired Mac rediscovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    Log.w(TAG, "Paired Mac rediscovery start failed: $errorCode")
                    listenerRef.set(null)
                    stopped.set(true)
                    latch.countDown()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Paired Mac rediscovery stop failed: $errorCode")
                listenerRef.set(null)
            }
        }

        listenerRef.set(discoveryListener)
        handler.post {
            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Paired Mac rediscovery threw an exception", error)
                listenerRef.set(null)
                stopped.set(true)
                latch.countDown()
            }
        }

        val found = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!found) {
            handler.post { stopDiscovery() }
            Log.i(TAG, "Paired Mac rediscovery timed out")
        }

        return result.get()
    }

    private fun NsdServiceInfo.txtString(key: String): String? {
        return attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolvedHost(serviceInfo: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }

        return addresses.firstOrNull { it is Inet4Address }
            ?.hostAddress
            ?.takeIf { it.isNotBlank() }
    }

    private fun isWifiConnected(connectivityManager: ConnectivityManager): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun normalizeServiceType(serviceType: String): String {
        return serviceType.trimEnd('.')
    }
}
