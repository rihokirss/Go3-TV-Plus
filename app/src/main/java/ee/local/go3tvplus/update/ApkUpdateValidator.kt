package ee.local.go3tvplus.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException

/** The release metadata is not enough: trust only a newer APK signed by this app's own key. */
internal fun verifyUpdateApk(context: Context, file: File, release: AppRelease) {
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: throw IOException("Allalaaditud fail ei ole sobiv Androidi rakendus.")
    val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    validateUpdateIdentity(
        installed = ApkIdentity(installed.packageName, installed.longVersionCode, installed.versionName,
            installed.applicationInfo?.minSdkVersion ?: 0,
            installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()),
        candidate = ApkIdentity(archive.packageName, archive.longVersionCode, archive.versionName,
            archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()),
        releaseVersion = release.version,
        deviceSdk = Build.VERSION.SDK_INT,
    )
}

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minimumSdk: Int,
    val signers: Set<String>,
)

internal fun validateUpdateIdentity(installed: ApkIdentity, candidate: ApkIdentity, releaseVersion: String, deviceSdk: Int) {
    if (candidate.packageName != installed.packageName) throw IOException("Uuenduse paketitunnus ei klapi.")
    if (candidate.versionCode <= installed.versionCode || candidate.versionName != releaseVersion) {
        throw IOException("Allalaaditud APK ei ole selle rakenduse uuem versioon.")
    }
    if (candidate.minimumSdk > deviceSdk) {
        throw IOException("See uuendus vajab teleri Androidi uuemat versiooni.")
    }
    if (installed.signers.isEmpty() || installed.signers != candidate.signers) {
        throw IOException("Uuenduse allkiri ei klapi selle rakenduse allkirjaga.")
    }
}
