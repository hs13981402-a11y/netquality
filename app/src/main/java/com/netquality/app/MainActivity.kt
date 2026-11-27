package com.netquality.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // ───────────── تنظیمات قابل ویرایش ─────────────
    data class Target(val name: String, val host: String, val port: Int)
    data class Site(val name: String, val url: String, val internal: Boolean)

    companion object {
        // اهداف پینگ (port برای حالتی است که ICMP مسدود باشد و با TCP تست شود)
        val TARGETS = listOf(
            Target("Google DNS", "8.8.8.8", 53),
            Target("Cloudflare", "1.1.1.1", 53)
        )

        // سایت‌های تست دسترسی؛ internal = سایت داخلی ایران
        val SITES = listOf(
            Site("Google", "https://www.gstatic.com/generate_204", false),
            Site("Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace", false),
            Site("دیجی‌کالا", "https://www.digikala.com", true),
            Site("آپارات", "https://www.aparat.com", true)
        )

        // دامنه‌های تست DNS
        val DNS_DOMAINS = listOf("google.com", "digikala.com")

        // آدرس‌های تست سرعت دانلود (به ترتیب امتحان می‌شوند)
        val SPEED_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "http://cachefly.cachefly.net/10mb.test",
            "https://proof.ovh.net/files/10Mb.dat"
        )

        // تشخیص Captive Portal (صفحه‌ی ورود هات‌اسپات)
        const val CAPTIVE_URL = "http://connectivitycheck.gstatic.com/generate_204"

        const val PREFS = "netq"
        const val KEY_HISTORY = "history"
    }

    // ───────────── مدل‌ها ─────────────
    data class PingResult(
        val host: String, val sent: Int, val received: Int,
        val avg: Double, val min: Double, val max: Double,
        val jitter: Double, val method: String
    ) {
        val loss: Double get() = if (sent == 0) 100.0 else (sent - received) * 100.0 / sent
    }

    data class HttpRes(val code: Int?, val ms: Long, val err: String?) {
        val ok: Boolean get() = code != null
    }

    data class NetInfo(
        val connected: Boolean, val type: String, val ssid: String,
        val rssi: Int?, val freq: Int?, val link: Int?, val gateway: String?
    )

    // ───────────── وضعیت UI ─────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnHistory: Button
    private lateinit var progress: ProgressBar
    private lateinit var cbAuto: CheckBox
    private lateinit var cm: ConnectivityManager
    private var running = false
    private val log = StringBuilder()

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (cbAuto.isChecked && !running) {
                    lifecycleScope.launch {
                        delay(2500) // صبر کن اتصال کامل برقرار بشه
                        if (!running && cbAuto.isChecked) runTest()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread { if (!running) tvStatus.text = "❌ اتصال شبکه قطع شد" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)
        btnHistory = findViewById(R.id.btnHistory)
        progress = findViewById(R.id.progress)
        cbAuto = findViewById(R.id.cbAuto)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        btnStart.setOnClickListener { runTest() }
        btnHistory.setOnClickListener { showHistory() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        }
        cm.registerDefaultNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cm.unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {
        }
    }

    // ───────────── اجرای تست کامل ─────────────
    private fun add(section: String) {
        log.append(section).append("\n\n")
        tvLog.text = log.toString()
    }

    private fun runTest() {
        if (running) return
        running = true
        btnStart.isEnabled = false
        progress.visibility = View.VISIBLE
        log.setLength(0)
        tvLog.text = ""
        tvStatus.text = "⏳ در حال تست… (حدود ۳۰ ثانیه)"

        lifecycleScope.launch {
            var severity = 0
            val issues = ArrayList<String>()
            fun issue(sev: Int, msg: String) {
                if (sev > severity) severity = sev
                issues.add((if (sev >= 2) "❌ " else "⚠️ ") + msg)
            }

            var info: NetInfo? = null
            var pingText = "-"
            var lossText = "-"
            var speedText = "-"

            try {
                // ۱) اطلاعات اتصال
                val ni = withContext(Dispatchers.IO) { readNetInfo() }
                info = ni
                val sb = StringBuilder("📶 اتصال\nنوع: ${ni.type}")
                if (ni.ssid.isNotEmpty()) sb.append("\nشبکه: ${ni.ssid}")
                ni.rssi?.let { sb.append("\nقدرت سیگنال: $it dBm (${rssiLabel(it)})") }
                ni.freq?.let { sb.append("\nفرکانس: $it MHz، کانال ${channel(it)}") }
                ni.link?.let { sb.append("\nسرعت لینک Wi-Fi: $it Mbps") }
                ni.gateway?.let { sb.append("\nGateway: $it") }
                add(sb.toString())

                if (!ni.connected) {
                    issue(2, "به هیچ شبکه‌ای وصل نیستید.")
                    return@launch
                }
                ni.rssi?.let {
                    if (it < -80) issue(2, "سیگنال Wi-Fi خیلی ضعیف است ($it dBm). به مودم نزدیک‌تر شوید.")
                    else if (it < -70) issue(1, "سیگنال Wi-Fi ضعیف است ($it dBm).")
                }

                // ۲) پینگ / Jitter / Packet Loss (موازی)
                val gwDef = ni.gateway?.let { gw -> async { ping(gw, 80, 8) } }
                val tDefs = TARGETS.map { t -> async { ping(t.host, t.port, 8) } }
                val gwRes = gwDef?.await()
                val inet = tDefs.map { it.await() }

                val ps = StringBuilder("🏓 پینگ، Jitter و Packet Loss")
                gwRes?.let { ps.append("\n").append(fmtPing("روتر (Gateway)", it)) }
                TARGETS.forEachIndexed { i, t -> ps.append("\n").append(fmtPing(t.name, inet[i])) }
                add(ps.toString())

                val inetOk = inet.any { it.received > 0 }
                if (gwRes != null) {
                    if (gwRes.received == 0 && !inetOk) {
                        issue(2, "نه روتر جواب می‌دهد نه اینترنت؛ مشکل از Wi-Fi یا خود روتر است.")
                    } else if (gwRes.received > 0 && !inetOk) {
                        issue(2, "روتر در دسترس است ولی اینترنت نیست؛ مشکل از لینک اینترنت (WAN/ISP) است.")
                    } else if (gwRes.received > 0 && (gwRes.avg > 20 || gwRes.loss >= 5)) {
                        issue(1, "تاخیر/افت بین گوشی و روتر بالاست (${f0(gwRes.avg)}ms)؛ مشکل از Wi-Fi داخلی (سیگنال یا تداخل) است.")
                    }
                } else if (!inetOk) {
                    issue(2, "هیچ‌کدام از هدف‌ها پینگ نشدند؛ اینترنت قطع است یا مسدود شده.")
                }
                TARGETS.forEachIndexed { i, t ->
                    val r = inet[i]
                    if (r.received > 0) {
                        if (r.loss >= 5) issue(1, "افت بسته (Packet Loss) به ${t.name}: ${f0(r.loss)}٪")
                        if (r.avg > 150) issue(1, "تاخیر بالا به ${t.name}: ${f0(r.avg)}ms")
                        if (r.jitter > 30) issue(1, "نوسان تاخیر (Jitter) بالا به ${t.name}: ${f0(r.jitter)}ms")
                    }
                }
                val okRes = inet.filter { it.received > 0 }
                if (okRes.isNotEmpty()) pingText = f0(okRes.map { it.avg }.average()) + "ms"
                lossText = f0(inet.map { it.loss }.average()) + "%"

                // ۳) تست DNS
                val rows = DNS_DOMAINS.map { d -> async { dnsRow(d) } }.map { it.await() }
                val ds = StringBuilder("🔎 تست DNS (سیستم / 8.8.8.8 / 1.1.1.1)")
                DNS_DOMAINS.forEachIndexed { i, d ->
                    val (s, g, c) = rows[i]
                    ds.append("\n$d: ${fmtDns(s)} / ${fmtDns(g)} / ${fmtDns(c)}")
                }
                add(ds.toString())
                fun bad(v: Long?) = v == null || v < 0
                val (s0, g0, c0) = rows[0]
                if (bad(s0) && (!bad(g0) || !bad(c0))) {
                    issue(2, "DNS پیش‌فرض این شبکه جواب نمی‌دهد ولی DNS گوگل/کلودفلر کار می‌کند؛ DNS ارائه‌دهنده مشکل دارد (می‌توانید DNS را روی 8.8.8.8 یا 1.1.1.1 بگذارید).")
                } else if (bad(s0) && bad(g0) && bad(c0)) {
                    if (inetOk) issue(2, "هیچ DNS ای جواب نمی‌دهد؛ احتمالاً پورت 53 مسدود است.")
                } else if (s0 != null && s0 > 300) {
                    issue(1, "پاسخ DNS کند است (${s0}ms).")
                }

                // ۴) دسترسی سایت‌ها + Captive Portal
                val capDef = async(Dispatchers.IO) { httpCheck(CAPTIVE_URL, false) }
                val siteDefs = SITES.map { s -> async(Dispatchers.IO) { httpCheck(s.url, true) } }
                val cap = capDef.await()
                val siteRes = siteDefs.map { it.await() }

                val hs = StringBuilder("🌐 دسترسی سایت‌ها")
                SITES.forEachIndexed { i, s ->
                    val r = siteRes[i]
                    val kind = if (s.internal) "داخلی" else "بین‌المللی"
                    hs.append("\n${s.name} ($kind): ")
                    if (r.ok) hs.append("✅ ${r.code} (${r.ms}ms)") else hs.append("❌ ${r.err ?: "خطا"}")
                }
                add(hs.toString())

                val cc = cap.code
                val captivePortal = cc != null && cc != 204 && cc in 200..399
                if (captivePortal) {
                    issue(2, "این شبکه صفحه‌ی ورود (Captive Portal / هات‌اسپات) دارد و تا لاگین نکنید اینترنت نمی‌دهد.")
                } else {
                    val intlIdx = SITES.indices.filter { !SITES[it].internal }
                    val locIdx = SITES.indices.filter { SITES[it].internal }
                    val intlOk = intlIdx.count { siteRes[it].ok }
                    val locOk = locIdx.count { siteRes[it].ok }
                    if (intlOk == 0 && locOk > 0) {
                        issue(2, "اینترنت بین‌المللی قطع یا فیلتر است؛ فقط سایت‌های داخلی باز می‌شوند.")
                    } else if (intlOk > 0 && locOk == 0) {
                        issue(1, "سایت‌های داخلی باز نمی‌شوند (مشکل DNS داخلی یا مسدودی).")
                    } else if (intlOk == 0 && locOk == 0 && inetOk) {
                        issue(2, "پینگ هست ولی هیچ سایتی باز نمی‌شود؛ احتمال مشکل DNS، فیلتر یا پورتال.")
                    }
                    siteRes.forEachIndexed { i, r ->
                        if (r.ok && r.ms > 3000) issue(1, "بازشدن ${SITES[i].name} کند است (${r.ms}ms).")
                    }
                }

                // ۵) سرعت دانلود
                val reachable = inetOk || siteRes.any { it.ok }
                if (reachable && !captivePortal) {
                    tvStatus.text = "⏳ در حال تست سرعت دانلود…"
                    var mbps: Double? = null
                    for (u in SPEED_URLS) {
                        mbps = withContext(Dispatchers.IO) { speedTest(u) }
                        if (mbps != null) break
                    }
                    if (mbps != null) {
                        speedText = f1(mbps) + " Mbps"
                        add("⬇️ سرعت دانلود: $speedText")
                        if (mbps < 1.0) issue(2, "سرعت دانلود بسیار پایین است (${f1(mbps)} Mbps).")
                        else if (mbps < 5.0) issue(1, "سرعت دانلود پایین است (${f1(mbps)} Mbps).")
                    } else {
                        speedText = "ناموفق"
                        add("⬇️ سرعت دانلود: تست انجام نشد (سرور تست در دسترس نیست)")
                        issue(1, "تست سرعت انجام نشد؛ سرورهای تست مسدود یا در دسترس نیستند.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                issues.add("خطای غیرمنتظره: ${e.message}")
            } finally {
                val verdict = when (severity) {
                    0 -> "✅ کیفیت اینترنت خوب است"
                    1 -> "⚠️ مشکلات جزئی وجود دارد"
                    else -> "❌ مشکل جدی وجود دارد"
                }
                tvStatus.text = verdict + if (issues.isEmpty()) "" else "\n\n" + issues.joinToString("\n\n")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val net = (info?.type ?: "-") + (info?.ssid?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                saveHistory("$time | $net | ping $pingText | loss $lossText | speed $speedText | ${verdict.take(2)}")
                progress.visibility = View.GONE
                btnStart.isEnabled = true
                running = false
            }
        }
    }

    // ───────────── اطلاعات شبکه ─────────────
    @Suppress("DEPRECATION")
    private fun readNetInfo(): NetInfo {
        val none = NetInfo(false, "بدون اتصال", "", null, null, null, null)
        val net = cm.activeNetwork ?: return none
        val caps = cm.getNetworkCapabilities(net) ?: return none
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "موبایل"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "نامشخص"
        }
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return NetInfo(true, type, "", null, null, null, null)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wi = wm.connectionInfo
        val ssid = wi?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() } ?: ""
        val g = wm.dhcpInfo?.gateway ?: 0
        val gw = if (g != 0) String.format(
            Locale.US, "%d.%d.%d.%d",
            g and 0xff, (g shr 8) and 0xff, (g shr 16) and 0xff, (g shr 24) and 0xff
        ) else null
        return NetInfo(
            true, type, ssid,
            wi?.rssi?.takeIf { it > -127 },
            wi?.frequency?.takeIf { it > 0 },
            wi?.linkSpeed?.takeIf { it > 0 },
            gw
        )
    }

    private fun rssiLabel(r: Int) = when {
        r >= -55 -> "عالی"
        r >= -67 -> "خوب"
        r >= -75 -> "متوسط"
        r >= -85 -> "ضعیف"
        else -> "خیلی ضعیف"
    }

    private fun channel(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5170..5895 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    // ───────────── پینگ ─────────────
    private suspend fun ping(host: String, tcpPort: Int, count: Int): PingResult =
        withContext(Dispatchers.IO) {
            val icmp = icmpPing(host, count)
            if (icmp != null && icmp.received > 0) return@withContext icmp
            val tcp = tcpPing(host, tcpPort, count)
            if (tcp.received > 0) {
                tcp.copy(method = if (icmp != null) "TCP، ICMP مسدود است" else "TCP")
            } else {
                icmp ?: tcp
            }
        }

    private fun icmpPing(host: String, count: Int): PingResult? {
        return try {
            val p = ProcessBuilder(
                "/system/bin/ping", "-c", "$count", "-W", "2", "-w", "${count + 6}", host
            ).redirectErrorStream(true).start()
            val times = ArrayList<Double>()
            val re = Regex("time=([0-9.]+)")
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l -> re.find(l)?.let { times.add(it.groupValues[1].toDouble()) } }
            }
            p.waitFor()
            stats(host, count, times, "ICMP")
        } catch (e: Exception) {
            null
        }
    }

    private fun tcpPing(host: String, port: Int, count: Int): PingResult {
        val times = ArrayList<Double>()
        repeat(count) {
            try {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(host, port), 2000)
                    times.add((System.nanoTime() - t0) / 1e6)
                }
            } catch (_: Exception) {
            }
            Thread.sleep(200)
        }
        return stats(host, count, times, "TCP")
    }

    private fun stats(host: String, sent: Int, t: List<Double>, method: String): PingResult {
        if (t.isEmpty()) return PingResult(host, sent, 0, 0.0, 0.0, 0.0, 0.0, method)
        var j = 0.0
        for (i in 1 until t.size) j += abs(t[i] - t[i - 1])
        j = if (t.size > 1) j / (t.size - 1) else 0.0
        return PingResult(host, sent, t.size, t.average(), t.min(), t.max(), j, method)
    }

    private fun fmtPing(name: String, r: PingResult): String =
        if (r.received == 0) "$name: ❌ بدون پاسخ"
        else "$name: ${f0(r.avg)}ms | jitter ${f0(r.jitter)} | loss ${f0(r.loss)}% (${r.method})"

    // ───────────── DNS ─────────────
    private suspend fun dnsRow(d: String): Triple<Long?, Long?, Long?> = coroutineScope {
        val s = async { sysResolve(d) }
        val g = async(Dispatchers.IO) { dnsQuery("8.8.8.8", d) }
        val c = async(Dispatchers.IO) { dnsQuery("1.1.1.1", d) }
        Triple(s.await(), g.await(), c.await())
    }

    // null = timeout ، -1 = پاسخ نامعتبر ، غیر از اون‌ها = میلی‌ثانیه
    private suspend fun sysResolve(domain: String): Long? {
        val d = lifecycleScope.async(Dispatchers.IO) {
            try {
                val t0 = System.nanoTime()
                InetAddress.getByName(domain)
                (System.nanoTime() - t0) / 1_000_000
            } catch (e: Exception) {
                -1L
            }
        }
        return withTimeoutOrNull(4000) { d.await() }
    }

    private fun dnsQuery(server: String, domain: String): Long? {
        return try {
            val id = Random().nextInt(0xFFFF)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeShort(id)
            dos.writeShort(0x0100) // recursion desired
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (part in domain.split(".")) {
                dos.writeByte(part.length)
                dos.writeBytes(part)
            }
            dos.writeByte(0)
            dos.writeShort(1) // type A
            dos.writeShort(1) // class IN
            val data = baos.toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = 2500
                val t0 = System.nanoTime()
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(512)
                s.receive(DatagramPacket(buf, buf.size))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val rid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val rcode = buf[3].toInt() and 0x0F
                val an = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                if (rid == id && rcode == 0 && an > 0) ms else -1L
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fmtDns(v: Long?): String = when {
        v == null -> "timeout"
        v < 0 -> "نامعتبر"
        else -> "${v}ms"
    }

    // ───────────── HTTP و سرعت ─────────────
    private fun httpCheck(url: String, follow: Boolean): HttpRes {
        val t0 = System.nanoTime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = follow
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            c.disconnect()
            HttpRes(code, (System.nanoTime() - t0) / 1_000_000, null)
        } catch (e: Exception) {
            HttpRes(null, (System.nanoTime() - t0) / 1_000_000, e.javaClass.simpleName)
        }
    }

    private fun speedTest(url: String, maxMs: Long = 8000): Double? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                val t0 = System.nanoTime()
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if ((System.nanoTime() - t0) / 1_000_000 > maxMs) break
                }
                val sec = (System.nanoTime() - t0) / 1e9
                if (total < 100_000 || sec <= 0) null else total * 8 / sec / 1e6
            }
        } catch (e: Exception) {
            null
        }
    }

    // ───────────── تاریخچه ─────────────
    private fun saveHistory(line: String) {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val old = sp.getString(KEY_HISTORY, "") ?: ""
        val lines = (listOf(line) + old.split("\n").filter { it.isNotBlank() }).take(40)
        sp.edit().putString(KEY_HISTORY, lines.joinToString("\n")).apply()
    }

    private fun showHistory() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val text = sp.getString(KEY_HISTORY, "")?.takeIf { it.isNotBlank() } ?: "هنوز تستی انجام نشده."
        AlertDialog.Builder(this)
            .setTitle("تاریخچه‌ی تست‌ها")
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .setNeutralButton("پاک کردن") { _, _ -> sp.edit().remove(KEY_HISTORY).apply() }
            .show()
    }

    private fun f0(d: Double) = String.format(Locale.US, "%.0f", d)
    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
}
