# Go3 Air

Mitteametlik Android TV klient kiireks ja puldisõbralikuks Go3 otse-TV vaatamiseks. Rakendus avab viimase kanali, hoiab video telekava all mängimas ning teeb kanalivahetuse võimalikuks nii D-padi, `CH+/CH−` kui ka numbriklahvidega.

> [!IMPORTANT]
> See on sõltumatu kogukonnaprojekt, mitte Go3 ametlik rakendus. Go3 nimi ja teenus kuuluvad nende omanikele. Rakendus eeldab kasutaja enda kehtivat Go3 tellimust ning kasutab Go3 tavapärast seadmesidumist ja ametlikku DRM-litsentsivoogu. DRM-i ega teenuse piiranguid ei eemaldata.

## Põhivõimalused

- viimati vaadatud kanal avaneb automaatselt;
- läbipaistev ja tihe EPG töötab mängiva video peal;
- otse-TV ja Go3 pakutav järelvaatamine;
- kiire kanaliriba, lemmikud ning muudetavad kanalinumbrid;
- kanalivahetus `CH+/CH−`, D-padi ja numbriklahvidega;
- ajanihe, 10-sekundiline kerimine ja saatevahetuste markerid ajaribal;
- saate meeldetuletused ja automaatne kanalile lülitumine värvinuppudega;
- kanaliteülene heli- ja subtiitrikeele eelistus;
- Go3 vaatamisprofiilid ning turvaline QR-/seadmekoodiga sidumine;
- Android MediaSessioni tugi, et telefon ja süsteem näitaksid kanalit ning saadet;
- täisekraanil avatav ilmateade hetke-, tunni- ja nelja päeva prognoosiga;
- punase nupuga avatav „Täna õhtul” vaade lemmikkanalite saadete, meeldetuletuste ja automaatlülitustega;
- rohelise nupuga avatavad valitud peatuse järgmised ühistranspordi väljumised;
- serveripoolse vaatamisseansi korrektne sulgemine kanali vahetamisel, rakendusest väljumisel ja unerežiimis.

## Nõuded

- Android TV või Google TV, Android 9 või uuem;
- aktiivne Go3 tellimus;
- teleris lubatud tundmatutest allikatest paigaldamine;
- soovituslikult vähemalt 10 Mbps internetiühendus.

Samsung Tizeni ja LG webOS-i jaoks see APK ei sobi. Need platvormid vajavad eraldi rakendust.

## APK paigaldamine

### 1. Laadi APK alla

