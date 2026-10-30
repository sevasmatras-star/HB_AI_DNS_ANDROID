package ai.hb.smartdns

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("hb_smart_dns", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var providerSpinner: Spinner
    private lateinit var primaryText: TextView
    private lateinit var secondaryText: TextView
    private lateinit var testButton: Button
    private lateinit var autoButton: Button
    private lateinit var aiTestButton: Button
    private lateinit var startButton: Button
    private var selected = Providers.all.first()
    private var busy = false

    private val bg = Color.rgb(12, 15, 27)
    private val panel = Color.rgb(24, 29, 47)
    private val muted = Color.rgb(160, 170, 194)
    private val accent = Color.rgb(125, 108, 255)
    private val green = Color.rgb(80, 210, 157)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        buildUi()
        val saved = prefs.getString("provider", Providers.all.first().id)
        val index = Providers.all.indexOfFirst { it.id == saved }.coerceAtLeast(0)
        providerSpinner.setSelection(index)
        updateStatus()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
        }
        scroll.addView(root)

        val brand = TextView(this).apply {
            text = "HB AI"
            textSize = 30f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(brand)
        root.addView(label("SMART DNS  /  ANDROID EDITION", 11f, accent, true))
        root.addView(space(22))

        val hero = card()
        hero.addView(label("Управление DNS", 20f, Color.WHITE, true))
        hero.addView(space(5))
        hero.addView(label("Выберите профиль и подключите его одним нажатием.", 13f, muted))
        root.addView(hero)
        root.addView(space(16))

        val providerCard = card()
        providerCard.addView(label("DNS-ПРОВАЙДЕР", 11f, muted, true))
        providerCard.addView(space(8))
        providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Providers.all.map { it.title })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selected = Providers.all[position]
                    prefs.edit().putString("provider", selected.id).apply()
                    renderProvider()
                }
            }
        }
        providerCard.addView(providerSpinner)
        root.addView(providerCard)
        root.addView(space(12))

        val dnsCard = card()
        dnsCard.addView(label("АДРЕСА DNS", 11f, muted, true))
        primaryText = label("", 15f, Color.WHITE, true)
        secondaryText = label("", 13f, muted)
        dnsCard.addView(space(8))
        dnsCard.addView(primaryText)
        dnsCard.addView(space(4))
        dnsCard.addView(secondaryText)
        dnsCard.addView(space(8))
        dnsCard.addView(label("Назначение: AI-сервисы и доступность зависят от конкретного DNS-провайдера.", 12f, muted))
        root.addView(dnsCard)
        root.addView(space(16))

        statusText = label("●  Отключено", 16f, muted, true)
        detailsText = label("DNS-профиль не активен.", 12f, muted)
        val statusCard = card()
        statusCard.addView(statusText)
        statusCard.addView(space(5))
        statusCard.addView(detailsText)
        root.addView(statusCard)
        root.addView(space(14))

        startButton = button("ПОДКЛЮЧИТЬ SMART DNS", accent).apply { setOnClickListener { requestVpn() } }
        root.addView(startButton)
        root.addView(space(8))
        val stop = button("ОТКЛЮЧИТЬ И ВЕРНУТЬ ОБЫЧНЫЙ DNS", Color.rgb(53, 61, 83)).apply {
            setOnClickListener {
                stopService(Intent(this@MainActivity, DnsVpnService::class.java))
                prefs.edit().putBoolean("enabled", false).apply()
                statusText.text = "●  Отключено"
                statusText.setTextColor(muted)
                detailsText.text = "Обычный DNS Android восстановлен."
                startButton.isEnabled = true
            }
        }
        root.addView(stop)
        root.addView(space(8))
        testButton = button("ПРОВЕРИТЬ DNS-СЕРВЕР", Color.rgb(35, 104, 91)).apply { setOnClickListener { testDns() } }
        root.addView(testButton)
        root.addView(space(8))
        autoButton = button("АВТОПОДБОР DNS", Color.rgb(75, 82, 145)).apply { setOnClickListener { autoSelectDns() } }
        root.addView(autoButton)
        root.addView(space(8))
        aiTestButton = button("ПРОВЕРИТЬ ОБХОД БЛОКИРОВОК", Color.rgb(35, 104, 91)).apply { setOnClickListener { testAiDomains() } }
        root.addView(aiTestButton)
        root.addView(space(20))

        val info = card()
        info.addView(label("ВАЖНО", 11f, accent, true))
        info.addView(space(7))
        info.addView(label(
            "Android подключает DNS через системное VPN-разрешение. Приложение не перенаправляет обычный интернет-трафик через удалённый VPN-сервер. Отключите «Частный DNS» Android и другие VPN на время проверки.",
            12f, muted
        ))
        info.addView(space(8))
        info.addView(label(
            "Проверка подтверждает ответ DNS-сервера, но не гарантирует доступ к ChatGPT, Claude, Gemini или ElevenLabs. Сторонние DNS-профили могут перестать работать или менять условия.",
            12f, muted
        ))
        info.addView(space(8))
        info.addView(label(
            "Если обычный DNS-пакет не проходит, локальный туннель использует резервный DoH-запрос к Cloudflare или Google. Обычный интернет-трафик через удалённый VPN-сервер не отправляется.",
            12f, muted
        ))
        root.addView(info)
        setContentView(scroll)
        renderProvider()
    }

    private fun renderProvider() {
        if (!::primaryText.isInitialized) return
        primaryText.text = "Основной:  ${selected.primary}"
        secondaryText.text = "Резервный:  ${selected.secondary}"
        if (prefs.getBoolean("enabled", false)) {
            detailsText.text = "Выбран профиль: ${selected.title}. Переподключите, чтобы применить новый."
        }
    }

    private fun requestVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, 410)
        else startDnsService()
    }

    @Deprecated("Deprecated by Android; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 410) {
            if (resultCode == RESULT_OK) startDnsService()
            else {
                statusText.text = "●  Разрешение не выдано"
                statusText.setTextColor(Color.rgb(255, 178, 95))
            }
        }
    }

    private fun startDnsService() {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            putExtra(DnsVpnService.EXTRA_PRIMARY, selected.primary)
            putExtra(DnsVpnService.EXTRA_SECONDARY, selected.secondary)
            putExtra(DnsVpnService.EXTRA_PROVIDER, selected.title)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            prefs.edit().putBoolean("enabled", true).apply()
            statusText.text = "●  Подключение запускается"
            statusText.setTextColor(green)
            detailsText.text = "${selected.title}: ${selected.primary} (резерв: ${selected.secondary})"
        } catch (e: Exception) {
            statusText.text = "●  Не удалось запустить"
            statusText.setTextColor(Color.rgb(255, 178, 95))
            detailsText.text = "Android не разрешил запустить DNS-службу: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
        startButton.isEnabled = true
    }

    private fun testDns() {
        if (busy) return
        busy = true
        testButton.isEnabled = false
        testButton.text = "ПРОВЕРКА..."
        detailsText.text = "Проверяем ответ DNS-сервера ${selected.primary}…"
        val provider = selected
        executor.execute {
            val first = queryDns(provider.primary)
            val second = if (!first) queryDns(provider.secondary) else false
            runOnUiThread {
                busy = false
                testButton.isEnabled = true
                testButton.text = "ПРОВЕРИТЬ DNS-СЕРВЕР"
                when {
                    first -> detailsText.text = "Основной DNS отвечает: ${provider.primary}"
                    second -> detailsText.text = "Основной не ответил; резервный отвечает: ${provider.secondary}"
                    else -> detailsText.text = "Нет ответа от ${provider.primary} и ${provider.secondary}. Проверьте сеть или выберите другой профиль."
                }
            }
        }
    }

    private fun queryDns(server: String, domain: String = "example.com"): Boolean {
        return try {
            val id = (System.nanoTime() and 0xFFFF).toInt()
            val labels = domain.trimEnd('.').split('.')
            val qname = ArrayList<Byte>()
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                if (bytes.isEmpty() || bytes.size > 63) return false
                qname.add(bytes.size.toByte())
                bytes.forEach { qname.add(it) }
            }
            qname.add(0)
            val q = ByteArray(12 + qname.size + 4)
            q[0] = (id ushr 8).toByte(); q[1] = id.toByte()
            q[2] = 1; q[5] = 1
            for (i in qname.indices) q[12 + i] = qname[i]
            val p = 12 + qname.size
            q[p] = 0; q[p + 1] = 1; q[p + 2] = 0; q[p + 3] = 1
            DatagramSocket().use { socket ->
                // Only the synthetic DNS addresses are routed into the TUN interface;
                // direct probes to the upstream resolver IP do not recurse into the tunnel.
                socket.soTimeout = 2500
                socket.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                response.length >= 12 &&
                    buffer[0] == q[0] && buffer[1] == q[1] &&
                    (buffer[2].toInt() and 0x80) != 0
            }
        } catch (_: Exception) { false }
    }

    private fun autoSelectDns() {
        if (busy) return
        busy = true
        autoButton.isEnabled = false
        autoButton.text = "ПОДБИРАЕМ DNS..."
        detailsText.text = "Проверяем основные DNS-серверы профилей. Это может занять несколько секунд."
        executor.execute {
            val candidates = Providers.all.filter { it.id != "xbox" }
            val ranked = candidates.mapNotNull { provider ->
                val started = System.currentTimeMillis()
                val ok = queryDns(provider.primary)
                if (ok) provider to (System.currentTimeMillis() - started) else null
            }.sortedBy { it.second }
            runOnUiThread {
                busy = false
                autoButton.isEnabled = true
                autoButton.text = "АВТОПОДБОР DNS"
                if (ranked.isNotEmpty()) {
                    val best = ranked.first().first
                    selected = best
                    val index = Providers.all.indexOfFirst { it.id == best.id }
                    providerSpinner.setSelection(index)
                    detailsText.text = "Самый быстрый ответ: ${best.title} (${ranked.first().second} мс). Профиль выбран; нажмите «Подключить» для применения."
                } else {
                    detailsText.text = "Не удалось получить ответ ни от одного профиля. Проверьте интернет или попробуйте позже."
                }
            }
        }
    }

    private fun testAiDomains() {
        if (busy) return
        busy = true
        aiTestButton.isEnabled = false
        aiTestButton.text = "ПРОВЕРЯЕМ ДОСТУП..."
        detailsText.text = "Открываем сайты через ${selected.title}. Проверка может занять до минуты."
        executor.execute {
            val domains = listOf(
                "chatgpt.com", "claude.ai", "gemini.google.com", "elevenlabs.io", "grok.com", "perplexity.ai"
            )
            val results = domains.map { it to checkSite(it) }
            runOnUiThread {
                busy = false
                aiTestButton.isEnabled = true
                aiTestButton.text = "ПРОВЕРИТЬ ОБХОД БЛОКИРОВОК"
                val ok = results.filter { it.second.first }.map { "${it.first} (${it.second.second})" }
                val failed = results.filterNot { it.second.first }.map { "${it.first} (${it.second.second})" }
                detailsText.text = buildString {
                    append("Открываются: ")
                    append(if (ok.isEmpty()) "нет" else ok.joinToString())
                    if (failed.isNotEmpty()) append(". Не открываются: ").append(failed.joinToString())
                    append(". Проверка выполнена по реальному HTTPS-ответу сайта.")
                }
            }
        }
    }

    private fun checkSite(domain: String): Pair<Boolean, String> {
        return try {
            val connection = (URL("https://$domain/").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; HB AI Smart DNS)")
            }
            val code = connection.responseCode
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText().take(20000) }
            } catch (_: Exception) { "" }
            connection.disconnect()
            val lower = body.lowercase()
            val blocked = lower.contains("not available in your country") ||
                lower.contains("unavailable in your country") ||
                lower.contains("country restriction") ||
                lower.contains("access denied")
            val captcha = lower.contains("captcha") || lower.contains("recaptcha")
            when {
                blocked -> false to "региональная блокировка"
                captcha -> true to "открыт, но CAPTCHA"
                code in 200..399 -> true to "HTTP $code"
                else -> false to "HTTP $code"
            }
        } catch (e: Exception) {
            false to (e::class.simpleName ?: "ошибка сети")
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(panel, 18)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun button(value: String, color: Int) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        minHeight = dp(52)
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateStatus() {
        val active = DnsVpnService.isRunning
        statusText.text = if (active) "●  Подключение запущено" else "●  Отключено"
        statusText.setTextColor(if (active) green else muted)
        detailsText.text = if (active) "Активный профиль: ${selected.title}." else "DNS-профиль не активен."
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
