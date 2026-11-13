package ee.local.go3tvplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ee.local.go3tvplus.catalog.CatalogSection

internal object NavigationStyle {
    val Background = Color(0xFF090D17)
    val Muted = Color(0xFFA7B0C1)
    val Accent = Color(0xFF8FE7F4)
    val Shape = RoundedCornerShape(10.dp)
}

@Composable
internal fun NavigationRail(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(180.dp).fillMaxHeight().background(NavigationStyle.Background.copy(alpha = .95f))
            .padding(start = 20.dp, end = 16.dp, top = 35.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("GO3 AIR", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("SINU HETK. SINU VALIK.", color = NavigationStyle.Muted, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(27.dp))
        content()
    }
}

/** The rail shows the current section; LEFT/BACK returns control to the main menu. */
@Composable
internal fun SettingsFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize().background(NavigationStyle.Background)) {
        NavigationRail {
            CatalogSection.entries.forEach { section ->
                val selected = section == CatalogSection.SETTINGS
                Box(Modifier.fillMaxWidth().background(if (selected) Color.White.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(40.dp))
                    .padding(horizontal = 15.dp, vertical = 10.dp)) {
                    Text(section.label, color = if (selected) Color.White else NavigationStyle.Muted, fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Column(modifier.weight(1f).fillMaxHeight().padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
