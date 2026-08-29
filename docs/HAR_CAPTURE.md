# Go3 API lepingu valideerimine

Päris Go3 adapterit ei tohi ehitada oletuste, `npaw.*` telemeetria ega käsitsi kopeeritud pika elueaga tokenite põhjal. Vajalik on kasutaja enda aktiivse tellimuse lubatud liikluse lokaalne vaatlus.

## 1. Brauseri HAR

1. Ava Chrome'is `https://go3.ee/live_tv` ja logi sisse.
2. Ava Developer Tools → Network, lülita sisse **Preserve log** ja puhasta varasemad kirjed.
3. Laadi live-TV leht uuesti.
4. Ava EPG, vaheta vähemalt kahe kanali vahel ning käivita üks varasem järelvaadatav saade.
5. Ekspordi **Save all as HAR with content** ja salvesta fail repos ignoreeritud kausta `captures/`.
6. Ära lisa HAR-i Git-i ega kleebi seda vestlusse: see võib sisaldada küpsiseid ja bearer-token'e.

HAR-ist tuleb dokumenteerida ainult leping, mitte saladused:

- API hostid ja endpoint'ide rajad;
- HTTP meetodid, sisutüübid ja kohustuslike päiste **nimed**;
- seadme aktiveerimise koodi, pollimise, tokeni uuendamise ja profiilide wire-formaat;
- kanalite, õiguste ja EPG wire-formaat ning ajatemplite ajavöönd;
- live- ja catch-up pileti manifesti, DRM URL-i, litsentsipäiste ning playback-session ID väljad;
- seansi sulgemise/heartbeat'i päring ja 401/403/409/429 veakoodide tähendus.

## 2. Android TV aktiveerimine

Kontrolli ametlikus Go3 TV äpis QR-sidumist ja kuuekohalist koodi. Lubatud on ADB logcat ning enda seadme kohaliku proksi kaudu nähtav liiklus, kui rakendus kasutab süsteemi usaldusahelat. Sertifikaadipinningut, APK allkirjakontrolli ega riistvaralist attestatsiooni ei lülitata välja.

Vajalikud kinnitused:

- kes loob QR payload'i ja kas see erineb käsitsi sisestatava koodi URL-ist;
- pollimise intervall, aegumine ja OTC002-taoline uue koodi nõue;
- kas token on seotud Androidi seadme ID, Widevine'i turvataseme või ametliku paketi allkirjaga;
- millal seade Go3 viie seadme nimekirja lisatakse ja eemaldatakse.

## 3. Adapteri lisamine

1. Lisa `Go3Gateway` implementatsioon eraldi faili, kasutades OkHttp'd ilma body-logging interceptorita.
2. Hoia kõik wire-DTO-d adapteri paketis ja teisenda need kohe domeenimudeliteks.
3. Koonda endpoint'id ühte lepinguklassi; ära paiguta URL-e ViewModelisse või UI-sse.
4. Kaardista Go3 vead `Go3Failure` tüüpideks ning redigeeri testisalvestistest tokenid, küpsised, DRM challenge'id ja isikuandmed.
5. Lisa MockWebServeri kontraktitestid puhastatud JSON fixture'itega.
6. Vaheta `TvApplication` tootmisrežiimis `UnconfiguredGo3Gateway` valideeritud adapteri vastu ja ehita käsuga `./gradlew -PdemoMode=false assembleRelease`.

## Stop-tingimus

Kui litsentsiserver nõuab ametliku Go3 APK allkirja või attestatsiooni, mida erarakendus ei saa tavapärase Android MediaDrm API kaudu esitada, lõpetatakse tootmisintegratsioon tehnilise raportiga. DRM-i dekrüpteerimist, võtmete eksporti ega pinningu murdmist ei rakendata.