Ava GitHubi lehel [Releases](https://github.com/rihokirss/Go3-TV-Plus/releases/latest) ja laadi alla `Go3-Air-<versioon>.apk`.

### 2. Paigalda mälupulgalt

Järgmised sammud eeldavad, et teleris on juba rakendus, millega saab mälupulgal olevaid faile sirvida.

1. Kopeeri allalaaditud `Go3-Air-<versioon>.apk` arvutis mälupulga juurkausta, et seda oleks teleris lihtne leida.
2. Eemalda mälupulk arvutist turvaliselt ja ühenda see teleri USB-pessa.
3. Ava teleris failibrauser ning vali USB-mäluseade.
4. Leia kopeeritud APK ja vajuta puldil `OK`.
5. Kui Android paigaldamise blokeerib, vali kuvatud `Seaded` ning luba sellel failibrauseril tundmatuid rakendusi installida.
6. Mine failibrauserisse tagasi, ava APK uuesti ja vali `Installi`.
7. Pärast paigaldamist vali `Ava` või käivita **Go3 Air** teleri rakenduste nimekirjast.

FAT32-vormingus mälupulk töötab Android TV seadmetega üldjuhul kõige kindlamalt. Kui teler APK-d ei näita, vali failibrauseris kõigi failide kuvamine või proovi teist USB-pesa.

Google TV-s asub vastav luba tavaliselt menüüs:

```text
Seaded → Rakendused → Erijuurdepääs → Tundmatute rakenduste installimine
```

Menüü täpne nimi sõltub teleri tootjast ja Androidi versioonist. Pärast paigaldamist võib tundmatute rakenduste loa soovi korral uuesti välja lülitada.

### Alternatiiv: paigaldamine ADB-ga

Luba teleris arendaja valikud ja Wireless debugging, ühenda arvuti teleriga ning käivita:

```bash
adb connect TELERI_IP:ADB_PORT
adb install -r Go3-Air-0.4.33.apk
```

`-r` uuendab olemasolevat rakendust ja jätab sidumise ning seaded alles. Kui Android kuvab `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, on sama paketiga rakendus allkirjastatud teise võtmega. Vana rakenduse eemaldamine lahendab konflikti, kuid kustutab selle kohalikud seaded ja konto sidumise.

Tehniline paketitunnus on ajaloolise uuendusühilduvuse tõttu `ee.local.go3tvplus.debug`; avaldatud APK ise ei ole debuggable.

### 3. Seo Go3 konto

1. Ava teleris Go3 Air.
2. Skanni kuvatav QR-kood telefoniga või sisesta ekraanil olev seadmekood.
3. Kinnita seade oma Go3 kontol.
4. Kui kontol on mitu Go3 profiili, vali soovitud vaatamisprofiil.

Parooli rakendusse ei sisestata ega salvestata. Tokenid hoitakse Android Keystore'iga kaitstult.

## Puldinupud

| Nupp | Toiming |
|---|---|
| `CH+ / CH−` | Vaheta kanal kohe ja näita kanaliriba |
| `↑ / ↓` | Ava kanaliriba ja vali kanal |
| `→` | Ava EPG; EPG-s liigu järgmise saate juurde |
| `←` | Ava rakenduse seaded; EPG-s liigu eelmise saate juurde |
| `OK` | Kinnita valik; mängijavaates ava ajariba |
| Ajaribal `↑` | Alusta vaadatavat saadet algusest, kui järelvaatamine on saadaval |
| Hoia EPG-s `OK` | Vaheta kõigi kanalite ja lemmikute filtrit |
| EPG-s `punane / roheline` | Eelmine / järgmine päev |
| EPG-s `kollane` | Lisa või eemalda saate meeldetuletus |
| EPG-s `sinine` | Lisa või eemalda automaatne kanalile lülitumine |
| Täisekraanil `sinine` | Lülita kell sisse või välja; valik jäetakse meelde |
| Täisekraanil `kollane` | Ava ilmateade; `kollane` või `BACK` sulgeb selle |
| Täisekraanil `punane` | Ava „Täna õhtul” saatekava; `punane` või `BACK` sulgeb selle |
| Täisekraanil `roheline` | Ava ühistranspordi väljumised; `roheline` või `BACK` sulgeb selle |
| `GUIDE / MENU` | Ava EPG, kui puldil vastav nupp leidub |
| `1–9`, seejärel `0–9` | Sisesta 1–3-kohaline kanalinumber; häälestus pärast sisestuspausi |
| `0` eraldi | Vaheta eelmisele kanalile; korduv vajutus lülitab kahe viimase kanali vahel |
| `BACK` | Sulge esmalt aktiivne kiht, seejärel rakendus |
| `HOME` | Peata taasesitus ja sulge Go3 serveriseanss |

Ilmateate vaikimisi asukoht on Suurupi. Seda saab muuta menüüst
`Seaded → Ilm`; asukoha otsing ja prognoos kasutavad Open-Meteo teenust.

## TCL-i Prime Video ja Sony Netflixi nupp

Repos on eraldi valikuline, teleri tootja ära tundev `tclredirect` helper. See
suunab testitud TCL Google TV Prime Video nupu või Sony BRAVIA Netflixi nupu
Go3 Airi rakendusse ning töötab ka pärast teleri täielikku restarti. TCL-i
lahendus eeldab, et võrgu-ADB jääb teleris lubatuks; Sony lahendus kasutab
püsivat ligipääsetavuse võtmefiltrit ega vaja pärast seadistamist võrgu-ADB-d.
Helper ei kuulu põhi-APK-sse, sest lahendus sõltub konkreetsest telerist ja
puldist.

Paigaldamine, taastamine, turvapiirangud ja tehniline taust on kirjas
[nupusuunaja märkmetes](docs/tcl-prime-button-redirect.md).

## Uuendamine

Laadi uuem APK Releases-lehelt ja paigalda see vana peale. Rakendus asendatakse ning konto sidumine, lemmikud, kanalinumbrid ja muud eelistused jäävad alles, kui paketitunnus ja allkiri ühtivad.

Rakendusel pole veel automaatset uuendajat ega Play Store'i versiooni.

## Teadaolevad piirangud

- Go3 API ei ole avalik ning teenuse muudatus võib ajutiselt sidumise, EPG või taasesituse katki teha.
- Näha ja mängida saab ainult kasutaja Go3 paketis olevaid kanaleid ning saateid.
- Go3 seadme- ja samaaegsete striimide piirangud kehtivad ka selles rakenduses.
- Widevine'i, geoblokki, paketipiiranguid ega muid teenuse kaitseid ei murta.
- Mõne saate järelvaatamine sõltub Go3 õigustest ja sellest, kas teenus annab toimiva catch-up kirje.

## Lähtekoodist ehitamine

Vaja on JDK 17 ja Android SDK 37.

```bash
./gradlew testDebugUnitTest packageSideloadApk
```

Minifitseeritud sideload-APK tekib asukohta:

```text
dist/Go3-Air-<versioon>.apk
```

Avaldatud versiooni allkirjavõtit repos ei hoita. Teise võtmega ehitatud APK ei saa GitHub Release'ist paigaldatud rakendust kohapeal uuendada.

## Arhitektuur ja turvalisus

- `domain/Go3Gateway.kt` — teenusespetsiifiline liides UI ja andmekihi vahel;
- `data/AuthCoordinator.kt` — QR-kood, pollimine, aegumine ja tokeni uuendamine;
- `data/local/` — Roomi vahemälu, DataStore'i eelistused ja Android Keystore'iga kaitstud tokenid;
- `player/TvPlayer.kt` — Media3 ExoPlayer, MediaSession ja ametlik DRM-voog;
- `ui/TvViewModel.kt` — EPG, kanalite, puldi ja taasesituse olek;
- `ui/Go3TvApp.kt` — Android TV kasutajaliides.

Rakendus ei logi HTTP kehi, bearer-token'e ega DRM-päiseid. HAR-id, võtmed, APK-d ja lokaalsed captures-failid on Gitist välistatud. Täpsem info: [turvalisus](docs/SECURITY.md).

## Vastutuse piirang

Projekt on eksperimentaalne ja mõeldud isiklikuks kasutuseks. Kasutaja vastutab selle eest, et rakenduse kasutamine vastaks tema Go3 lepingu tingimustele ja kohalikele õigusaktidele.
