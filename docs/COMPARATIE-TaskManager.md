# Checker vs. TaskManager (RohitKushvaha01) — comparație și plan de fuziune

Analiză făcută pe `~/dev/TaskManager` @ `444e7ca` (20 nov. 2026) și
`~/dev/checker` @ `6ac4885` (v1.0.12).

Ambele aplicații fac același lucru de bază — citesc procesele Android și le
opresc prin shell — dar pornesc din filozofii diferite:

- **TaskManager** e un *monitor de sistem* („gnome system monitor pentru
  Android”): grafice, CPU/GPU/temperatură, fiecare proces linux, daemon nativ.
- **Checker** e un *curățător sigur*: ce aplicații rulează, ce se poate opri
  fără să strici telefonul, backup APK înainte de dezinstalare.

---

## 1. Tabel comparativ

| Capabilitate | Checker | TaskManager |
|---|---|---|
| Listă aplicații instalate (sistem/utilizator) | ✅ 551 pachete | ⚠️ doar procese vii |
| Procese linux (nu doar aplicații) | ❌ | ✅ toate, cu PID/PPID |
| Memorie per pachet (RSS prin UID) | ✅ | ✅ per proces |
| **CPU % per proces** | ❌ | ✅ |
| Fire de execuție, nice, state, cgroup, exe path | ❌ | ✅ |
| Prim-plan / fundal | ✅ prin `dumpsys activity` (exact) | ⚠️ euristică `oom_score_adj <= 100` |
| **Grafice istorice** CPU / RAM / Swap / GPU | ❌ | ✅ (Vico, 800 ms) |
| Info SoC, clustere, frecvențe, governor, temperatură | ❌ | ✅ |
| Baterie (cicluri, curent) | ❌ | ✅ |
| **Verdict de oprire sigură** (lansator, IME, dialer, SMS, admin, servicii foreground) | ✅ cu motiv | ❌ (doar confirmare) |
| **Îngheață / dezgheață** (`pm disable-user`) | ✅ | ❌ |
| **Backup APK înainte de dezinstalare** + restaurare (inclusiv split-uri) | ✅ | ❌ |
| Dezinstalare | ✅ | ❌ |
| **Oprire în masă din selecție** | ✅ | ❌ (un proces pe rând) |
| **Listă albă / listă neagră** persistente | ✅ | ⚠️ doar „pinned” (fixare în listă, nu protecție) |
| Info debloat (ce face un pachet de sistem, din lista UAD) | ❌ | ✅ (`uad_lists.json` → Room) |
| Sortare după RAM / nume / CPU | ✅ RAM, nume | ✅ RAM, CPU, A-Z |
| Căutare | ✅ | ✅ cu debounce 150 ms |
| Pull-to-refresh | ❌ | ✅ |
| Auto-refresh | ❌ (manual) | ✅ 5 s listă / 0,8 s grafice |
| Moduri de lucru | adb TCP + Shizuku | Shizuku (3 moduri) + root |
| Temă | una | 4 teme + Monet |
| Limbă | română | engleză (+ trad. comunitate) |
| Reclame / cumpărături în aplicație | ❌ | ✅ AdMob + billing + modul „pro” closed-source |
| Licență | own | Apache 2.0 |
| Teste | 0 | 0 (doar `ExampleUnitTest`) |
| Mărime cod | 20 fișiere, 3.586 linii | 94 fișiere, 9.755 linii + 652 C++ |
| Build | 1 modul, CI semnat | 6 module, NDK fixat, baseline profiles |

---

## 2. Arhitectură

### TaskManager

```
app (shell fin)  →  main (UI + logică)  →  bridge (pro/OSS)  →  components (UI comun)
                          │
                          ├─ AIDL IDaemonUserService
                          ▼
                   Shizuku (shell/root)
                          │
                          ▼
              libtaskmanagerd.so  ←  C++ care citește /proc
                          │  JSON o linie per răspuns, prin pipe
                          ▼
                   DaemonServer (InputStream → SharedFlow)
```

