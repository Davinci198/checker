#!/usr/bin/env python3
"""CLI de test pentru aplicația Checker — varianta „adaptată” a lui 2procwatch.py.

Scriptul original (`2procwatch.py`) rămâne neatins lângă acesta, ca referință
pentru citirea /proc prin adb. Fișierul de față păstrează aceeași abordare
(un singur `adb shell` per mostră, `/proc` + `ps`), dar o orientează pe nevoile
aplicației Android:

  - inventar de pachete, împărțit în sistem / utilizator / înghețate;
  - corelarea proceselor care rulează cu pachetele lor (prin UID);
  - înghețare (`pm disable-user`), dezghețare (`pm enable`);
  - dezinstalare, cu copie APK înainte (implicit);
  - reinstalare din copia APK (inclusiv pachete cu split-uri).

Comenzile sunt identice cu cele pe care le rulează aplicația Kotlin prin
backend-ul adb sau Shizuku, deci rezultatul din CLI e proba că aplicația are
comenzile corecte.

Exemple:

    python3 checker_probe.py packages
    python3 checker_probe.py packages --running --user
    python3 checker_probe.py packages --json
    python3 checker_probe.py freeze com.exemplu.app
    python3 checker_probe.py unfreeze com.exemplu.app
    python3 checker_probe.py backup com.exemplu.app
    python3 checker_probe.py uninstall com.exemplu.app          # copiază APK, apoi șterge
    python3 checker_probe.py uninstall com.exemplu.app --no-backup
    python3 checker_probe.py restore /sdcard/Download/Checker/apk-backup/com.exemplu.app/20261119-101500

Implicit folosește primul dispozitiv adb. Cu `--serial` se poate alege altul
(ex. `127.0.0.1:5555` pentru adb over Wi-Fi).
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Tuple

BACKUP_ROOT = "/sdcard/Download/Checker/apk-backup"


# --------------------------------------------------------------------- adb
class Adb:
    """Un singur punct de acces la `adb shell`, ca în 2procwatch.py."""

    def __init__(self, serial: Optional[str] = None, timeout: float = 30.0) -> None:
        base = ["adb"]
        if serial:
            base += ["-s", serial]
        self._base = base
        self.timeout = timeout

    def run(self, command: str, timeout: Optional[float] = None) -> Tuple[int, str]:
        try:
            result = subprocess.run(
                self._base + ["shell", command],
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout or self.timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            return -1, str(exc)
        return result.returncode, result.stdout

    def out(self, command: str, timeout: Optional[float] = None) -> str:
        return self.run(command, timeout)[1]

    def device_ok(self) -> bool:
        code, out = self.run("echo probe-ok", timeout=8)
        return code == 0 and "probe-ok" in out


# ------------------------------------------------------------------ modele
@dataclass
class ProcessInfo:
    pid: int
    uid: int
    name: str
    rss_kb: int


@dataclass
class Package:
    name: str
    system: bool
    frozen: bool = False
    version: str = ""
    pids: List[int] = field(default_factory=list)
    rss_kb: int = 0

    @property
    def running(self) -> bool:
        return bool(self.pids)

    @property
    def category(self) -> str:
        return "sistem" if self.system else "utilizator"


# ------------------------------------------------------------------ scanare
def list_packages(adb: Adb, user: int = 0) -> Tuple[List[Package], Dict[int, List[int]]]:
    """Returnează pachetele și harta uid -> pachete (pentru corelare procese)."""
    system = _package_set(adb, "-s")
    third_party = _package_set(adb, "-3")
    disabled = _package_set(adb, f"--user {user} -d")

    packages: Dict[str, Package] = {}
    for name in system:
        packages[name] = Package(name=name, system=True, frozen=name in disabled)
    for name in third_party:
        packages.setdefault(name, Package(name=name, system=False, frozen=name in disabled))

    uid_map: Dict[int, List[int]] = {}
    for line in adb.out("ps -A -o PID,UID,NAME,RSS").splitlines()[1:]:
        parts = line.split()
        if len(parts) < 4 or not parts[0].isdigit() or not parts[1].isdigit():
            continue
        uid_map.setdefault(int(parts[1]), []).append(int(parts[0]))

    return [packages[name] for name in sorted(packages)], uid_map


def _package_set(adb: Adb, flags: str) -> List[str]:
    lines = adb.out(f"pm list packages {flags}").splitlines()
    return [
        line.strip().removeprefix("package:")
        for line in lines
        if line.strip().startswith("package:")
    ]


def attach_processes(packages: Iterable[Package], adb: Adb) -> None:
    """Corelează procesele cu pachetele prin UID (ca `ps` din 2procwatch.py)."""
    processes: Dict[int, ProcessInfo] = {}
    for line in adb.out("ps -A -o PID,UID,NAME,RSS").splitlines()[1:]:
        parts = line.split()
        if len(parts) < 4 or not parts[0].isdigit() or not parts[1].isdigit():
            continue
        processes[int(parts[0])] = ProcessInfo(
            pid=int(parts[0]),
            uid=int(parts[1]),
            name=parts[2],
            rss_kb=int(parts[3]) if parts[3].isdigit() else 0,
        )

    # UID -> pachete: `dumpsys package` ar fi scump; folosim convenția
    # procesului principal (numele procesului == numele pachetului) plus
    # potrivirea pe prefix, exact ce face și aplicația prin getPackagesForUid.
    by_name = {pkg.name: pkg for pkg in packages}
    for process in processes.values():
        owner = process.name.split(":", 1)[0]
        package = by_name.get(owner)
        if package is None:
            # procese SystemUI / provideri etc. rămân neatașate
            continue
        package.pids.append(process.pid)
        package.rss_kb += process.rss_kb


# ----------------------------------------------------------------- acțiuni
def freeze(adb: Adb, package: str) -> Tuple[bool, str]:
    code, out = adb.run(f"pm disable-user --user 0 {shlex.quote(package)}")
    if _pm_ok(out):
        return True, f"{package}: înghețată"
    code, out = adb.run(f"pm disable {shlex.quote(package)}")
    return _pm_ok(out), out.strip() or f"{package}: înghețarea a eșuat"


def unfreeze(adb: Adb, package: str) -> Tuple[bool, str]:
    code, out = adb.run(f"pm enable {shlex.quote(package)}")
    if _pm_ok(out):
        return True, f"{package}: dezghețată"
    code, out = adb.run(f"pm enable --user 0 {shlex.quote(package)}")
    return _pm_ok(out), out.strip() or f"{package}: dezghețarea a eșuat"


def backup(adb: Adb, package: str) -> Tuple[bool, str]:
    paths = [
        line.strip().removeprefix("package:")
        for line in adb.out(f"pm path {shlex.quote(package)}").splitlines()
        if line.strip().startswith("package:")
    ]
    if not paths:
        return False, f"{package}: niciun APK găsit"

    stamp = time.strftime("%Y%m%d-%H%M%S")
    directory = f"{BACKUP_ROOT}/{package}/{stamp}"
    copies = " && ".join(
        f"cp -f {shlex.quote(path)} {shlex.quote(directory + '/' + path.rsplit('/', 1)[-1])}"
        for path in paths
    )
    command = (
        f"mkdir -p {shlex.quote(directory)} && {copies} && "
        f"chmod 644 {shlex.quote(directory)}/*.apk && ls -l {shlex.quote(directory)}"
    )
    code, out = adb.run(command, timeout=180)
    if code != 0 or ".apk" not in out:
        return False, f"{package}: copierea a eșuat ({out.strip()})"
    return True, f"{package}: {len(paths)} APK-uri în {directory}"


def uninstall(adb: Adb, package: str, is_system: bool, do_backup: bool) -> Tuple[bool, str]:
    note = ""
    if do_backup:
        ok, message = backup(adb, package)
        if not ok:
            return False, f"dezinstalare anulată — {message}"
        note = f" ({message})"

    command = (
        f"pm uninstall --user 0 {shlex.quote(package)}"
        if is_system
        else f"pm uninstall {shlex.quote(package)}"
    )
    code, out = adb.run(command, timeout=120)
    if "Success" in out:
        return True, f"{package}: dezinstalată{note}"
    return False, f"{package}: {out.strip() or 'răspuns gol de la pm'}"


def restore(adb: Adb, directory: str) -> Tuple[bool, str]:
    listing = adb.out(f"ls -l {shlex.quote(directory.rstrip('/'))}")
    entries = []
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[-1].endswith(".apk") and parts[4].isdigit():
            entries.append((parts[-1], int(parts[4])))

    if not entries:
        return False, f"{directory}: niciun .apk"

    # `pm install` rulează în system_server, care nu poate deschide căi FUSE
    # din /sdcard. Copiem întâi în /data/local/tmp, ca la aplicația Kotlin.
    stage = f"/data/local/tmp/checker-restore-{int(time.time())}"
    copies = " && ".join(
        f"cp -f {shlex.quote(directory.rstrip('/') + '/' + name)} "
        f"{shlex.quote(stage + '/' + name)}"
        for name, _ in entries
    )
    code, out = adb.run(
        f"rm -rf {shlex.quote(stage)} && mkdir -p {shlex.quote(stage)} && {copies} && "
        f"chmod 644 {shlex.quote(stage)}/*.apk",
        timeout=180,
    )
    if code != 0:
        adb.run(f"rm -rf {shlex.quote(stage)}")
        return False, f"pregătirea instalării a eșuat: {out.strip()}"

    try:
        return _install_staged(adb, stage, entries)
    finally:
        adb.run(f"rm -rf {shlex.quote(stage)}")


def _install_staged(adb: Adb, stage: str, entries: List[Tuple[str, int]]) -> Tuple[bool, str]:
    if len(entries) == 1:
        name = entries[0][0]
        code, out = adb.run(
            f"pm install -r -d {shlex.quote(stage + '/' + name)}", timeout=300
        )
        return "Success" in out, out.strip() or "răspuns gol de la pm install"

    total = sum(size for _, size in entries)
    code, out = adb.run(f"pm install-create -r -d -S {total}")
    session = ""
    for line in out.splitlines():
        if "[" in line and "]" in line:
            session = line[line.index("[") + 1 : line.index("]")]
            break
    if not session:
        return False, f"nu pot crea sesiunea: {out.strip()}"

    for name, size in entries:
        code, write_out = adb.run(
            f"pm install-write -S {size} {session} {shlex.quote(name)} "
            f"{shlex.quote(stage + '/' + name)}",
            timeout=300,
        )
        if code != 0:
            adb.run(f"pm install-abandon {session}")
            return False, f"{name}: {write_out.strip()}"

    code, commit = adb.run(f"pm install-commit {session}", timeout=300)
    return "Success" in commit, commit.strip() or "răspuns gol la install-commit"


def _pm_ok(output: str) -> bool:
    return "Success" in output or "new state" in output


# -------------------------------------------------------------------- CLI
def render(packages: List[Package], running_only: bool, as_json: bool) -> None:
    rows = [pkg for pkg in packages if pkg.running] if running_only else packages

    if as_json:
        print(json.dumps(
            [
                {
                    "package": pkg.name,
                    "category": pkg.category,
                    "frozen": pkg.frozen,
                    "running": pkg.running,
                    "pids": pkg.pids,
                    "rss_kb": pkg.rss_kb,
                }
                for pkg in rows
            ],
            indent=2,
        ))
        return

    system = sum(1 for pkg in packages if pkg.system)
    print(f"{len(packages)} pachete · sistem {system} · utilizator {len(packages) - system} "
          f"· înghețate {sum(1 for pkg in packages if pkg.frozen)} "
          f"· rulează {sum(1 for pkg in packages if pkg.running)}\n")

    for pkg in rows:
        flags = []
        if pkg.frozen:
            flags.append("îngheţat")
        if pkg.running:
            flags.append(f"PID {pkg.pids[0]}")
            flags.append(f"{pkg.rss_kb // 1024} MB")
        suffix = ("  [" + ", ".join(flags) + "]") if flags else ""
        print(f"  {pkg.category:<10} {pkg.name}{suffix}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Test CLI pentru aplicația Checker (adb shell, ca 2procwatch.py).",
    )
    parser.add_argument("--serial", help="serialul adb (implicit: primul dispozitiv)")
    parser.add_argument("--user", type=int, default=0, help="userul Android (implicit 0)")

    sub = parser.add_subparsers(dest="command", required=True)

    packages_cmd = sub.add_parser("packages", help="listează pachetele pe categorii")
    packages_cmd.add_argument("--running", action="store_true", help="doar ce rulează")
    packages_cmd.add_argument("--system", action="store_true", help="doar aplicații de sistem")
    packages_cmd.add_argument("--user-apps", action="store_true", help="doar aplicații de utilizator")
    packages_cmd.add_argument("--frozen", action="store_true", help="doar înghețate")
    packages_cmd.add_argument("--json", action="store_true", help="ieșire JSON")

    for name, helper in (
        ("freeze", "îngheață un pachet"),
        ("unfreeze", "dezgheață un pachet"),
        ("backup", "copiază APK-urile pachetului"),
    ):
        cmd = sub.add_parser(name, help=helper)
        cmd.add_argument("package")

    uninstall_cmd = sub.add_parser("uninstall", help="copiază APK, apoi dezinstalează")
    uninstall_cmd.add_argument("package")
    uninstall_cmd.add_argument("--no-backup", action="store_true", help="fără copie APK")
    uninstall_cmd.add_argument("--system", action="store_true", help="tratează ca aplicație de sistem")

    restore_cmd = sub.add_parser("restore", help="reinstalează din directorul de backup")
    restore_cmd.add_argument("directory")

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    adb = Adb(args.serial)

    if not adb.device_ok():
        print("adb nu răspunde (dispozitiv conectat? server pornit?)", file=sys.stderr)
        return 2

    if args.command == "packages":
        packages, _ = list_packages(adb, args.user)
        attach_processes(packages, adb)
        if args.system:
            packages = [pkg for pkg in packages if pkg.system]
        if args.user_apps:
            packages = [pkg for pkg in packages if not pkg.system]
        if args.frozen:
            packages = [pkg for pkg in packages if pkg.frozen]
        render(packages, args.running, args.json)
        return 0

    if args.command == "freeze":
        ok, message = freeze(adb, args.package)
    elif args.command == "unfreeze":
        ok, message = unfreeze(adb, args.package)
    elif args.command == "backup":
        ok, message = backup(adb, args.package)
    elif args.command == "uninstall":
        ok, message = uninstall(adb, args.package, args.system, not args.no_backup)
    elif args.command == "restore":
        ok, message = restore(adb, args.directory)
    else:  # pragma: no cover
        return 2

    print(message)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())