package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceCode
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class DemoGo3Gateway : Go3Gateway {
    private var pollCount = 0

    override suspend fun requestDeviceCode(): DeviceCode {
        delay(350)
        pollCount = 0
        return DeviceCode(
            code = "123456",
            verificationUrl = "https://go3.ee/subscriber/connect-tv",
            qrPayload = "https://go3.ee/subscriber/connect-tv?code=123456",
            expiresAt = Instant.now().plusSeconds(600),
            pollIntervalSeconds = 1,
        )
    }

    override suspend fun pollDeviceCode(deviceCode: String): AuthTokens? {
        delay(250)
        pollCount += 1
        return if (pollCount >= 2) AuthTokens("demo-access") else null
    }

    override suspend fun profiles(accessToken: String) = listOf(
        Profile("main", "Minu profiil", false),
        Profile("kids", "Lapsed", true),
    )

    override suspend fun channels(accessToken: String, profileId: String): List<Channel> =
        demoNames.mapIndexed { index, name -> Channel("demo-$index", name, serverNumber = index + 1) }

    override suspend fun programs(
        accessToken: String,
        profileId: String,
        from: Instant,
        until: Instant,
    ): List<Program> {
        val zone = ZoneId.of("Europe/Tallinn")
        val first = ZonedDateTime.ofInstant(from, zone).truncatedTo(ChronoUnit.HOURS).minusHours(1)
        val result = mutableListOf<Program>()
        demoNames.indices.forEach { channelIndex ->
            var cursor = first.plusMinutes((channelIndex * 7).toLong())
            var item = 0
            while (cursor.toInstant().isBefore(until)) {
                val length = if ((item + channelIndex) % 3 == 0) 90L else 60L
                val end = cursor.plusMinutes(length)
                result += Program(
                    id = "demo-$channelIndex-${cursor.toEpochSecond()}",
                    channelId = "demo-$channelIndex",
                    title = demoTitles[(item + channelIndex) % demoTitles.size],
                    description = "Demorežiimi saatekava näidis. Päris andmed tulevad pärast Go3 API lepingu valideerimist.",
                    startsAt = cursor.toInstant(),
                    endsAt = end.toInstant(),
                    catchupAvailable = end.toInstant().isBefore(Instant.now()),
                )
                cursor = end
                item += 1
            }
        }
        return result
    }

    override suspend fun liveTicket(accessToken: String, profileId: String, channelId: String): PlaybackTicket =
        demoTicket(channelId, true)

    override suspend fun catchupTicket(accessToken: String, profileId: String, programId: String): PlaybackTicket =
        demoTicket(programId, false)

    override suspend fun prolongPlayback(accessToken: String, playbackSessionId: String) = Unit

    override suspend fun closePlayback(accessToken: String, playbackSessionId: String) = Unit

    private fun demoTicket(contentId: String, live: Boolean) = PlaybackTicket(
        contentId = contentId,
        manifestUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
        mimeType = "application/x-mpegURL",
        playbackSessionId = "demo-session-$contentId",
        isLive = live,
    )

    private companion object {
        val demoNames = listOf("ETV", "ETV2", "TV3", "Kanal 2", "TV6", "Duo 4", "Discovery", "Eurosport")
        val demoTitles = listOf("Hommikuprogramm", "Uudised", "Dokumentaal", "Mängufilm", "Sport", "Õhtune saade")
    }
}