De ce au nevoie de daemon: din Android 7, o aplicație **nu poate citi**
`/proc/<pid>/stat` pentru procesele altcuiva (`hidepid`). Deci citesc din
procesul care rulează ca `shell`, o singură dată, și trimit JSON prin pipe.

**Costul:** un executabil nativ compilat cu NDK, AIDL, două moduri Shizuku,
sincronizare de pipe, ~6,4 MB de modul `app` doar din asset-uri + cod nativ.

### Checker

```
ViewModel → ShellController → { AdbShell (TCP 5040) | ShizukuShell (Messenger) | LocalShell }
                  │
                  ├─ AppScanner: `pm list packages`, `ps -A -o PID,UID,NAME,RSS`, dumpsys
                  └─ AppActions: `am force-stop`, `pm disable-user`, `pm uninstall`
```

Fără daemon, fără AIDL, fără cod nativ. Fiecare scanare = 3–4 comenzi shell.
Suficient pentru 551 pachete; mai puțin fin pentru „fiecare proces, la 800 ms”.

---

## 3. Ce merită luat de la TaskManager

### 3.1 Informație per proces (cel mai mare câștig)

Cod: `ProcessViewModel.Process`, `taskmanagerd.cpp:readProc()`.

Câmpuri pe care Checker nu le are: `cpuUsage`, `threads`, `nice`, `state`,
`cgroup`, `executablePath`, `parentPid`, `startTime`, `elapsedTime`,
`virtualMemoryKb`.

**Nu e nevoie de daemon ca să le obții.** Am verificat pe telefon: prin shell-ul
pe care Checker îl are deja, `ps -A -o PID,UID,NAME,RSS,%CPU,%MEM,NI,STAT`
returnează toate astea. Deci se poate face în Kotlin, în `AppScanner`.

### 3.2 Grafice istorice CPU / RAM / Swap

Cod: `commons/charts/*`, `CPU.kt`, `RAM.kt`, `graphUpdater.kt`.

Un `GraphDataHandler` care ține un inel de eșantioane și un `UsageChart` (Vico).
Se poate face și cu Canvas propriu (fără dependență) în ~150 de linii.

Sursa de date, verificată pe device: `/proc/stat` (CPU total), `/proc/meminfo`
(RAM + Swap), `/proc/loadavg`. Se citesc dintr-o singură comandă shell.

### 3.3 Info SoC + temperatură

Cod: `CpuInfoReader.kt`, `CPU.kt:ClusterCard`.

Verificat pe device: `/sys/class/thermal/thermal_zone*/type` + `temp`,
`/sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq`. Se citesc prin shell.
Util în Checker ca să explici *de ce* telefonul e cald când rulează 90 de aplicații.

### 3.4 Baza de date debloat (UAD) — cea mai practică idee

Cod: `generate_prebuilt_db.py`, `app/src/main/assets/databases/apps.db`,
`data/Data.kt`, `ProcessInfo.kt:580`.

`uad_lists.json` (Universal Android Debloater, ~1.500 pachete de sistem) e
transformat într-un SQLite de 800 KB și împachetat în APK. La deschiderea fișei
unei aplicații de sistem, arată ce face pachetul și dacă e recomandat de
eliminat. Pentru un „curățător” asta e exact informația care lipsește: Checker
știe *dacă* e sigur să oprești, dar nu știe *ce este* `com.motorola.motocare`.

Se poate implementa fără Room: JSON-ul e mic, se încarcă o dată în memorie
(`Map<String, String>`), sau se împachetează ca SQLite și se citește cu
`SQLiteDatabase`.

### 3.5 Fixarea în listă (pin) + pull-to-refresh

Cod: `Settings.pinnedProcesses`, sortarea `sortedByDescending { isPinned }`,
`PullToRefreshBox`. Ieftin de portat, util la liste de 500+ rânduri.

### 3.6 Detalii de inginerie bune

- Versionarea din git (Checker o are deja, la fel).
- Baseline profiles pentru pornire (Checker nu are).
- NDK fixat + build reproductibil (relevant doar dacă apare cod nativ).
- Moduri de lucru explicite, cu ecran de selecție la prima pornire.

---

## 4. Ce are Checker și TaskManager nu

