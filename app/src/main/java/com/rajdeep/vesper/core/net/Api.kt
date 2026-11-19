package com.rajdeep.vesper.core.net

import com.rajdeep.vesper.BuildConfig
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.shared.ApiError
import com.rajdeep.vesper.shared.RefreshRequest
import com.rajdeep.vesper.shared.TokenPair
import com.rajdeep.vesper.shared.VesperJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

/** Server reachability as the UI sees it. Render Free may be cold-starting ("waking"). */
enum class Reach { ONLINE, WAKING, OFFLINE }

@Singleton
class Api @Inject constructor(private val session: SessionManager) {
    val base = BuildConfig.API_URL
    private val json = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val refreshLock = Mutex()
    private val _reach = MutableStateFlow(Reach.ONLINE)
    val reach: StateFlow<Reach> = _reach

    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private suspend fun raw(method: String, path: String, body: RequestBody?, auth: Boolean, tokenOut: Array<String?>? = null): Pair<Int, String> {
        val token = if (auth) session.accessToken() else null
        tokenOut?.set(0, token)
        val req = Request.Builder().url(base + path).method(method, body).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }.build()
        http.newCall(req).await().use { r -> return r.code to (r.body.string()) }
    }

    /**
     * Calls the API with cold-start tolerance: while Render wakes (timeouts / 502 / 503) it retries with backoff
     * for up to ~90 s and reports [Reach.WAKING]. 401 triggers one token refresh.
     */
    private suspend fun call(method: String, path: String, body: RequestBody?, auth: Boolean): String = withContext(Dispatchers.IO) {
        var attempt = 0
        val started = System.currentTimeMillis()
        while (true) {
            val used = arrayOfNulls<String>(1)
            val result = runCatching { raw(method, path, body, auth, used) }
            val (code, text) = result.getOrElse { e ->
                if (System.currentTimeMillis() - started > 90_000) { _reach.value = Reach.OFFLINE; throw e }
                _reach.value = if (attempt == 0) Reach.ONLINE else Reach.WAKING
                attempt++
                delay(minOf(8_000L, 700L * attempt))
                continue
            }
            if (code == 502 || code == 503 || code == 504) {
                _reach.value = Reach.WAKING
                if (System.currentTimeMillis() - started > 90_000) throw ApiException(code, "waking", "The server is waking up. Try again in a moment.")
                attempt++
                delay(minOf(8_000L, 1_000L * attempt))
                continue
            }
            _reach.value = Reach.ONLINE
            if (code == 401 && auth) {
                if (refresh(used[0])) {
                    val (c2, t2) = raw(method, path, body, auth)
                    return@withContext checked(c2, t2)
                }
                session.end("expired")
                throw ApiException(401, "unauthorized", "Please sign in again")
            }
            return@withContext checked(code, text)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private fun checked(code: Int, text: String): String {
        if (code in 200..299) return text
        val err = runCatching { VesperJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
        throw ApiException(code, err?.code ?: "http_$code", err?.message ?: "Something went wrong ($code)")
    }

    /** Rotates tokens; returns false when the session is no longer valid. */
    suspend fun refresh(failedToken: String?): Boolean = refreshLock.withLock {
        val s = session.current() ?: return false
        // Another caller already rotated while we waited.
        if (failedToken != null && s.accessToken != failedToken) return true
        val body = VesperJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(s.refreshToken)).toRequestBody(json)
        val (code, text) = runCatching { raw("POST", "/v1/auth/refresh", body, auth = false) }.getOrElse { return false }
        if (code != 200) return false
        val pair = VesperJson.decodeFromString(TokenPair.serializer(), text)
        session.updateTokens(pair)
        true
    }

    suspend fun <R> get(path: String, out: KSerializer<R>, auth: Boolean = true): R =
        VesperJson.decodeFromString(out, call("GET", path, null, auth))

    suspend fun <B, R> post(path: String, body: B, inS: KSerializer<B>, out: KSerializer<R>, auth: Boolean = true): R =
        VesperJson.decodeFromString(out, call("POST", path, VesperJson.encodeToString(inS, body).toRequestBody(json), auth))

    suspend fun <B, R> put(path: String, body: B, inS: KSerializer<B>, out: KSerializer<R>): R =
        VesperJson.decodeFromString(out, call("PUT", path, VesperJson.encodeToString(inS, body).toRequestBody(json), true))

    suspend fun <R> postEmpty(path: String, out: KSerializer<R>): R =
        VesperJson.decodeFromString(out, call("POST", path, "{}".toRequestBody(json), true))

    suspend fun <R> delete(path: String, out: KSerializer<R>): R =
        VesperJson.decodeFromString(out, call("DELETE", path, null, true))

    /** Raw request to a third-party URL (media provider). No auth headers. */
    suspend fun external(req: Request): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        http.newCall(req).await().use { it.code to it.body.bytes() }
    }
}

inline fun <reified T> ser(): KSerializer<T> = serializer()
