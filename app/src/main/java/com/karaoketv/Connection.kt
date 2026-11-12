package com.karaoketv

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ExecutorService

/**
 * The TV's connection, for two separate things:
 * - [online]: can it reach YouTube? Videos and searches need it. Android's network state alone
 *   isn't enough (Wi-Fi up but the internet down), so after a failure a tiny request to YouTube
 *   confirms it, and repeats every few seconds until it works again.
 * - [lanAddress]: the TV's address on the home network, for the phones' QR code. It changes
 *   when the TV switches network or gets a new address.
 * [onChange] runs on the main thread whenever either changes.
 */
class Connection(context: Context, private val io: ExecutorService, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var online = true
        private set
    @Volatile var lanAddress: String? = null
        private set

    private var probing = false
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = changed()
    }

    fun start() {
        if (started) return
        started = true
        lanAddress = readLanAddress()
        if (!hasNetwork()) online = false
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(callback) }
        main.removeCallbacksAndMessages(null)
    }

    /** Something that needed the internet just failed: check, and say so if it's really down. */
    fun suspect() { main.post { probe() } }

    /** Checks now and tells [then] (on the main thread) whether YouTube can be reached. */
    fun check(then: (Boolean) -> Unit) {
        val ok = runCatching {
            io.execute {
                val reachable = hasNetwork() && reachYouTube()
                main.post { if (started) set(reachable); then(reachable) }
            }
        }.isSuccess
        if (!ok) then(online)
    }

    // Network events arrive in bursts (Wi-Fi reconnecting fires several): act once they settle
    private fun changed() {
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
    }

    private val settle = Runnable {
        if (!started) return@Runnable
        val address = readLanAddress()
        val addressChanged = address != lanAddress
        lanAddress = address
        if (!hasNetwork()) set(false, addressChanged) else { if (addressChanged) onChange(); probe() }
    }

    private fun set(value: Boolean, alsoChanged: Boolean = false) {
        val changed = online != value
        online = value
        if (changed || alsoChanged) onChange()
        // Down: keep checking until YouTube answers again
        main.removeCallbacks(recheck)
        if (!value && started) main.postDelayed(recheck, RECHECK_MS)
    }

    private val recheck = Runnable { probe() }

    private fun probe() {
        if (probing || !started) return
        if (!hasNetwork()) { set(false); return }
        probing = true
        val ok = runCatching {
            io.execute {
                val reachable = reachYouTube()
                main.post { probing = false; if (started) set(reachable) }
            }
        }.isSuccess
        if (!ok) probing = false
    }

    private fun hasNetwork(): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun readLanAddress(): String? = runCatching {
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        const val SETTLE_MS = 1200L
        const val RECHECK_MS = 6000L

        /** YouTube's "no content" address: tiny, made for exactly this check. */
        fun reachYouTube(): Boolean = runCatching {
            val c = URL("https://www.youtube.com/generate_204").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.instanceFollowRedirects = false
                c.responseCode in 200..399
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }
}
