package ee.local.go3tvplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ee.local.go3tvplus.BuildConfig
import ee.local.go3tvplus.update.AppUpdateState
import ee.local.go3tvplus.update.UpdateStage

@Composable
fun AppUpdateOverlay(state: AppUpdateState, onAction: (Int) -> Unit) {
    if (!state.visible) return
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(28.dp).widthIn(max = 720.dp).fillMaxWidth()
                .background(Go3Colors.AppBackground, RoundedCornerShape(18.dp)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("RAKENDUSE UUENDUSED", color = Go3Colors.Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                when (state.stage) {
                    UpdateStage.CHECKING -> "Kontrollin uut versiooni…"
                    UpdateStage.AVAILABLE -> "Go3 Air ${state.release?.version} on saadaval"
                    UpdateStage.DOWNLOADING -> "Laadin uuendust alla… ${state.progress}%"
                    UpdateStage.PERMISSION -> "Luba Go3 Airil uuendusi paigaldada"
                    UpdateStage.READY -> "Uuendus on paigaldamiseks valmis"
                    UpdateStage.INSTALLING -> "Kinnita uuendus Androidi paigaldusaknas"
                    UpdateStage.CURRENT -> "Go3 Air on ajakohane"
                    UpdateStage.ERROR -> "Uuendamine ei õnnestunud"
                },
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                state.message ?: when (state.stage) {
                    UpdateStage.AVAILABLE -> "Praegune versioon ${BuildConfig.VERSION_NAME}. Konto ja seaded jäävad alles. Paigaldamise ajal vaatamine peatub."
                    UpdateStage.PERMISSION -> "Vali Androidi seadetes Go3 Air ja luba sellest allikast paigaldamine. Seejärel tule BACK-nupuga tagasi."
                    UpdateStage.CURRENT -> "Paigaldatud versioon ${BuildConfig.VERSION_NAME}."
                    UpdateStage.DOWNLOADING -> "APK laaditakse alla Go3 Airi GitHubi väljalaskest."
                    else -> "Uuendused Go3 Airi GitHubi väljalasetest."
                },
                color = Go3Colors.TextSecondary, fontSize = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                state.actions.forEachIndexed { index, label ->
                    val selected = index == state.selectedAction
                    Box(
                        Modifier.background(if (selected) Go3Colors.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .border(2.dp, if (selected) Color.White else Go3Colors.TextFaint, RoundedCornerShape(10.dp))
                            .clickable { onAction(index) }.padding(horizontal = 22.dp, vertical = 12.dp),
                    ) { Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text("◀▶ vali   OK kinnita   BACK sulge", color = Go3Colors.TextFaint, fontSize = 13.sp)
        }
    }
}
