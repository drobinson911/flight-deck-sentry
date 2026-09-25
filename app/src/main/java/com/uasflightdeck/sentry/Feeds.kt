package com.uasflightdeck.sentry

import android.content.Context
import android.net.wifi.WifiManager
import com.uasflightdeck.sentry.core.Parsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Thin HTTP layer. Every call has hard timeouts: a hung socket must never stall a poller.
 * [onResponse] fires on ANY HTTP response (a 401 or 404 still proves the internet works), [onNetFail] when no
 * response came back at all: the internet monitor's evidence.
 */
class Http(private val onResponse: () -> Unit = {}, private val onNetFail: () -> Unit = {}) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** GET -> body text; throws IOException on any non-2xx. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
            // The UAS worker 403s default library user agents.
            .header("User-Agent", UA)
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = try { client.newCall(b.build()).execute() } catch (e: IOException) { onNetFail(); throw e }
        resp.use { r ->
            onResponse()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("empty body")
        }
    }

    companion object {
        val UA = "Mozilla/5.0 (Linux; Android 10; RC Plus) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 FlightDeckSentry/${BuildConfig.VERSION_NAME}"
    }
}

/**
 * Listens for the Overwatch LAN beacon (UDP 41120, JSON {"app":"overwatch","http":8080,...})
 * so the station is found with no typing. Runs alongside a typed station URL,
 * never instead of it.
 */
class BeaconListener(private val ctx: Context, private val onFound: (String) -> Unit) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sentry-beacon")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.soTimeout = 3000
                sock.bind(InetSocketAddress(41120))
                val buf = ByteArray(2048)
                while (coroutineContext.isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: java.net.SocketTimeoutException) { continue }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val o = runCatching { Parsers.parse(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
                    val app = (o["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase()
                    if (app != "overwatch" && app != "airmerge") continue
                    val port = (o["http"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 8080
                    val host = pkt.address?.hostAddress ?: continue
                    onFound("http://$host:$port")
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }
}
