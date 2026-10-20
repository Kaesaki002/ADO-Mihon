package eu.kanade.tachiyomi.ui.library.auto.match

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AniList GraphQL API için coroutine tabanlı hız sınırlayıcı.
 * Varsayılan olarak dakikada en fazla 25 isteğe izin verir.
 *
 * @param permits İzin verilen istek sayısı (varsayılan: 25)
 * @param periodMs İzin periyodu (milisaniye, varsayılan: 60.000 ms = 1 dakika)
 * @param clock Zaman sağlayıcı (test için sahte saat verilebilir)
 * @param delayFn Gecikme fonksiyonu (test için sahte bekleme verilebilir)
 */
class AniListRateLimiter(
    val permits: Int = 25,
    val periodMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        /**
         * Uygulama genelinde paylaşılan tekil (singleton) hız sınırlayıcı.
         */
        val DEFAULT: AniListRateLimiter by lazy { AniListRateLimiter() }
    }

    private val mutex = Mutex()
    private val requestTimestamps = ArrayDeque<Long>()

    /**
     * İzin kotasını kontrol eder, gerekirse bir sonraki periyoda kadar bekler.
     */
    suspend fun acquire() {
        var waitTimeMs = 0L

        mutex.withLock {
            val now = clock()
            val windowStart = now - periodMs

            // Süresi geçmiş eski istek damgalarını temizle
            while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= windowStart) {
                requestTimestamps.removeFirst()
            }

            if (requestTimestamps.size >= permits) {
                val oldestInWindow = requestTimestamps.first()
                waitTimeMs = (oldestInWindow + periodMs) - now
            }
        }

        if (waitTimeMs > 0) {
            delayFn(waitTimeMs)
            // Bekleme sonrası yeniden acquire kontrolü yap
            acquire()
            return
        }

        mutex.withLock {
            requestTimestamps.addLast(clock())
        }
    }

    /**
     * Verilen isteği hız sınırlamasına ve 429 Retry-After kurallarına uygun olarak çalıştırır.
     * En fazla [maxRetries] deneme yapar.
     */
    suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        block: suspend () -> RateLimitResponse<T>,
    ): T {
        var attempts = 0
        while (true) {
            attempts++
            acquire()

            when (val response = block()) {
                is RateLimitResponse.Success -> return response.data
                is RateLimitResponse.RateLimited -> {
                    if (attempts >= maxRetries) {
                        throw AniListRateLimitException("AniList rate limit aşıldı (429), $maxRetries deneme tükendi.")
                    }
                    val retryAfterMs = response.retryAfterSeconds * 1000L
                    delayFn(retryAfterMs)
                }
                is RateLimitResponse.Error -> {
                    throw response.exception
                }
            }
        }
    }
}

sealed class RateLimitResponse<out T> {
    data class Success<T>(val data: T) : RateLimitResponse<T>()
    data class RateLimited(val retryAfterSeconds: Long) : RateLimitResponse<Nothing>()
    data class Error(val exception: Exception) : RateLimitResponse<Nothing>()
}

class AniListRateLimitException(message: String) : Exception(message)
