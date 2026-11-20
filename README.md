# Checker

Aplicație Android (Kotlin + Jetpack Compose) pentru administrarea aplicațiilor
instalate pe telefon. Interfața este integral în română.

## Ce face

- **Scanează** toate pachetele instalate și le împarte în **sistem** și
  **utilizator**.
- Arată **ce rulează**: procesele aparținând fiecărui pachet (prin UID), memoria
  ocupată (RSS) și dacă aplicația este în **prim-plan** sau în **fundal**.
- **Sortează după memorie** — în tab-urile Rulează / Fundal / Oprire sigură
  lista e ordonată descrescător după RSS, cu totalul de memorie afișat sus.
- **Consum în timp real**: un card sus arată CPU și RAM ca două grafice care se
  mișcă la 2 secunde, plus swap, load average și numărul de nuclee. Fiecare
  aplicație primește procentul ei de CPU, iar filtrul **Consum CPU** arată doar
  ce trece de 1%. Eșantionarea merge numai cât timp ecranul e vizibil.
- **Context de sistem**: numele SoC-ului, cea mai caldă zonă termică (text
  chihlimbar peste 50 °C, roșu peste 60 °C, cu avertismentul că telefonul
  frânează termic) și plafonul de frecvență al clusterelor. Sarcina GPU apare
  doar unde nucleul permite citirea din uid `shell` — pe Android 16 SELinux
  închide `/sys/class/kgsl`, deci rândul lipsește.
- **Fire de execuție** pentru fiecare aplicație, în fișă; cardul arată și cât a
  durat citirea, când trece de 1,5 s.
- **Paznic CPU în fundal**: un comutator pornește un serviciu în prim-plan care
  citește consumul la 45 s și anunță aplicațiile ce trec de 25% dintr-un nucleu
  două eșantioane la rând (90 s), deși nu sunt pe ecran. Lista albă e respectată,
  procesele de sistem sunt ignorate, iar același pachet nu e anunțat mai des de
  20 de minute. Nu oprește nimic singur: anunță și atât.
- **Debloat**: baza de date UAD spune, pentru fiecare pachet preinstalat, dacă  poate fi eliminat (recomandat / avansat / experți / risc) și de ce. Filtrul
  **Debloat** le arată pe cele instalate acum, ordonate după verdict, iar fișa
  aplicației și dialogul de dezinstalare afișează avertismentul potrivit.
- Spune **ce se poate opri în siguranță**: fiecare aplicație primește un verdict
  (oprire sigură / atenție / nu opri) și motivul lui, iar filtrul
  **Oprire sigură** arată doar aplicațiile care pot fi oprite fără efecte.
- **Îngheață** (`pm disable-user --user 0`) și **dezgheață** (`pm enable`)
  aplicații.
- **Oprește mai multe deodată**: apăsare lungă pe un rând intră în modul de
  selecție, apeși pe câte aplicații vrei, apoi **Oprește (N)** le oprește pe
  toate dintr-un singur tap. Se raportează câte au reușit și câtă memorie s-a
  eliberat.
- **Lista albă și lista neagră**, ambele persistente:
  - **lista albă** = aplicații protejate; nu se opresc nici individual, nici în
    masă, iar verdictul lor devine „Nu opri”;
  - **lista neagră** = aplicațiile pe care le oprești mereu; au filtru propriu,
    ca să le oprești împreună dintr-un tap.
  Un pachet stă într-o singură listă — adăugarea într-una îl scoate din cealaltă.
  Se adaugă din fișa aplicației sau pe lot, din modul de selecție.
- **Dezinstalează** — și, implicit, face întâi o **copie a APK-urilor** în
  `Download/Checker/apk-backup/<pachet>/<data>/`.
- **Reinstalează** din copie, inclusiv pachetele cu split-uri
  (`pm install-create` → `install-write` → `install-commit`).
- Filtre: Toate / Rulează / Fundal / Consum CPU / Oprire sigură / Utilizator /
  Sistem / Înghețate / Listă albă / Listă neagră / Debloat, plus căutare după
  nume sau pachet.

## Siguranța la oprire

Verdictul nu e o presupunere: se calculează din rolurile pe care sistemul le dă
pachetului și din starea lui reală.

| Verdict | Când | Exemple |
|---|---|---|
| **Nu opri** | UID de sistem (< 10000), proces persistent, lansatorul, tastatura implicită, aplicația de apeluri sau de SMS, serviciu de accesibilitate activ, administrator de dispozitiv | SystemUI, Messages, launcher-ul |
| **Oprește doar temporar** | are serviciu în prim-plan sau e aplicație de sistem | muzică, descărcări, navigație, GMS |
| **Poți opri în siguranță** | aplicație de utilizator, fără serviciu în prim-plan, fără rol critic | aplicații obișnuite din fundal |

