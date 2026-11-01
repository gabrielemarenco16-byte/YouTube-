package it.shortsgen.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Alcuni servizi (es. Pexels, dietro un filtro anti-bot) rifiutano client non riconosciuti. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ShortsGenerator/1.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) chain.proceed(request)
                else chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }
}

/** Esegue la richiesta in modo cancellabile; lancia [ApiException] se la risposta non è 2xx. */
suspend fun OkHttpClient.executeForBytes(request: Request, provider: String): Pair<ByteArray, String?> {
    val response = await(request)
    return withContext(Dispatchers.IO) {
        response.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw ApiException(provider, r.code, String(bytes, Charsets.UTF_8))
            bytes to r.header("Content-Type")
        }
    }
}

suspend fun OkHttpClient.executeForString(request: Request, provider: String): String =
    String(executeForBytes(request, provider).first, Charsets.UTF_8)

private suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
