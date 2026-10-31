package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Scarica un file (video o immagine) su disco, con un limite di dimensione. */
internal suspend fun OkHttpClient.downloadTo(url: String, target: File, provider: String, maxBytes: Long = 80L * 1024 * 1024) =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(provider, response.code, response.body?.string().orEmpty())
            val body = response.body ?: throw IOException("$provider: download vuoto")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            var total = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("$provider: file troppo grande")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw IOException("$provider: file scaricato vuoto")
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