Rolurile critice se citesc din `Settings.Secure` (`default_input_method`,
`enabled_accessibility_services`), din `TelecomManager`, `Telephony.Sms`,
`DevicePolicyManager` și din rezolvarea intenției `CATEGORY_HOME`; serviciile în
prim-plan vin dintr-o singură citire `dumpsys activity services`, filtrată pe
`isForeground=true`.

Fiecare fișă de aplicație arată și informații despre pachet: versiune, UID,
procese, fire de execuție, memorie, SDK țintă/minim, data instalării și a
ultimei actualizări, sursa instalării (`com.android.vending`,
`com.google.android.packageinstaller` etc.), numărul de permisiuni cerute și
câte sunt periculoase, dacă are iconiță în sertar și dacă are serviciu în
prim-plan.

**Memoria partajată:** pachetele care împart un UID (de exemplu uid 1000, adică
`system_server`) raportează aceleași procese. Aplicația le arată ca „partajat” și
nu le mai adună la total, ca să nu apară sume umflate.

## Cum obține privilegii

Comenzile `pm`, `ps` și `dumpsys` au nevoie de uid `shell`. Aplicația are două
căi, ambele cu aceleași privilegii ca `adb shell`:

| Backend | Cum se conectează | Când e util |
|---|---|---|
| **ADB** (implicit) | socket TCP către serverul adb: `host:transport:<serial>` + `shell:<comandă>` | server adb pornit pe telefon (Termux) sau în rețea |
| **Shizuku** | `Shizuku.bindUserService` → `ShellUserService` rulează cu uid shell | fără server adb; necesită Shizuku pornit și permisiune acordată |

Pe acest telefon, serverul adb din Termux ascultă pe portul **5040**
(`ANDROID_ADB_SERVER_PORT`), deci valoarea implicită a aplicației este
`127.0.0.1:5040`. Hostul și portul se pot schimba din dialogul **Conexiune**.

## Comenzi folosite

```
pm list packages -s | -3 | --user 0 -d
ps -A -o PID,UID,NAME,RSS
dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'
pm path <pachet>
pm disable-user --user 0 <pachet>   /   pm enable <pachet>
pm uninstall <pachet>               /   pm uninstall --user 0 <pachet>   (sistem)
am force-stop <pachet>
pm install -r -d <apk>              /   pm install-create/write/commit  (split-uri)
```

## Structura proiectului

```
app/src/main/java/ro/davinci/checker/
├── MainActivity.kt / CheckerApp.kt      # intrarea în aplicație
├── CheckerViewModel.kt                  # stare UI, acțiuni, filtrare
├── shell/
│   ├── Shell.kt                         # ShellBackend + framing (cod de ieșire)
│   ├── AdbShell.kt                      # protocolul serverului adb peste TCP
│   ├── ShizukuShell.kt                  # Shizuku UserService + Messenger
│   ├── ShellUserService.kt              # procesul privilegiat (uid shell)
│   ├── LocalShell.kt                    # fallback fără privilegii
│   └── ShellController.kt               # alege backend-ul, ține starea
├── data/
│   ├── AppEntry.kt / AppScanner.kt      # pachete, procese, prim-plan, informații
│   ├── SystemStats.kt / LiveProbe.kt    # CPU/RAM/swap/load + jiffies per proces
│   ├── DebloatDb.kt                     # verdicte de debloat din assets/debloat.json
│   ├── AppSafety.kt                     # verdict de oprire + rolurile critice
│   ├── AppActions.kt                    # îngheață / dezgheață / force-stop
│   ├── ApkBackup.kt                     # copie APK + reinstalare
│   └── IconCache.kt                     # icoane LRU pentru listă
├── watch/
│   ├── CpuWatch.kt                      # setările paznicului CPU + canalul
│   └── CpuWatchService.kt               # serviciu în prim-plan, eșantion la 45 s
└── ui/                                  # Compose: temă, listă, fișă, grafice, backup-uri
```

`tools/` conține scripturile pentru testare din terminal:

- `tools/2procwatch.py` — copia **nealterată** a scriptului original folosit ca
  referință pentru citirea `/proc` prin adb.
- `tools/checker_probe.py` — varianta adaptată proiectului: listează pachetele pe
  categorii, corelează procesele cu pachetele și rulează exact acțiunile
  aplicației (freeze/unfreeze/uninstall cu backup/restore) prin `adb shell`.

```bash
python3 tools/checker_probe.py packages --running
python3 tools/checker_probe.py packages --json
python3 tools/checker_probe.py backup com.exemplu.app
python3 tools/checker_probe.py uninstall com.exemplu.app
python3 tools/checker_probe.py restore /sdcard/Download/Checker/apk-backup/com.exemplu.app/20261119-101500
```