### 4.1 Verdictul de oprire sigură — diferența fundamentală

`AppSafety.kt` calculează din roluri reale: lansator, tastatură (IME), telefon,
SMS, servicii de accesibilitate, administrator de dispozitiv, `UID < 10000`,
`FLAG_PERSISTENT`, serviciu în prim-plan → **SAFE / CAUTION / NEVER** + motiv.

TaskManager are doar `isForegroundProcess()` = `oom_score_adj <= 100`, adică o
euristică. Consecința: poți opri lansatorul sau tastatura fără avertisment.
Sondaj pe telefon: 7 aplicații de utilizator rulând → 1 lansator (NEVER),
4 cu servicii în prim-plan (CAUTION), 1 sigură.

**Asta nu se copiază din TaskManager — se păstrează.**

### 4.2 Backup APK + restaurare

`ApkBackup.kt`: copiază toate split-urile în
`Download/Checker/apk-backup/<pachet>/<data>/` înainte de dezinstalare, iar la
restaurare le stagează în `/data/local/tmp` (fiindcă `pm install` nu poate citi
FUSE `/sdcard`) și face `install-create` → `install-write` → `install-commit`.
TaskManager nu dezinstalează deloc, deci nu are problema — dar nici soluția.

### 4.3 Înghețare / dezghețare

`pm disable-user --user 0` / `pm enable`. Pentru aplicații de sistem e adesea mai
bun decât oprirea: nu repornește la următorul eveniment.

### 4.4 Liste albă / neagră cu protecție aplicată

Nu doar filtre: lista albă schimbă verdictul în „Nu opri”, e sărită la oprirea
în masă și dezactivează butonul individual. Un pachet stă într-o singură listă.

### 4.5 Oprire în masă

Selecție prin apăsare lungă, contor de câte rulează / câte sunt protejate,
`Oprește (N)`, raport de reușite/eșecuri și memorie eliberată.

### 4.6 Igienă de proiect

Fără reclame, fără billing, fără modul closed-source, fără `uad_lists.json` de
800 KB în git. Semnare prin `-P`/secrete, nu `/home/rohit/...`. Un singur modul,
build în ~1 min pe CI.

---

## 5. Ce NU merită copiat

| Lucru | De ce nu |
|---|---|
| AdMob + billing + `taskmanager_pro` (submodul privat) | Checker e FOSS, fără reclame; modulul „pro” nu e în repo |
| `system()` în C++ pentru `am force-stop` | pornește un shell; Checker are deja `ShellBackend` |
| Semnare cu cale hardcodată `/home/rohit/...` | Checker citește din `-P` / secrete |
| `compileSdk 37` + AGP 9.4 + Kotlin 2.3.21 | prea aproape de bleeding edge pentru un build pe Termux |
| 6 module pentru o aplicație de 10k linii | complexitate fără câștig la scara Checker |
| Lipsa testelor | ambele au 0 teste reale; nu e model |

---

## 6. Licență

TaskManager e **Apache 2.0**: codul poate fi reutilizat, cu păstrarea notației de
copyright și a textului licenței pentru fișierele copiate. `uad_lists.json` vine
din Universal Android Debloater (GPL-3.0) — ca *date* se pot include cu
atribuire, dar dacă Checker devine derivat din cod GPL, discuția se schimbă.
Recomandare: pentru UAD să folosim datele cu atribuire clară în „Despre”, nu cod.

---

## 7. Plan: „din două, una mai bună”

Ținta: **Checker rămâne aplicația sigură și românească, dar capătă ochii
TaskManager** (CPU per proces, grafice, temperatură, info debloat). Fără daemon
nativ, fără reclame.

### Faza 1 — CPU per proces + grafice live (valoare mare, risc mic)

1. `AppScanner`: comandă nouă `ps -A -o PID,UID,NAME,RSS,%CPU,%MEM,NI,STAT,TIME`
   → `AppEntry.cpuPercent`, `processCount`, `threadCount`; agregare pe UID.
2. `data/SystemStats.kt`: `cat /proc/stat`, `/proc/meminfo`, `/proc/loadavg`
   → CPU total %, RAM folosită/totală, swap.
