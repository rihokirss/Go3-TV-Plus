package ee.local.go3tvplus.update

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ApkUpdateValidatorTest {
    private val installed = ApkIdentity("ee.local.go3tvplus.debug", 46, "0.4.36", 28, setOf("trusted signer"))
    private val next = installed.copy(versionCode = 47, versionName = "0.4.37")

    @Test fun acceptsNewerApkSignedByTheInstalledAppsKey() {
        validateUpdateIdentity(installed, next, "0.4.37", 28)
    }

    @Test fun rejectsDifferentPackagesSignersAndMissingSignatures() {
        for (bad in listOf(next.copy(packageName = "ee.local.other"), next.copy(signers = setOf("other signer")),
            next.copy(signers = emptySet()), next.copy(signers = setOf("trusted signer", "other signer")))) {
            assertThrows(IOException::class.java) { validateUpdateIdentity(installed, bad, "0.4.37", 28) }
        }
    }

    @Test fun rejectsDowngradesReinstallsMislabelledReleasesAndUnsupportedAndroidVersions() {
        for (bad in listOf(next.copy(versionCode = 45), next.copy(versionCode = 46),
            next.copy(versionName = "0.4.38"), next.copy(minimumSdk = 29))) {
            assertThrows(IOException::class.java) { validateUpdateIdentity(installed, bad, "0.4.37", 28) }
        }
    }
}
