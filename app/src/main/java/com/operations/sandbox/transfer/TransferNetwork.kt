package com.operations.sandbox.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The address the old phone serves the transfer on: its own IPv4 address on the local Wi-Fi.
 *
 * Never a wildcard bind. A server on `0.0.0.0` would also answer on the mobile-data interface,
 * which a carrier can route to, and this server should be reachable from one room.
 *
 * Wi-Fi or Ethernet through ConnectivityManager first. Failing that, the phone's own hotspot,
 * which ConnectivityManager doesn't report as a network the phone is *on*: with no shared Wi-Fi,
 * the new phone can join the old phone's hotspot and the transfer still works.
 */
object TransferNetwork {

    fun localAddress(context: Context): InetAddress? = fromConnectivity(context) ?: fromHotspot()

    private fun fromConnectivity(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private fun fromHotspot(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && HOTSPOT_NAMES.any { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private val HOTSPOT_NAMES = listOf("ap", "swlan", "softap", "wlan")
}