3. `ui/Graph.kt`: grafic Canvas (inel de 60 eșantioane, 2 serii) — fără Vico.
4. `CheckerViewModel`: tick de 2 s când ecranul e vizibil, oprit în fundal.
5. Sortare nouă: `CPU`; filtru nou: „Consum CPU”.

**Fișiere atinse:** `AppScanner.kt`, `AppEntry.kt`, `CheckerViewModel.kt`,
`CheckerScreen.kt`, + `SystemStats.kt`, `Graph.kt`. ~400 linii.

### Faza 2 — Info debloat (UAD) + fișă de aplicație îmbogățită

1. `tools/build_apps_db.py` (adaptat din `generate_prebuilt_db.py`) →
   `app/src/main/assets/apps.db` (doar `id`, `description`, `recommendation`).
2. `data/DebloatInfo.kt`: citire cu `SQLiteDatabase`, cache în memorie.
3. Fișă aplicație: secțiune „Ce este” + „Recomandare” + badge „recomandat de
   eliminat” în listă, doar pentru pachete de sistem.
4. Filtru nou: „Debloat” (pachete de sistem cu recomandare de eliminare).

**~250 linii + 800 KB asset.**

### Faza 3 — Context de sistem

1. Card sus: SoC, temperatură, frecvențe per cluster, load average.
2. `CpuInfoReader` adaptat (citește prin `ShellBackend`, nu direct din fișiere).
3. Avertizare când temperatura depășește un prag + multe aplicații în fundal.

**~200 linii.**

### Faza 4 — Opțional: daemon

Doar dacă apare nevoia de eșantionare sub 1 s sau de procese linux complete.
Atunci: `libcheckerd.so` minimal (JSON prin pipe), pornit prin Shizuku, cu
aceleași comenzi ca `ShellBackend`. Nu înainte de Faza 1–3.

---

## 8. Verificări făcute pe device (moto g57 power, Android 16)

```
ps -A -o PID,UID,NAME,RSS,%CPU,%MEM,NI,STAT   → funcționează
/proc/stat                                     → cpu 1347710 117572 ...
/proc/meminfo                                  → MemTotal 11780368 kB, MemAvailable 6098468 kB
/sys/class/thermal/thermal_zone*/temp          → cpuss-0=39900 (39,9 °C)
/sys/devices/system/cpu/cpufreq/policy0/...    → 691200 kHz
```

Concluzie: **tot ce face TaskManager prin daemon se poate citi prin shell-ul pe
care Checker îl are deja.** Daemonul lor e o optimizare, nu o necesitate.

---

## 9. Stadiu implementare

### Faza 1 — live stats (implementată)

- `data/SystemStats.kt` — CPU %, RAM, swap, load, nuclee.
- `data/LiveProbe.kt` — un singur apel shell per eșantion: `/proc/stat`,
  `/proc/meminfo`, `/proc/loadavg`, `ps -A -o PID,UID,RSS` și jiffies per PID
  din `/proc/<pid>/stat` (awk, o trecere). CPU per proces = diferența de
  jiffies între două eșantioane, nu media pe durata vieții procesului.
- `ui/Graph.kt` — sparkline desenat pe Canvas, fără librărie de grafice.
- Card „Consum în timp real" cu CPU și RAM, comutator pentru oprirea
  eșantionării, swap/load/nuclee pe un rând.
- Filtru „Consum CPU" (peste 1%) și sortare CPU; CPU apare și pe rând,
  lângă memorie.
- Eșantionarea rulează la 2 s **doar** cât timp ecranul e vizibil
  (`ON_START`/`ON_STOP`), ca să nu consume baterie în fundal.

Diferență față de plan: numărul de fire per proces (`THREADS`) nu e disponibil
în `ps`-ul de pe Android 16 (`ps: bad -o 'PID,THREADS'`), iar citirea
`/proc/<pid>/status` pentru fiecare PID ar dubla costul eșantionului. Se poate
adăuga mai târziu, doar pentru aplicația deschisă în detaliu.

### Faza 2 — debloat UAD (de făcut)

### Faza 3 — SoC/temperaturi (de făcut)
