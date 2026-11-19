# Checker

Aplicație Android (Kotlin + Jetpack Compose) pentru administrarea aplicațiilor
instalate pe telefon. Interfața este integral în română.

## Ce face

- **Scanează** toate pachetele instalate și le împarte în **sistem** și
  **utilizator**.
- Arată **ce rulează**: procesele aparținând fiecărui pachet (prin UID), memoria
  ocupată (RSS) și dacă aplicația este în **prim-plan** sau în **fundal**.
- **Îngheață** (`pm disable-user --user 0`) și **dezgheață** (`pm enable`)
  aplicații.
- **Dezinstalează** — și, implicit, face întâi o **copie a APK-urilor** în
  `Download/Checker/apk-backup/<pachet>/<data>/`.
- **Reinstalează** din copie, inclusiv pachetele cu split-uri
  (`pm install-create` → `install-write` → `install-commit`).
- Filtre: Toate / Rulează / Fundal / Utilizator / Sistem / Înghețate, plus
  căutare după nume sau pachet.

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
│   ├── AppEntry.kt / AppScanner.kt      # pachete, procese, prim-plan
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

## Limite cunoscute

- Fără adb și fără Shizuku, lista de aplicații se vede, dar starea „rulează” și
  acțiunile nu sunt disponibile.
- Aplicațiile de sistem sunt dezinstalate doar pentru utilizatorul 0
  (`pm uninstall --user 0`), nu din imaginea de sistem.
- Aplicațiile cu administrator de dispozitiv activ nu pot fi dezinstalate până
  nu se revocă adminul.
- Root nu este suportat.