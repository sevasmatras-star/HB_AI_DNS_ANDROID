package ai.hb.smartdns

data class DnsProvider(val id: String, val title: String, val primary: String, val secondary: String, val note: String)

object Providers {
    // Addresses copied from the user-provided upstream project description.
    val all = listOf(
        DnsProvider("comss", "Comss.one", "94.250.253.55", "212.109.195.93", "ChatGPT · Claude · Gemini · ElevenLabs"),
        DnsProvider("geohide", "GeoHide EU", "143.20.64.192", "45.13.237.213", "Профиль GeoHide EU"),
        DnsProvider("magic", "MagicDNS", "109.120.132.43", "94.249.177.102", "ChatGPT · Claude · Gemini · ElevenLabs"),
        DnsProvider("xbox", "Xbox DNS", "111.88.96.54", "111.88.96.55", "Для Xbox Live; не профиль AI-сервисов")
    )
}
