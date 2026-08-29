# Turvalisus ja privaatsus

- Juurdepääsu- ja refresh-tokenid krüpteeritakse AES/GCM võtmega, mis luuakse Android Keystore'is.
- Rakendus ei küsi ega salvesta Go3 parooli.
- Kõik võrgud peavad kasutama HTTPS-i ja ainult süsteemi usaldatud sertifikaate; cleartext ning kasutaja lisatud CA-d on keelatud.
- HTTP body-loggingut ei kasutata. Mängijale antavad manifesti- ja litsentsipäised elavad ainult mälus.
- HAR, `.jks`, `.keystore`, `secrets.properties` ja `captures/` on Git-ist välistatud.
- `npaw.*` väärtused on mõõteteenuse seansiandmed, mitte autentimine, ning rakendus ei kasuta neid.
- Seadmepiiri, striimilimiidi, geobloki ja paketiõiguse vead kuvatakse kasutajale, kuid serveri toorvastust ega tokenit mitte.
