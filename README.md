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
- Spune **ce se poate opri în siguranță**: fiecare aplicație primește un verdict
  (oprire sigură / atenție / nu opri) și motivul lui, iar filtrul
  **Oprire sigură** arată doar aplicațiile care pot fi oprite fără efecte.
- **Îngheață** (`pm disable-user --user 0`) și **dezgheață** (`pm enable`)
  aplicații.
- **Dezinstalează** — și, implicit, face întâi o **copie a APK-urilor** în
  `Download/Checker/apk-backup/<pachet>/<data>/`.
- **Reinstalează** din copie, inclusiv pachetele cu split-uri
  (`pm install-create` → `install-write` → `install-commit`).
- Filtre: Toate / Rulează / Fundal / Oprire sigură / Utilizator / Sistem /
  Înghețate, plus căutare după nume sau pachet.

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
procese, memorie, SDK țintă/minim, data instalării și a ultimei actualizări,
sursa instalării (`com.android.vending`, `com.google.android.packageinstaller`
etc.), numărul de permisiuni cerute și câte sunt periculoase, dacă are iconiță
în sertar și dacă are serviciu în prim-plan.

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
│   ├── AppSafety.kt                     # verdict de oprire + rolurile critice
│   ├── AppActions.kt                    # îngheață / dezgheață / force-stop
│   ├── ApkBackup.kt                     # copie APK + reinstalare
│   └── IconCache.kt                     # icoane LRU pentru listă
└── ui/                                  # Compose: temă, listă, fișă, backup-uri
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

Release-ul folosește `ro.davinci.checker` (fără sufix) și un keystore propriu,
ca update-urile să se instaleze **peste** versiunea existentă, fără dezinstalare:

```bash
cp ~/9remote-uploads/opencode/checker-keys/checker-release.jks release.jks   # gitignored
export RELEASE_STORE_PASSWORD=$(grep storePassword ~/9remote-uploads/opencode/checker-keys/passwords.txt | cut -d= -f2)
export RELEASE_KEY_PASSWORD=$RELEASE_STORE_PASSWORD
export RELEASE_KEY_ALIAS=checker
./gradlew :app:assembleRelease -Pandroid.aapt2FromMavenOverride=$(which aapt2)
adb install -r app/build/outputs/apk/release/app-release.apk
```

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