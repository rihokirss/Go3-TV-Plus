# Go3 Air 0.5.2

Otsing uuendab tulemusi sisestamise ajal pärast 300 ms kirjutamispausi. Go3 serveriotsing ja kohalik vahemälu töötavad paralleelselt; tulemuste nägemiseks ei pea kogu kataloogi laadimist ootama. Aegunud päringud tühistatakse ning võrguvea korral jäävad kohalikud vasted alles.

Kohaliku otsingu aeglustanud andmebaasipäring on parandatud. TCL-is vähenes mõõdetud tüüpiline päringuaeg umbes 3,85 sekundilt 16 millisekundile. Viimases kontrollis jõudsid serveri ja vahemälu ühendatud tulemused kohale umbes 372 ms-ga, millele lisandub 300 ms kirjutamispaus; võrgu tegelik kiirus võib erineda.

Puldiga sirvimine on sujuvam:

- Peamenüüst paremale liikudes taastub valitud plakat või filter ilma vahepealse fookusehüppeta.
- „Vaata kõiki” avab fookuse plakatiruudustikus; BACK taastab sama rea nupu.
- Filmi detailvaatest BACK naaseb samale plakatile ja taastab kerimiskoha nii filmireas kui ka ruudustikus.
- Valiku valge kontuur on tumeda eraldusjoonega nähtav ka valgel plakatil.

Plakatid laaditakse ekraanile sobivas mõõdus ning järgmised kaks plakatit laetakse vaikselt ette. Nähtavad pildid ja eellaadimine kasutavad ühist mälu- ja kettavahemälu. Väikese videopuhvri korral ootab eellaadimine.

Versioonikood on **55**, et uuendus oleks saadaval ka 0.5.2-preview testversioonilt. Paketinimi ja allkiri säilitavad uuendusühilduvuse. Konto, vaatamisjärg, kanaliseaded ning TCL-i taasesituse parandus säilivad.

Kontrollitud TCL-is; [kontrolliaruanne](https://github.com/rihokirss/Go3-TV-Plus/blob/v0.5.2/docs/VALIDATION_0.5.2.md).
