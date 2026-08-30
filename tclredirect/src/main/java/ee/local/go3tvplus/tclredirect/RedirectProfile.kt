package ee.local.go3tvplus.tclredirect

import android.os.Build

data class RedirectProfile(
    val deviceName: String,
    val sourceButtonName: String,
    val rawKeyCode: String,
    val symbolicKeyCode: String?,
    val competingPackage: String,
    val usesTclAutoStart: Boolean,
) {
    val title: String
        get() = "$deviceName $sourceButtonName-nupu suunaja"

    val description: String
        get() = "Abirakendus avab $sourceButtonName nupuga Go3 TV+ ja taastab suunamise pärast teleri täielikku restarti."

    companion object {
        fun current(): RedirectProfile {
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            return if (manufacturer.contains("sony")) {
                RedirectProfile(
                    deviceName = "Sony",
                    sourceButtonName = "Netflixi",
                    rawKeyCode = "0247",
                    symbolicKeyCode = "BTN_4",
                    competingPackage = "com.netflix.ninja",
                    usesTclAutoStart = false,
                )
            } else {
                RedirectProfile(
                    deviceName = "TCL",
                    sourceButtonName = "Prime Video",
                    rawKeyCode = "02f0",
                    symbolicKeyCode = null,
                    competingPackage = "com.tcl.partnercustomizer",
                    usesTclAutoStart = true,
                )
            }
        }
    }
}
