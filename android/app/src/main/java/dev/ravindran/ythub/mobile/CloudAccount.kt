package dev.ravindran.ythub.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One account service; the local player never depends on this service. */
data class AccountState(val status: String = "Initializing", val userId: String? = null, val email: String = "", val verified: Boolean = false, val message: String = "")
object CloudAccount {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: JsonObject? = null
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    val configured get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "account-session.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("ythub-account-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ythub-account-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun save() {
        if (session == null) { file.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(session.toString().toByteArray())
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val output = file.startWrite()
        try { output.write(payload.toByteArray()); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun restore(): JsonObject? {
        if (!file.baseFile.exists()) return null
        val parts = file.openRead().bufferedReader().use { it.readText() }.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
        return JsonParser.parseString(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))).asJsonObject
    }
    fun init(ctx: Context) {
        context = ctx.applicationContext
        scope.launch {
            if (!configured) { mutable.value = AccountState("Signed out"); return@launch }
            mutex.withLock {
                try {
                    session = restore()
                    if (session == null) mutable.value = AccountState("Signed out")
                    else validate()
                } catch (_: Exception) { session = null; mutable.value = AccountState("Session expired", message = "Secure session could not be restored. Sign in again.") }
            }
        }
        scope.launch { while (isActive) { delay(45_000); if (session != null) runCatching { token() } } }
        CloudSync.init(ctx)
    }
    private suspend fun validate() {
        try {
            refreshIfNeeded()
            val user = request("/auth/v1/user", "GET", null, session!!.get("access_token").asString)
            session!!.add("user", user); session!!.addProperty("validated", true); save()
            publish(user, "Authenticated")
        } catch (e: CloudHttpException) {
            if (e.status == 401 || e.status == 403) { session = null; save(); mutable.value = AccountState("Session expired") }
            else offline()
        } catch (_: java.io.IOException) { offline() }
    }
    private fun offline() {
        val saved = session
        if (saved?.get("validated")?.asBoolean == true) publish(saved.getAsJsonObject("user"), "Offline")
        else mutable.value = AccountState("Session expired", message = "Reconnect to verify your session.")
    }
    private fun publish(user: JsonObject, status: String) {
        mutable.value = AccountState(status, user.get("id").asString, user.get("email")?.asString.orEmpty(), user.get("email_confirmed_at")?.isJsonNull == false)
    }
    private suspend fun refreshIfNeeded() {
        val saved = session ?: error("Sign in first")
        if ((saved.get("expires_at")?.asLong ?: 0) > System.currentTimeMillis()/1000 + 90) return
        val previous = mutable.value
        mutable.value = previous.copy(status = "Refreshing session")
        try {
            val next = request("/auth/v1/token?grant_type=refresh_token", "POST", json("refresh_token" to saved.get("refresh_token").asString))
            next.addProperty("expires_at", System.currentTimeMillis()/1000 + next.get("expires_in").asLong)
            next.addProperty("validated", saved.get("validated")?.asBoolean == true)
            session = next; save()
        } finally { mutable.value = previous }

    }
    suspend fun token(expectedOwner: String? = null): String = withContext(Dispatchers.IO) { mutex.withLock {
        if(expectedOwner != null) check(session?.getAsJsonObject("user")?.get("id")?.asString == expectedOwner) { "Account changed" }
        try { refreshIfNeeded() } catch (e: CloudHttpException) {
            if (e.status in listOf(400,401,403)) { session = null; save(); mutable.value = AccountState("Session expired") }
            throw e
        }
        session?.get("access_token")?.asString ?: error("Sign in first")
    } }
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val previousState = mutable.value
        val previousSession = session
        mutable.value = previousState.copy(status = "Signing in")
        try {
            val result = request("/auth/v1/token?grant_type=password", "POST", json("email" to email.trim(), "password" to password))
            result.addProperty("expires_at", System.currentTimeMillis()/1000 + result.get("expires_in").asLong)
            session = result; validate()
        } catch (_: Exception) { session = previousSession; mutable.value = previousState; error("Unable to sign in. Check your credentials and email verification.") }
    } }
    suspend fun signUp(email: String, password: String, displayName: String) = withContext(Dispatchers.IO) {
        require(password.length >= 12) { "Use at least 12 characters" }
        try { request("/auth/v1/signup", "POST", json("email" to email.trim(), "password" to password).apply { add("data", json("display_name" to displayName.trim().take(100))) }) }
        catch (_: CloudHttpException) { error("Registration could not be completed. Check your details or try recovery.") }
        Unit
    }
    suspend fun sendCode(email: String, recovery: Boolean) = withContext(Dispatchers.IO) {
        try { request(if(recovery) "/auth/v1/recover" else "/auth/v1/resend", "POST", json("email" to email.trim(), "type" to "signup")) }
        catch (e: CloudHttpException) {
            // Keep unknown-account responses identical, but do not claim a code was sent on service failure.
            if (e.status == 429 || e.status >= 500) error("Email could not be sent right now. Please try again later.")
            if (e.status !in listOf(400, 422)) throw e
        }
        Unit
    }
    suspend fun verify(email: String, code: String, recovery: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val result = try { request("/auth/v1/verify", "POST", json("email" to email.trim(), "token" to code.trim(), "type" to if(recovery) "recovery" else "signup")) }
        catch (_: CloudHttpException) { error("Code could not be verified. Request a new code.") }
        result.addProperty("expires_at", System.currentTimeMillis()/1000 + result.get("expires_in").asLong)
        session = result; validate()
        if (recovery && mutable.value.userId != null) mutable.value = mutable.value.copy(status = "Account recovery")
    } }
    suspend fun password(value: String) {
        require(value.length >= 12) { "Use at least 12 characters" }
        val actor = state.value.userId ?: error("Sign in first")
        val result = request("/auth/v1/user", "PUT", json("password" to value), token(actor))
        mutex.withLock { check(session?.getAsJsonObject("user")?.get("id")?.asString == actor) { "Account changed" }; session?.add("user", result); save(); publish(result,"Authenticated") }
    }
    suspend fun forgetDeletedAccount() { mutex.withLock { session=null;save();mutable.value=AccountState("Signed out") } }
    suspend fun signOut(others: Boolean = false) {
        val actor = state.value.userId ?: error("Sign in first")
        val access = token(actor)
        mutex.withLock {
            check(session?.getAsJsonObject("user")?.get("id")?.asString == actor) { "Account changed" }
            request("/auth/v1/logout?scope=${if(others) "others" else "local"}", "POST", null, access)
            if (!others) { session = null; save(); mutable.value = AccountState("Signed out") }
        }
    }
    suspend fun api(path: String, method: String = "GET", body: JsonObject? = null, expectedOwner: String? = null): com.google.gson.JsonElement = raw(path, method, body, token(expectedOwner))
    private suspend fun request(path: String, method: String, body: JsonObject?, token: String? = null): JsonObject = raw(path, method, body, token).let { if(it.isJsonObject) it.asJsonObject else JsonObject() }
    private suspend fun raw(path: String, method: String, body: JsonObject?, token: String?): com.google.gson.JsonElement = withContext(Dispatchers.IO) {
        check(configured) { "Cloud accounts are not configured in this build" }
        val connection = URL(BuildConfig.SUPABASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            if(token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if(body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            if(connection.responseCode !in 200..299) {
                val detail = runCatching { (connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "").let { JsonParser.parseString(it).asJsonObject.get("error")?.asString } }.getOrNull()
                throw CloudHttpException(connection.responseCode, detail)
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if(text.isBlank()) JsonObject() else JsonParser.parseString(text)
        } finally { connection.disconnect() }
    }
}
class CloudHttpException(val status: Int, detail: String? = null): java.io.IOException(detail?.take(240) ?: "Cloud request failed ($status). Retry when connected.")
fun json(vararg entries: Pair<String, String>) = JsonObject().apply { entries.forEach { (key,value) -> addProperty(key,value) } }
