package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Otomatik güncelleme denetimi devre dışı bırakıldı
        return GetApplicationRelease.Result.NoNewUpdate
    }
}

val GITHUB_REPO: String by lazy {
    if (isNightlyBuildType) {
        "mihonapp/mihon-preview"
    } else {
        "mihonapp/mihon"
    }
}

val RELEASE_TAG: String by lazy {
    if (isNightlyBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
