package ee.local.go3tvplus.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import ee.local.go3tvplus.catalog.parseCatalogTitle
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Program
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render real controls with stale TV/catch-up metadata; never starts a video session. */
@RunWith(AndroidJUnit4::class)
class SeekOverlayDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cataloguePlaybackOwnsThePanelAndTvMetadataReturnsAfterwards() {
        val now = Instant.now()
        val programme = Program("tv", "channel", "Telekavasaade", "TV tutvustus", now.minusSeconds(300), now.plusSeconds(300), true)
        val previous = programme.copy(id = "catchup", title = "Vana järelvaatamine", startsAt = now.minusSeconds(3600), endsAt = now.minusSeconds(300))
        val movie = parseCatalogTitle(Json.parseToJsonElement("""{"id":"movie","type":"VOD","title":"Kataloogifilm","lead":"Filmi tutvustus","year":2025,"rating":12,"duration":5400}""").jsonObject)!!
        val episode = parseCatalogTitle(Json.parseToJsonElement("""{"id":"episode","type":"EPISODE","title":"Seriaali osa","lead":"Osa tutvustus","episode":4,"duration":3600}""").jsonObject)!!
        val tv = TvUiState(currentChannelId = "channel", channels = listOf(Channel("channel", "TV kanal", serverNumber = 1)),
            programsByChannel = mapOf("channel" to listOf(programme)), seek = SeekState(600_000, 5_400_000, isLive = true))
        val current = mutableStateOf(tv.copy(vodItem = movie, catchupProgram = previous, seek = tv.seek.copy(isLive = false)))
        compose.setContent { MaterialTheme { SeekOverlay(current.value) } }
        compose.onNodeWithText("Kataloogifilm").assertIsDisplayed()
        compose.onNodeWithText("FILM").assertIsDisplayed()
        compose.onNodeWithText("Filmi tutvustus").assertIsDisplayed()
        compose.onNodeWithText("2025  •  12+  •  90 min").assertIsDisplayed()
        compose.onNodeWithText("10:00").assertIsDisplayed()
        compose.onNodeWithText("1:30:00").assertIsDisplayed()
        assertNoTvMetadata()

        compose.runOnIdle { current.value = current.value.copy(vodItem = episode, seek = current.value.seek.copy(durationMs = 3_600_000)) }
        compose.onNodeWithText("Seriaali osa").assertIsDisplayed()
        compose.onNodeWithText("SERIAAL  •  4. osa").assertIsDisplayed()
        compose.onNodeWithText("Osa tutvustus").assertIsDisplayed()
        compose.onNodeWithText("Kataloogifilm").assertDoesNotExist()
        assertNoTvMetadata()

        // A title with no optional metadata still must not fall back to TV.
        compose.runOnIdle { current.value = current.value.copy(vodItem = episode.copy(description = null, durationSeconds = null, year = null, age = null)) }
        compose.onNodeWithText("Seriaali osa").assertIsDisplayed()
        compose.onNodeWithText("Osa tutvustus").assertDoesNotExist()
        assertNoTvMetadata()

        compose.runOnIdle { current.value = tv }
        compose.onNodeWithText("Telekavasaade").assertIsDisplayed()
        compose.onNodeWithText("1  TV kanal").assertIsDisplayed()
        compose.onAllNodesWithTag("seek-program-boundary").assertCountEquals(1)
        compose.runOnIdle { current.value = tv.copy(catchupProgram = previous, seek = tv.seek.copy(isLive = false)) }
        compose.onNodeWithText("Vana järelvaatamine").assertIsDisplayed()
        compose.onNodeWithText("Telekavasaade").assertDoesNotExist()
    }

    private fun assertNoTvMetadata() {
        compose.onNodeWithText("Telekavasaade").assertDoesNotExist()
        compose.onNodeWithText("TV kanal", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Vana järelvaatamine").assertDoesNotExist()
        compose.onNodeWithText("OTSE", substring = true).assertDoesNotExist()
        compose.onAllNodesWithTag("seek-program-boundary").assertCountEquals(0)
    }
}