## Build

Pe Termux aarch64, binarul `aapt2` din build-tools este x86-64 și nu rulează,
deci se indică `aapt2` din Termux:

```bash
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/data/data/com.termux/files/home/Android
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$(which aapt2)
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (`ro.davinci.checker.debug`).

**Debug e lent la derulare.** Un build `debug` (`debuggable=true`, fără AOT)
măsoară ~43% cadre pierdute și p90 = 133 ms la scroll; același cod în `release`
dă ~2,8% și p90 = 11 ms. Pentru senzația reală se folosește release-ul.

## Release semnat

**Build-ul de release se face pe GitHub Actions, nu pe telefon** — R8 + release
țin CPU-ul la 100% minute întregi și telefonul se încălzește. Local rămâne doar
pentru verificare rapidă a unui `assembleDebug`.

Release-ul folosește `ro.davinci.checker` (fără sufix) și un keystore propriu,
ca update-urile să se instaleze **peste** versiunea existentă, fără dezinstalare:

```bash
cp ~/9remote-uploads/opencode/checker-keys/checker-release.jks release.jks   # gitignored
PW=$(grep storePassword ~/9remote-uploads/opencode/checker-keys/passwords.txt | cut -d= -f2)
./gradlew :app:assembleRelease -Pandroid.aapt2FromMavenOverride=$(which aapt2) \
  -Pchecker.storePassword="$PW" -Pchecker.keyPassword="$PW" -Pchecker.keyAlias=checker
adb install -r app/build/outputs/apk/release/app-release.apk
```

Parolele se dau ca proprietăți (`-Pchecker.*`) sau, în CI, ca variabile de mediu
(`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`) — nu
doar din mediu local, fiindcă un `GradleDaemon` deja pornit nu primește
variabilele noi și semnarea cade cu „keystore password was incorrect”.

Fără `release.jks` build-ul rămâne nesemnat (`app-release-unsigned.apk`), util
doar pentru inspecție — nu se poate instala.

**Versionarea e automată:** `versionCode` = numărul de commituri din istorie,
`versionName` = `1.0.<versionCode>`. Fiecare commit nou produce un cod mai mare,
deci `adb install -r` nu dă `INSTALL_FAILED_VERSION_DOWNGRADE`. CI are nevoie de
`fetch-depth: 0` ca să vadă tot istoricul.

**Release prin CI:** `gh workflow run "Build APK" --ref main` — jobul
*Release APK semnat* decodifică keystore-ul din `RELEASE_KEYSTORE_BASE64`,
semnează, verifică semnătura cu `apksigner`, urcă artefactul și publică un
GitHub Release `v<versionName>` cu APK-ul atașat. Secrete necesare:
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`,
`RELEASE_KEY_ALIAS`.

## Limite cunoscute

- Fără adb și fără Shizuku, lista de aplicații se vede, dar starea „rulează” și
  acțiunile nu sunt disponibile.
- Aplicațiile de sistem sunt dezinstalate doar pentru utilizatorul 0
  (`pm uninstall --user 0`), nu din imaginea de sistem.
- Aplicațiile cu administrator de dispozitiv activ nu pot fi dezinstalate până
  nu se revocă adminul.
- Root nu este suportat.
- **Sarcina GPU** nu poate fi citită pe Android 16 fără root: SELinux închide
  `/sys/class/kgsl` chiar și pentru uid `shell` (pe acest telefon, orice citire
  din `kgsl-3d0` dă „Permission denied”). Rândul apare doar pe nucleele care
  expun `gpubusy` cu drept de citire.
- **Paznicul CPU** cere permisiunea de notificări (Android 13+) și acces la
  shell. Fără shell trei eșantioane la rând, serviciul se oprește și spune de ce.
  Monitorizează doar aplicațiile de utilizator (uid ≥ 10 000), nu procesele de
  sistem.
## Credite și licențe

- Ideea de a citi CPU pe proces din `/proc/<pid>/stat` și structura bazei de
  debloat vin din [TaskManager](https://github.com/RohitKushvaha01/TaskManager)
  (Apache 2.0). Din el nu s-a preluat nimic din stratul comercial: Checker nu are
  reclame, cumpărături în aplicație sau funcții „pro”.
- `assets/debloat.json` derivă din lista
  [Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)
  (GPL-3.0): pachet, origine, verdict și o descriere scurtă. Datele sunt
  fapte despre pachete și sunt folosite aici cu atribuire; codul Checker rămâne
  separat de ele.
- Restul codului este scris pentru acest proiect.
