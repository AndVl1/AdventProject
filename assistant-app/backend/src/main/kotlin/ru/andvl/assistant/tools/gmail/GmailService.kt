package ru.andvl.assistant.tools.gmail

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.andvl.assistant.config.GmailProperties
import java.util.Base64
import java.util.Properties
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

data class MessageSummary(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
)

data class MessageDetail(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val date: String,
    val body: String,
)

@Service
class GmailService(
    private val props: GmailProperties,
) {

    private val logger = LoggerFactory.getLogger(GmailService::class.java)

    @Volatile
    private var gmail: Gmail? = null

    @PostConstruct
    fun init() {
        if (props.refreshToken.isBlank()) {
            logger.warn("GMAIL_REFRESH_TOKEN не задан — Gmail-инструменты вернут заглушку")
            return
        }
        try {
            val credentials = UserCredentials.newBuilder()
                .setClientId(props.clientId)
                .setClientSecret(props.clientSecret)
                .setRefreshToken(props.refreshToken)
                .build()

            val transport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            gmail = Gmail.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
                .setApplicationName("assistant-app")
                .build()

            logger.info("GmailService инициализирован для ${props.userEmail}")
        } catch (e: Exception) {
            logger.error("Не удалось инициализировать GmailService: ${e.message}", e)
        }
    }

    fun listRecent(limit: Int): List<MessageSummary> {
        val client = gmail ?: return listOf(MessageSummary("", "", "", "Gmail not configured"))

        val userId = props.userEmail.ifBlank { "me" }
        val listResponse = client.users().messages().list(userId)
            .setMaxResults(limit.toLong())
            .execute()

        val messages = listResponse.messages ?: return emptyList()

        return messages.mapNotNull { ref ->
            runCatching {
                val msg = client.users().messages().get(userId, ref.id)
                    .setFormat("metadata")
                    .setMetadataHeaders(listOf("From", "Subject"))
                    .execute()

                val headers = msg.payload?.headers?.associateBy({ it.name }, { it.value }) ?: emptyMap()
                MessageSummary(
                    id = ref.id,
                    from = headers["From"] ?: "",
                    subject = headers["Subject"] ?: "(no subject)",
                    snippet = msg.snippet ?: "",
                )
            }.getOrNull()
        }
    }

    fun getMessage(id: String): MessageDetail? {
        val client = gmail ?: return null
        val userId = props.userEmail.ifBlank { "me" }

        val msg = runCatching {
            client.users().messages().get(userId, id)
                .setFormat("full")
                .execute()
        }.getOrNull() ?: return null

        val headers = msg.payload?.headers?.associateBy({ it.name }, { it.value }) ?: emptyMap()
        val body = extractPlainBody(msg.payload) ?: msg.snippet ?: ""

        return MessageDetail(
            id = id,
            from = headers["From"] ?: "",
            to = headers["To"] ?: "",
            subject = headers["Subject"] ?: "(no subject)",
            date = headers["Date"] ?: "",
            body = body,
        )
    }

    private fun extractPlainBody(payload: com.google.api.services.gmail.model.MessagePart?): String? {
        if (payload == null) return null

        val data = payload.body?.data
        val mime = payload.mimeType.orEmpty()

        if (!data.isNullOrBlank() && mime.startsWith("text/plain")) {
            return decodeBase64Url(data)
        }

        val parts = payload.parts.orEmpty()
        parts.firstNotNullOfOrNull { extractPlainBody(it) }?.let { return it }

        if (!data.isNullOrBlank() && mime.startsWith("text/html")) {
            return decodeBase64Url(data)
        }

        return null
    }

    private fun decodeBase64Url(s: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(s)) }.getOrDefault("")

    fun send(to: String, subject: String, body: String) {
        val client = gmail ?: error("Gmail не настроен")

        val userId = props.userEmail.ifBlank { "me" }
        val session = Session.getDefaultInstance(Properties())
        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(userId))
            addRecipient(jakarta.mail.Message.RecipientType.TO, InternetAddress(to))
            setSubject(subject)
            setText(body)
        }

        val buffer = java.io.ByteArrayOutputStream()
        mime.writeTo(buffer)
        val encodedEmail = Base64.getUrlEncoder().encodeToString(buffer.toByteArray())

        val message = Message().apply { raw = encodedEmail }
        client.users().messages().send(userId, message).execute()
    }
}
