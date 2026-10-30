package ai.hb.smartdns

data class DnsProvider(val id: String, val title: String, val primary: String, val secondary: String, val note: String)

object Providers {
    // Addresses copied from the user-provided upstream project description.
    val all = listOf(
        DnsProvider("comss", "Comss.one", "94.250.253.55", "212.109.195.93", "ChatGPT · Claude · Gemini · ElevenLabs"),
        DnsProvider("geohide", "GeoHide EU", "143.20.64.192", "45.13.237.213", "Профиль GeoHide EU"),
        DnsProvider("magic", "MagicDNS", "109.120.132.43", "94.249.177.102", "ChatGPT · Claude · Gemini · ElevenLabs"),
        DnsProvider("xbox", "Xbox DNS", "111.88.96.54", "111.88.96.55", "Для Xbox Live; не профиль AI-сервисов"),
        DnsProvider("cloudflare", "Cloudflare", "1.1.1.1", "1.0.0.1", "Быстрый публичный DNS; DoH fallback"),
        DnsProvider("google", "Google DNS", "8.8.8.8", "8.8.4.4", "Публичный DNS; DoH fallback"),
        DnsProvider("quad9", "Quad9", "9.9.9.9", "149.112.112.112", "Публичный DNS с фильтрацией угроз")
    )
}
