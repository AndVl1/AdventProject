package ru.andvl.assistant.tools.docs

import org.jsoup.Jsoup
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

data class StoredDoc(
    val name: String,
    val mime: String,
    val text: String,
)

@Service
class DocStoreService {

    private val baseDir = "demo-docs"
    private val allowed = setOf("html", "md", "txt")

    fun list(): List<String> {
        val resource = ClassPathResource(baseDir).file
        return runCatching {
            resource.listFiles()?.map { it.name }?.sorted().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun read(name: String): StoredDoc? {
        val safe = name.substringAfterLast('/')
        val ext = safe.substringAfterLast('.', "").lowercase()
        if (ext !in allowed) return null

        val res = ClassPathResource("$baseDir/$safe")
        if (!res.exists()) return null

        val raw = res.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val text = when (ext) {
            "html" -> Jsoup.parse(raw).body().text()
            else -> raw
        }
        val mime = when (ext) {
            "html" -> "text/html"
            "md" -> "text/markdown"
            else -> "text/plain"
        }
        return StoredDoc(name = safe, mime = mime, text = text)
    }
}
