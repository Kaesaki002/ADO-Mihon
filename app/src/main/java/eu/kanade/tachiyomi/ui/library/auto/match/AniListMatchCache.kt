package eu.kanade.tachiyomi.ui.library.auto.match

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val ANILIST_CACHE_VERSION = 2

@Serializable
data class AniListMetadataCacheEntry(
    val version: Int,
    val candidate: AniListMediaCandidate? = null,
    val timestamp: Long,
)

@Serializable
data class MangaMatchCacheEntry(
    val version: Int,
    val mangaId: Long,
    val normalizedTitle: String,
    val normalizedAuthors: String,
    val trackerRemoteId: Long? = null,
    val status: MatchStatus,
    val reason: MatchReason,
    val score: Double,
    val anilistId: Long? = null,
    val timestamp: Long,
)

/**
 * Android Context bağımsız, File dizini tabanlı güvenli disk önbelleği.
 * Temp dosya + ATOMIC_MOVE ile bozulma koruması sağlar.
 */
class AniListMatchCache(
    val baseDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val metadataDir = File(baseDir, "metadata")
    private val matchesDir = File(baseDir, "matches")

    private val mutex = Mutex()

    init {
        baseDir.mkdirs()
        metadataDir.mkdirs()
        matchesDir.mkdirs()
    }

    suspend fun getMetadata(anilistId: Long): AniListMediaCandidate? = withContext(Dispatchers.IO) {
        val file = File(metadataDir, "$anilistId.json")
        if (!file.exists()) return@withContext null

        mutex.withLock {
            try {
                val jsonText = file.readText(Charsets.UTF_8)
                val entry = AniListJsonParser.decodeFromString<AniListMetadataCacheEntry>(jsonText)

                if (entry.version != ANILIST_CACHE_VERSION) {
                    file.delete()
                    return@withLock null
                }

                val now = clock()
                if (entry.candidate == null) {
                    // 30 günlük negatif kayıt
                    val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
                    if ((now - entry.timestamp) > thirtyDaysMs) {
                        file.delete()
                    }
                    return@withLock null
                }

                val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
                if ((now - entry.timestamp) > ninetyDaysMs) {
                    file.delete()
                    return@withLock null
                }

                entry.candidate
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Bozuk dosya durumunda dosyayı temizle ve yok say
                file.delete()
                null
            }
        }
    }

    suspend fun isCachedOrNegativelyCached(anilistId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = File(metadataDir, "$anilistId.json")
        if (!file.exists()) return@withContext false

        mutex.withLock {
            try {
                val jsonText = file.readText(Charsets.UTF_8)
                val entry = AniListJsonParser.decodeFromString<AniListMetadataCacheEntry>(jsonText)

                if (entry.version != ANILIST_CACHE_VERSION) {
                    file.delete()
                    return@withLock false
                }

                val now = clock()
                if (entry.candidate == null) {
                    val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
                    if ((now - entry.timestamp) > thirtyDaysMs) {
                        file.delete()
                        return@withLock false
                    }
                    return@withLock true
                } else {
                    val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
                    if ((now - entry.timestamp) > ninetyDaysMs) {
                        file.delete()
                        return@withLock false
                    }
                    return@withLock true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                file.delete()
                false
            }
        }
    }

    suspend fun isNegativeMetadataCache(anilistId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = File(metadataDir, "$anilistId.json")
        if (!file.exists()) return@withContext false

        mutex.withLock {
            try {
                val jsonText = file.readText(Charsets.UTF_8)
                val entry = AniListJsonParser.decodeFromString<AniListMetadataCacheEntry>(jsonText)

                if (entry.version != ANILIST_CACHE_VERSION) {
                    file.delete()
                    return@withLock false
                }

                val now = clock()
                if (entry.candidate == null) {
                    val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
                    if ((now - entry.timestamp) > thirtyDaysMs) {
                        file.delete()
                        return@withLock false
                    }
                    return@withLock true
                }
                false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                file.delete()
                false
            }
        }
    }

    suspend fun saveNotFoundMetadata(anilistId: Long) = withContext(Dispatchers.IO) {
        val file = File(metadataDir, "$anilistId.json")
        val entry = AniListMetadataCacheEntry(
            version = ANILIST_CACHE_VERSION,
            candidate = null,
            timestamp = clock(),
        )
        val jsonText = AniListJsonParser.encodeToString(entry)

        mutex.withLock {
            writeAtomic(file, jsonText)
        }
    }

    suspend fun saveMetadata(candidate: AniListMediaCandidate) = withContext(Dispatchers.IO) {
        val file = File(metadataDir, "${candidate.id}.json")
        val entry = AniListMetadataCacheEntry(
            version = ANILIST_CACHE_VERSION,
            candidate = candidate,
            timestamp = clock(),
        )
        val jsonText = AniListJsonParser.encodeToString(entry)

        mutex.withLock {
            writeAtomic(file, jsonText)
        }
    }

    suspend fun getMatch(
        mangaId: Long,
        currentTitle: String,
        currentAuthors: List<String>,
        currentTrackerRemoteId: Long?,
    ): MangaMatchCacheEntry? = withContext(Dispatchers.IO) {
        val file = File(matchesDir, "$mangaId.json")
        if (!file.exists()) return@withContext null

        mutex.withLock {
            try {
                val jsonText = file.readText(Charsets.UTF_8)
                val entry = AniListJsonParser.decodeFromString<MangaMatchCacheEntry>(jsonText)

                val now = clock()
                val normTitle = StringSimilarity.normalizeTitle(currentTitle)
                val normAuthors = normalizeAuthorsForCache(currentAuthors)

                // 1. Sürüm kontrolü
                if (entry.version != ANILIST_CACHE_VERSION) {
                    file.delete()
                    return@withLock null
                }

                // 2. Tracker remote_id değişmişse geçersiz
                if (entry.trackerRemoteId != currentTrackerRemoteId) {
                    file.delete()
                    return@withLock null
                }

                // 3. Başlık veya yazar değişmişse geçersiz
                if (entry.normalizedTitle != normTitle || entry.normalizedAuthors != normAuthors) {
                    file.delete()
                    return@withLock null
                }

                // 4. MATCHED kalıcıdır; AMBIGUOUS / NOT_MATCHED 30 gün geçerlidir
                if (entry.status == MatchStatus.MATCHED) {
                    return@withLock entry
                }

                val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
                if ((now - entry.timestamp) <= thirtyDaysMs) {
                    return@withLock entry
                } else {
                    file.delete()
                    return@withLock null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                file.delete()
                null
            }
        }
    }

    suspend fun saveMatch(
        mangaId: Long,
        title: String,
        authors: List<String>,
        trackerRemoteId: Long?,
        status: MatchStatus,
        reason: MatchReason,
        score: Double,
        anilistId: Long?,
    ) = withContext(Dispatchers.IO) {
        val file = File(matchesDir, "$mangaId.json")
        val normTitle = StringSimilarity.normalizeTitle(title)
        val normAuthors = normalizeAuthorsForCache(authors)

        mutex.withLock {
            // Yalnızca kayıt yoksa ya da içeriği (status, reason, anilistId vb.) değişmişse diske yaz
            if (file.exists()) {
                try {
                    val existing = AniListJsonParser.decodeFromString<MangaMatchCacheEntry>(file.readText(Charsets.UTF_8))
                    if (existing.version == ANILIST_CACHE_VERSION &&
                        existing.status == status &&
                        existing.reason == reason &&
                        existing.anilistId == anilistId &&
                        existing.trackerRemoteId == trackerRemoteId &&
                        existing.normalizedTitle == normTitle &&
                        existing.normalizedAuthors == normAuthors
                    ) {
                        return@withLock
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            val entry = MangaMatchCacheEntry(
                version = ANILIST_CACHE_VERSION,
                mangaId = mangaId,
                normalizedTitle = normTitle,
                normalizedAuthors = normAuthors,
                trackerRemoteId = trackerRemoteId,
                status = status,
                reason = reason,
                score = score,
                anilistId = anilistId,
                timestamp = clock(),
            )
            val jsonText = AniListJsonParser.encodeToString(entry)
            writeAtomic(file, jsonText)
        }
    }

    private fun writeAtomic(targetFile: File, content: String) {
        targetFile.parentFile?.mkdirs()
        // File.createTempFile prefix en az 3 karakter olmalıdır
        val tempFile = File.createTempFile(
            "cache_${targetFile.nameWithoutExtension}_",
            ".tmp",
            targetFile.parentFile,
        )

        try {
            tempFile.writeText(content, Charsets.UTF_8)
            val sourcePath = tempFile.toPath()
            val targetPath = targetFile.toPath()

            try {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            tempFile.delete()
            if (e is CancellationException) throw e
            throw e
        }
    }

    companion object {
        fun normalizeAuthorsForCache(authors: List<String>): String {
            return authors.map { StringSimilarity.normalizeTitle(it) }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(";")
        }
    }
}
