# Go3 TV+

Privaatne Android TV 9+ klient, mille põhivaade on alati mängiv telekanal. Rakendus sisaldab täisekraani mängijat, läbipaistvat telekava, kiiret kanaliriba, numbriklahvide tuge, viimase kanali taastamist, profiilivalikut ning QR/seadmekoodiga autentimise olekumasinat.

## Praegune seis

Rakendus kasutab kasutaja enda HAR-i ja Android TV kliendi põhjal kinnitatud Go3 seadmesidumist, kanaliloendit, EPG kataloogi, playback-session'it ning ametlikku Widevine'i litsentsivoogu. QR-sidumine, profiili meeldejätmine, live-video ja seitsme päeva EPG on kontrollitud füüsilistel Android TV seadmetel.

Go3 API ei ole avalik ja võib muutuda. Teenusespetsiifiline leping on koondatud `Go3HttpGateway` adapterisse; DRM-i, sertifikaadikaitset ega APK kontrolli ei murta.

## Puldinupud

| Nupp | Toiming |
|---|---|
| `CH+ / CH−` | Vaheta kanal kohe ja näita külgriba |
| `↑ / ↓` | Ava kanaliriba ja vali kanal |
| `→` | Ava läbipaistev EPG; EPG-s liigu järgmise saate juurde |
| `←` | Ava rakenduse seaded; EPG-s liigu eelmise saate juurde |
| `OK` | Kinnita valik; puhtas mängijavaates ava ajariba |
| Hoia EPG-s `OK` | Lülita kõikide kanalite ja lemmikute filter ümber |
| `GUIDE / MENU` | Ava EPG, kui puldil vastav nupp siiski leidub |
| `0–9` | Sisesta 1–3-kohaline kanalinumber; häälestus 2 sekundi järel |
| `BACK` | Sulge esmalt aktiivne kiht, siis rakendus |
| `HOME` | Peata heli ja video ning sulge Go3 serveriseanss |

## Ehitamine

Vaja on JDK 17 ja Android SDK 37.

```bash
./gradlew testDebugUnitTest packageSideloadApk
```

Minifitseeritud sideload-APK tekib asukohta:

```text
dist/Go3-TV-Plus-<versioon>.apk
```

Debug-APK:

```bash
./gradlew assembleDebug
```

Release kasutab hetkel masina Android debug-keystore'i, et samal teleril saaks APK-d uuendada. Võtit ei hoita repos. Avalikuks levitamiseks tuleb luua eraldi release-keystore ja hankida Go3 ametlik integratsiooniluba.

## Arhitektuur

- `domain/Go3Gateway.kt` — ainus teenusespetsiifiline liides UI ja andmekihi jaoks.
- `data/AuthCoordinator.kt` — QR-koodi küsimine, pollimine, aegumine ja tokeni uuendamine.
- `data/local/` — Roomi EPG/kanalivahemälu, DataStore'i eelistused ja Android Keystore'iga krüpteeritud tokenid.
- `player/TvPlayer.kt` — Media3 ExoPlayer, MediaSession ja Widevine/PlayReady/ClearKey konfiguratsioon.
- `ui/TvViewModel.kt` — kanali-, EPG-, numbrisisestuse ja puldi olekumasin.
- `ui/Go3TvApp.kt` — 10-foot UI, läbipaistvad overlay'd ja profiili-/sidumisvaated.

Rakendus ei logi HTTP kehi, bearer-token'e ega DRM-päiseid. HAR-id, võtmed ja `secrets.properties` on `.gitignore`-is.
