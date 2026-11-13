# README ekraanipildid

Go3 Air **0.5.0** Sony BRAVIA Android TV-s ja **0.5.1** TCL Google TV-s. Kataloogipildid on tegelikud rakenduse ekraanipildid; neis pole kasutajaliidest ega Go3 kataloogi taustapilte ümber joonistatud. Pildid on README jaoks teisendatud WebP-vormingusse kvaliteediga 90, algset mõõtu säilitades.

| Fail | Vaade | Päritolu |
| --- | --- | --- |
| [filmid.webp](filmid.webp) | Filmide avaleht, kompaktsem tutvustus ja populaarsete filmide rida | TCL, 0.5.1 |
| [filmi-detail.webp](filmi-detail.webp) | Filmi tutvustus ja tegevused | TCL, 0.5.1 |
| [seriaali-osad.webp](seriaali-osad.webp) | Seriaali hooaeg ja episood | Sony ekraanipilt |
| [otsing.webp](otsing.webp) | Ühine otsing ja filtrid | Sony ekraanipilt |
| [telekava-originaal.webp](telekava-originaal.webp) | Telekava, DRM-video salvestusel must | Sony ekraanipilt |
| [telekava-illustratsioon.webp](telekava-illustratsioon.webp) | Telekava illustratsioon looduskaadriga | Algse ekraanipildi töötlus sisseehitatud imagegen-tööriistaga |

## Telekava illustratsioon

DRM-iga kaitstud videokiht jääb Androidi ekraanipildil mustaks. Dokumentatsiooni illustratsioon kasutab selle asemel loodud looduskaadrit. Generatiivne töötlus muutis ka pildi mõõtu ja kasutajaliidese väikeseid detaile; täpne tegelik vaade on eraldi originaalpildil. README tähistab töödeldud pilti illustratsioonina.

Kasutatud oskus: **imagegen**. Genereerimine toimus sisseehitatud tööriistaga.

Lõplik töötlusjuhis (sisendiks algne telekava ekraanipilt):

```text
Use case: compositing. Asset type: documentation screenshot for an Android TV app README. Edit target: the attached real 1920x1080 TV guide screenshot. Replace ONLY the solid black DRM-redacted video region at the top (approximately y=0 through y=193, above the rounded navy TV guide panel) with an attractive photorealistic cinematic original nature documentary frame: a tranquil Estonian coastline at blue hour with forested islands, cool blue water and a warm narrow horizon. It is an illustrative substitute for video, not actual broadcast footage. Preserve the screenshot dimensions, framing and entire application UI from y=194 to bottom EXACTLY, all Estonian text, channel names, grid lines, blue focus borders, times, positions and colors pixel-exact. Do not redraw, restyle, translate or invent ANY interface element. Do not change any UI. No additional text, labels, logos, border, television hardware or watermark. The photographic landscape should fill just the black top strip, not overlap the UI.
```

Filmide ja seriaalide plakatid ning taustapildid pärinevad Go3 kataloogist ja kuuluvad nende õiguste omajatele. Rakendus ja dokumentatsioon ei eemalda DRM-i ega teenuse piiranguid.
