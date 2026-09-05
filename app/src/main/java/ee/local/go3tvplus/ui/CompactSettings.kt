package ee.local.go3tvplus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ee.local.go3tvplus.domain.SeaForecastPosition
import java.util.Locale

@Composable
internal fun LanguageSettingsOverlay(state: TvUiState) {
    CenteredMenuPanel {
        OverlayHeader(
            "HELI JA SUBTIITRID",
            hint = "Eelistus kehtib kõigil kanalitel ja järelvaatamisel",
            keyHints = listOf("▲▼" to "vali rida", "◀▶" to "muuda", "BACK" to "tagasi"),
        )
        LanguageSetting.entries.forEachIndexed { index, setting ->
            val audio = setting == LanguageSetting.AUDIO
            CompactSettingRow(
                selected = state.menuIndex == index,
                title = setting.title,
                description = if (audio) "Puuduva keele korral kanali vaikimisi heli" else "Näidatakse siis, kui valitud keel on saadaval",
                value = if (audio) languageLabel(AUDIO_LANGUAGE_OPTIONS, state.audioLanguagePreference)
                    else languageLabel(SUBTITLE_LANGUAGE_OPTIONS, state.subtitleLanguagePreference),
                adjustable = true,
            )
        }
        Text("Muudatus rakendub kohe ja salvestatakse automaatselt.", color = Go3Colors.TextHint, fontSize = 12.sp)
    }
}

@Composable
internal fun LocationsSettingsOverlay(state: TvUiState) {
    CenteredMenuPanel {
        OverlayHeader(
            "ASUKOHAD",
            hint = "Selle teleri ilma-, transpordi- ja mereilma valikud",
            keyHints = listOf("▲▼" to "vali", "OK" to "ava", "BACK" to "tagasi"),
        )
        LocationSetting.entries.forEachIndexed { index, setting ->
            val description = when (setting) {
                LocationSetting.WEATHER -> state.weather.location.let { listOfNotNull(it.name, it.area).joinToString(" • ") }
                LocationSetting.TRANSIT -> "${state.transit.stop.name} • kõik peatuse sõidusuunad"
                LocationSetting.SEA -> state.seaSettings.preferences.let { "${it.first.name} → ${it.second.name}" }
            }
            CompactSettingRow(state.locationsIndex == index, setting.title, description)
        }
    }
}

@Composable
internal fun SeaSettingsOverlay(state: SeaSettingsState) {
    val settings = state.preferences
    CenteredMenuPanel {
        OverlayHeader(
            "MEREILMA PUNKTID",
            hint = "Ilmajaamade mõõtmised ja laineprognoosi asukoht",
            keyHints = listOf("▲▼" to "vali", "OK" to "vali jaam", "◀▶" to "prognoosipunkt", "BACK" to "tagasi"),
        )
        CompactSettingRow(state.menuIndex == 0, "Esimene mõõtepunkt", settings.first.name)
        CompactSettingRow(state.menuIndex == 1, "Teine mõõtepunkt", settings.second.name)
        val forecastLabel = when (settings.forecastPosition) {
            SeaForecastPosition.BETWEEN -> "Punktide vahel"
            SeaForecastPosition.FIRST -> settings.first.name
            SeaForecastPosition.SECOND -> settings.second.name
        }
        CompactSettingRow(
            state.menuIndex == 2, "Prognoosipunkt", "Tuul, nähtavus ja lained selles piirkonnas",
            value = forecastLabel, adjustable = true,
        )
        val route = settings.route()
        Text(
            "Prognoos: ${String.format(Locale.US, "%.3f, %.3f", route.seaLatitude, route.seaLongitude)} • laineandmed lähimast merevõrgu punktist",
            color = Go3Colors.TextHint, fontSize = 12.sp,
        )
        Text("Jaamade mõõteandmed võivad erineda. Vali mereilma jaoks rannikul asuvad jaamad.", color = Go3Colors.TextHint, fontSize = 12.sp)
    }
}

@Composable
internal fun SeaStationPicker(state: SeaSettingsState) {
    val active = if (state.editingSecond) state.preferences.second else state.preferences.first
    CenteredMenuPanel {
        OverlayHeader(
            if (state.editingSecond) "TEINE MÕÕTEPUNKT" else "ESIMENE MÕÕTEPUNKT",
            hint = "Ilmateenistuse jaamad • praegu ${active.name}",
            keyHints = listOf("▲▼" to "vali", "OK" to "kinnita", "BACK" to "tagasi"),
        )
        when {
            state.loading -> Text("Laadin ilmajaamu…", color = Go3Colors.TextSecondary, fontSize = 16.sp)
            state.error != null -> Text(state.error, color = Go3Colors.ErrorText, fontSize = 16.sp)
            else -> {
                // A fixed six-row window makes both directions immediate and always visible.
                val first = (state.stationIndex - 2).coerceIn(0, (state.stations.size - 6).coerceAtLeast(0))
                Column(Modifier.fillMaxWidth().height(288.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.stations.drop(first).take(6).forEachIndexed { offset, station ->
                        val index = first + offset
                        SettingsRow(state.stationIndex == index, verticalPadding = 9.dp) {
                            Text(station.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (station.stationName == active.stationName) RowBadge("PRAEGUNE", state.stationIndex == index)
                        }
                    }
                }
                Text("${state.stationIndex + 1} / ${state.stations.size}", color = Go3Colors.TextFaint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CompactSettingRow(
    selected: Boolean,
    title: String,
    description: String,
    value: String? = null,
    adjustable: Boolean = false,
) {
    SettingsRow(selected, verticalPadding = 10.dp) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = if (selected) Color.White else Go3Colors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(14.dp))
        if (value != null) {
            Text(
                if (adjustable) "‹  $value  ›" else value,
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(max = 235.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else Text("›", color = Color.White, fontSize = 26.sp)
    }
}
