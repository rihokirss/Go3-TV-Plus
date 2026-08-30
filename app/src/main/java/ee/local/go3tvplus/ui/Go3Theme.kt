package ee.local.go3tvplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal object Go3Colors {
    // Bränd
    val Accent = Color(0xFF178BFF)
    val Cyan = Color(0xFF32C7FF)
    val Favorite = Color(0xFFFFD65C)

    // Puldi värviklahvid (legend + toimingumarkerid)
    val KeyRed = Color(0xFFFF5C5C)
    val KeyGreen = Color(0xFF59E391)
    val KeyYellow = Favorite
    val KeyBlue = Color(0xFF55B4FF)

    // Baastoonid
    val DeepBlue = Color(0xFF071221)
    val MidBlue = Color(0xFF0E2946)
    val AppBackground = Color(0xFF050B14)
    val GradientTop = Color(0xFF10365B)

    // Scrimid
    val Scrim = Color(0xB0050B14)
    val ScrimHeavy = Color(0xE6051020)

    // Pinnad
    val PanelDark = Color(0xF2071221)
    val SoftPanel = Color(0xE80B1B30)
    val NoticeSurface = Color(0xF0193655)
    val NowBadge = Color(0xF019567C)
    val StatusChip = Color(0xFF173C60)
    val InfoBar = Color(0xE5122740)
    val ErrorSurface = Color(0xEE651F28)

    // Read ja chipid
    val RowIdle = Color(0xD1132942)
    val ChipIdle = Color(0xE617304B)
    val SelectedRow = Color(0xD9193C67)
    val GuideRowTint = Color(0x3D142A42)

    // Telekava ajatelg
    val TimelineBackground = Color(0xB5091829)
    val ProgramCell = Color(0xE31D3550)
    val ProgramCellLive = Color(0xEB164E78)
    val CellHighlightBorder = Color(0xFFDDF4FF)
    val GridLine = Color(0xCC050E1B)
    val InkShadow = Color(0xCC06121F)

    // Tekst
    val TextSecondary = Color(0xFFAAB9C9)
    val TextHint = Color(0xFF9DB2C7)
    val TextFaint = Color(0xFF71879E)
    val ErrorText = Color(0xFFFFA0A5)
    val ProgressTrack = Color(0xFF29445E)
}

internal object Go3Radii {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 18.dp
    val XL = 24.dp
}

internal object Go3Brushes {
    val fullscreenRadial = Brush.radialGradient(listOf(Go3Colors.GradientTop, Go3Colors.AppBackground))
    val settingsPanel = Brush.horizontalGradient(
        listOf(Go3Colors.DeepBlue.copy(alpha = 0.99f), Go3Colors.MidBlue.copy(alpha = 0.95f)),
    )
    val seekPanel = Brush.verticalGradient(
        listOf(Go3Colors.MidBlue.copy(alpha = 0.93f), Go3Colors.DeepBlue.copy(alpha = 0.95f)),
    )
    val guideSheet = Brush.verticalGradient(
        listOf(Go3Colors.MidBlue.copy(alpha = 0.72f), Go3Colors.DeepBlue.copy(alpha = 0.77f)),
    )
    val railPanel = Brush.horizontalGradient(listOf(Go3Colors.PanelDark, Color.Transparent))
    val menuCard = Brush.verticalGradient(
        listOf(Go3Colors.MidBlue.copy(alpha = 0.97f), Go3Colors.DeepBlue.copy(alpha = 0.98f)),
    )
    val progressFill = Brush.horizontalGradient(listOf(Go3Colors.Accent, Go3Colors.Cyan))
}

@Composable
internal fun OverlayHeader(
    kicker: String,
    title: String? = null,
    hint: String? = null,
    keyHints: List<Pair<String, String>>? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kicker, color = Go3Colors.Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (title != null) Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, color = Go3Colors.TextHint, fontSize = 14.sp)
        if (keyHints != null) KeyHintRow(*keyHints.toTypedArray())
    }
}

@Composable
internal fun KeyCap(label: String) {
    Text(
        label,
        modifier = Modifier
            .background(Go3Colors.ChipIdle, RoundedCornerShape(Go3Radii.XS))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(Go3Radii.XS))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun KeyHintRow(
    vararg hints: Pair<String, String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (key, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                KeyCap(key)
                Text(label, color = Go3Colors.TextHint, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun SettingsRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .background(if (selected) Go3Colors.Accent else Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 18.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowBadge(text: String, selected: Boolean) {
    Text(
        text,
        color = if (selected) Color.White else Go3Colors.Cyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun CenteredMenuPanel(
    modifier: Modifier = Modifier,
    width: Dp = 680.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Go3Colors.ScrimHeavy), contentAlignment = Alignment.Center) {
        Column(
            modifier.width(width)
                .clip(RoundedCornerShape(Go3Radii.XL))
                .background(Go3Brushes.menuCard)
                .border(1.dp, Go3Colors.Cyan.copy(alpha = 0.18f), RoundedCornerShape(Go3Radii.XL))
                .padding(horizontal = 36.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
