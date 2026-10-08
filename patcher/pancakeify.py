#!/usr/bin/env python3
"""
Pancakeify patcher — bakes the Pancakeify runtime into an original Spotify APK.

SURGICAL pipeline (validated on Spotify 9.1.80.2221, Android 17):
  merge splits (APKEditor) -> extract+baksmali ONLY the dex holding the host Application
  -> inject one bootstrap call into attachBaseContext -> smali back -> repack the zip
  (replace that dex, add stable dex + mod assets + lsplant) -> zipalign -> apksigner.

We deliberately DO NOT apktool the whole app: Spotify's resources are huge and a full
round-trip is slow and fragile. We only touch one dex and add files, leaving
resources.arsc and every other entry byte-identical.

Modes:
  --mode roundtrip   merge + repack + sign, no code changes (proves install+run).
  --mode inject      full patch. Needs tools/pancake-stable.dex (M2 stub is fine);
                     mod/build/pancake.dex and tools/liblsplant.so are optional (M4/M3).

Tools (in tools/): APKEditor.jar, baksmali.jar, smali.jar. zipalign/apksigner from the
Android SDK build-tools; keytool from the JDK (all auto-located).

Usage:
  python patcher/pancakeify.py --input ../spotify-orig --mode inject
"""
from __future__ import annotations
import argparse, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import axml  # noqa: E402  (local AXML string-pool editor)

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
TEMPLATES = Path(__file__).resolve().parent / "templates"
BOOTSTRAP_CLASS = "com/pancakeify/stable/PancakeBootstrap"
APP_METHOD = "attachBaseContext(Landroid/content/Context;)V"


def log(*a): print("[pancakeify]", *a, flush=True)
def die(msg): log("ERROR:", msg); sys.exit(1)


def run(cmd, **kw):
    log("$", " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], **kw)
    if r.returncode != 0:
        die(f"command failed ({r.returncode}): {cmd[0]}")
    return r


# ---------- tool discovery ----------

def find_sdk() -> Path | None:
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        p = os.environ.get(env)
        if p and Path(p).is_dir():
            return Path(p)
    for g in (r"D:/Android/sdk", os.path.expanduser("~/Android/Sdk"),
              os.path.expanduser("~/AppData/Local/Android/Sdk")):
        if Path(g).is_dir():
            return Path(g)
    return None


def build_tool(name: str) -> str:
    sdk = find_sdk()
    if sdk:
        for bt in sorted((sdk / "build-tools").glob("*"), reverse=True):
            cands = (bt / name, bt / f"{name}.exe", bt / f"{name}.bat") if os.name == "nt" else (bt / name,)
            for cand in cands:
                if cand.exists():
                    return str(cand)
    found = shutil.which(name)
    if found:
        return found
    die(f"{name} not found (install Android SDK build-tools)")


def apksigner_cmd() -> list:
    """apksigner as a command list: the .jar through java everywhere (the SDK's launcher is a .bat on Windows
    and a shell script elsewhere); falls back to whatever build_tool finds."""
    sdk = find_sdk()
    if sdk:
        for bt in sorted((sdk / "build-tools").glob("*"), reverse=True):
            j = bt / "lib" / "apksigner.jar"
            if j.exists():
                return ["java", "-jar", str(j)]
    return [build_tool("apksigner")]


def keytool() -> str:
    jh = os.environ.get("JAVA_HOME")
    if jh and (Path(jh) / "bin").exists():
        for c in (Path(jh) / "bin" / "keytool", Path(jh) / "bin" / "keytool.exe"):
            if c.exists():
                return str(c)
    try:
        out = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                             capture_output=True, text=True).stderr
        m = re.search(r"java\.home = (.+)", out)
        if m:
            for c in (Path(m.group(1)) / "bin" / "keytool",
                      Path(m.group(1)) / "bin" / "keytool.exe"):
                if c.exists():
                    return str(c)
    except Exception:
        pass
    return shutil.which("keytool") or die("keytool not found (need a JDK)")


def jar(name: str) -> Path:
    p = TOOLS / name
    if not p.exists():
        die(f"missing {p} — see tools/README.md")
    return p


# ---------- input handling ----------

def collect_input(inp: Path, work: Path) -> Path:
    if inp.is_dir():
        apks = sorted(inp.glob("*.apk"))
        src_dir = inp
    elif inp.suffix in (".xapk", ".apks", ".zip"):
        ex = work / "unpacked"; ex.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(inp) as z: z.extractall(ex)
        apks = sorted(ex.glob("*.apk")); src_dir = ex
    elif inp.suffix == ".apk":
        return inp
    else:
        die(f"unsupported input: {inp}")
    if not apks:
        die(f"no .apk in {inp}")
    if len(apks) == 1:
        return apks[0]
    log(f"merging {len(apks)} splits with APKEditor")
    merged = work / "merged.apk"
    run(["java", "-jar", jar("APKEditor.jar"), "m", "-i", src_dir, "-o", merged])
    return merged


# ---------- find host Application + its dex ----------

def manifest_app_class(apk: Path) -> str | None:
    """AXML is binary; scan the UTF-16 string pool for a class ending exactly in
    'Application' (word boundary excludes 'ApplicationId'), preferring the app package."""
    with zipfile.ZipFile(apk) as z:
        s = z.read("AndroidManifest.xml").decode("utf-16-le", "ignore")
        pkg = re.search(r"([a-z][a-z0-9_]+(?:\.[a-z0-9_]+)+)", s)
        pkg = pkg.group(1) if pkg else ""
    cands = re.findall(r"[A-Za-z][\w.]+Application\b", s)
    cands = [c for c in dict.fromkeys(cands) if "." in c and not c.endswith("ApplicationId")]
    if not cands:
        return None
    cands.sort(key=lambda c: (0 if pkg and c.startswith(pkg.rsplit(".", 1)[0]) else 1, len(c)))
    return cands[0]


def dex_defining(apk: Path, fqcn: str, work: Path) -> str | None:
    """Which classesN.dex actually DEFINES the class. Confirms via baksmali (a string
    reference appears in many dexes; only one holds the class_def)."""
    needle = ("L" + fqcn.replace(".", "/") + ";").encode()
    rel = fqcn.replace(".", "/") + ".smali"
    with zipfile.ZipFile(apk) as z:
        dexes = sorted((n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)),
                       key=lambda n: int(re.match(r"classes(\d*)\.dex", n).group(1) or "1"))
        for n in dexes:
            if needle not in z.read(n):
                continue
            probe = work / ("probe_" + n)
            probe.write_bytes(z.read(n))
            outdir = work / ("probe_smali_" + n)
            subprocess.run(["java", "-jar", str(jar("baksmali.jar")), "d", str(probe),
                            "-o", str(outdir)], capture_output=True)
            if (outdir / rel).exists():
                shutil.rmtree(outdir, ignore_errors=True); probe.unlink(missing_ok=True)
                return n
            shutil.rmtree(outdir, ignore_errors=True); probe.unlink(missing_ok=True)
    return None


# ---------- injection ----------

def inject_call(smali: Path):
    text = smali.read_text(encoding="utf-8")
    call = f"    invoke-static {{p0, p0}}, L{BOOTSTRAP_CLASS};->start(Landroid/app/Application;Landroid/content/Context;)V"

    lines = text.splitlines()
    start = end = None
    for i, l in enumerate(lines):
        if l.strip().startswith(".method") and APP_METHOD in l:
            start = i
        elif start is not None and l.strip() == ".end method":
            end = i; break
    if start is None:
        die("attachBaseContext not found (host needs a synthesized override — not yet supported)")
    if any(BOOTSTRAP_CLASS in l for l in lines[start:end]):
        log("bootstrap call already present"); return
    ret = next((i for i in range(end - 1, start, -1) if lines[i].strip() == "return-void"), None)
    if ret is None:
        die("no return-void in attachBaseContext")
    lines[ret:ret] = ["", call]
    smali.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"injected bootstrap call before return-void @ line {ret}")


def _has_method(smali: Path, sig: str) -> bool:
    for l in smali.read_text(encoding="utf-8").splitlines():
        if l.strip().startswith(".method") and sig in l:
            return True
    return False


def inject_ctor_call(smali: Path):
    """Fallback for hosts that only INHERIT attachBaseContext (Spotify 9.1.90: it is final in p.ef71, in a dex that
    is already at the 65 536 method-id limit, so nothing can be added there). We hook from the Application's own
    constructor instead: PancakeBootstrap.early(app) arms a hook on ContextWrapper.attachBaseContext."""
    lines = smali.read_text(encoding="utf-8").splitlines()
    call = f"    invoke-static {{p0}}, L{BOOTSTRAP_CLASS};->early(Landroid/app/Application;)V"
    start = None
    for i, l in enumerate(lines):
        if l.strip().startswith(".method") and "<init>()V" in l:
            start = i
            break
    if start is None:
        die("no no-arg constructor in the host Application class")
    end = next((i for i in range(start, len(lines)) if lines[i].strip() == ".end method"), None)
    if any(BOOTSTRAP_CLASS in l for l in lines[start:end]):
        log("early call already present"); return
    sup = next((i for i in range(start, end) if lines[i].strip().startswith("invoke-direct {p0}") and "-><init>()V" in lines[i]), None)
    if sup is None:
        die("super constructor call not found in the host Application constructor")
    lines[sup + 1:sup + 1] = ["", call]
    smali.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"injected early() call after the super constructor call @ line {sup}")


def patch_dex(apk: Path, app_class: str, work: Path) -> tuple[str, Path]:
    dexname = dex_defining(apk, app_class, work) or die(f"no dex defines {app_class}")
    log(f"{app_class} defined in {dexname}")
    with zipfile.ZipFile(apk) as z:
        (work / dexname).write_bytes(z.read(dexname))
    smali_dir = work / "smali_target"
    run(["java", "-jar", jar("baksmali.jar"), "d", work / dexname, "-o", smali_dir])
    smali = smali_dir / (app_class.replace(".", "/") + ".smali")
    if not smali.exists():
        die(f"{smali} missing after baksmali")
    if _has_method(smali, APP_METHOD):
        inject_call(smali)
    else:
        log("host Application does not define attachBaseContext itself -> constructor injection")
        inject_ctor_call(smali)
    out_dex = work / "patched_target.dex"
    run(["java", "-jar", jar("smali.jar"), "a", smali_dir, "-o", out_dex])
    return dexname, out_dex


# ---------- repack ----------

def repack(src: Path, dst: Path, replace: dict[str, bytes], additions: list[tuple[str, Path]]):
    with zipfile.ZipFile(src) as zin:
        infos = zin.infolist()
        existing = {i.filename for i in infos}
        with zipfile.ZipFile(dst, "w") as zout:
            for item in infos:
                data = replace.get(item.filename, zin.read(item.filename))
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type          # preserve STORED for .so etc.
                zi.external_attr = item.external_attr
                zout.writestr(zi, data)
            for arc, path in additions:
                if arc in existing:
                    log(f"skip existing {arc}"); continue
                zi = zipfile.ZipInfo(arc)
                zi.compress_type = (zipfile.ZIP_STORED if arc.endswith(".so")
                                    else zipfile.ZIP_DEFLATED)
                zout.writestr(zi, path.read_bytes())
                log(f"added {arc} <- {path.name}")


def next_dex_slot(apk: Path) -> str:
    with zipfile.ZipFile(apk) as z:
        nums = [int(re.match(r"classes(\d*)\.dex", n).group(1) or "1")
                for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
    return f"classes{max(nums) + 1}.dex"


# ---------- signing ----------

def ensure_keystore(ks: Path):
    if ks.exists():
        return
    run([keytool(), "-genkeypair", "-v", "-keystore", ks, "-storepass", "pancake",
         "-keypass", "pancake", "-alias", "pancakeify", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000", "-dname", "CN=Pancakeify,O=Pancakeify,C=US"])


# --- pure-python zipalign -p (the SDK on a Linux box may only ship zipalign.exe) ---
def py_zipalign(src, dst, align_=4, so_align=4096):
    """zipalign -p equivalent: STORED entries on 4-byte (.so on page) boundaries."""
    with zipfile.ZipFile(src) as zin, open(dst, "wb") as out:
        entries = []
        for i in zin.infolist():
            raw = zin.read(i.filename)
            entries.append((i, raw))
        cd = []
        for i, raw in entries:
            name = i.filename.encode()
            stored = i.compress_type == zipfile.ZIP_STORED
            if stored:
                import zlib
                data = raw
                comp = data
            else:
                c = __import__("zlib").compressobj(6, 8, -15)
                comp = c.compress(raw) + c.flush()
            off = out.tell()
            a = so_align if (stored and i.filename.endswith(".so")) else align_
            extra = b""
            if stored:
                hdr = 30 + len(name)
                pad = (-(off + hdr)) % a
                extra = b"\0" * pad
            crc = __import__("zlib").crc32(raw) & 0xffffffff
            import struct
            out.write(struct.pack("<IHHHHHIIIHH", 0x04034b50, 20, 0x0800, 0 if stored else 8,
                                  0, 0x21, crc, len(comp), len(raw), len(name), len(extra)))
            out.write(name); out.write(extra); out.write(comp)
            cd.append((i, name, crc, len(comp), len(raw), off, 0 if stored else 8))
        cd_off = out.tell()
        for i, name, crc, cs, us, off, method in cd:
            out.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014b50, 20, 20, 0x0800, method, 0, 0x21,
                                  crc, cs, us, len(name), 0, 0, 0, 0, i.external_attr, off))
            out.write(name)
        cd_size = out.tell() - cd_off
        out.write(struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, len(cd), len(cd), cd_size, cd_off, 0))


def find_tool(name: str) -> str | None:
    sdk = find_sdk()
    if sdk:
        for bt in sorted((sdk / "build-tools").glob("*"), reverse=True):
            cands = (bt / name, bt / f"{name}.exe", bt / f"{name}.bat") if os.name == "nt" else (bt / name,)
            for cand in cands:
                if cand.exists():
                    return str(cand)
    return shutil.which(name)


def align_and_sign(apk: Path, out: Path, ks: Path):
    aligned = apk.with_name("aligned.apk")
    za = find_tool("zipalign")
    if za:
        run([za, "-p", "-f", "4", apk, aligned])
    else:
        log("zipalign not available here -> pure-python alignment")
        py_zipalign(apk, aligned)
    ensure_keystore(ks)
    run([*apksigner_cmd(), "sign", "--ks", ks, "--ks-pass", "pass:pancake",
         "--key-pass", "pass:pancake", str(aligned)])
    shutil.copy2(aligned, out)


# ---------- main ----------

def main():
    ap = argparse.ArgumentParser(description="Pancakeify patcher")
    ap.add_argument("--input", required=True)
    ap.add_argument("--mode", choices=["roundtrip", "inject"], default="inject")
    ap.add_argument("--app-class", default=None)
    ap.add_argument("--label", default="Pancakeify",
                    help="new app display name (renames the 'Spotify' label string); '' to skip")
    ap.add_argument("--out", default=str(ROOT / "dist" / "pancakeify-spotify.apk"))
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="pancakeify_"))
    log("work:", work)
    try:
        universal = collect_input(Path(args.input), work)

        replace: dict[str, bytes] = {}
        additions: list[tuple[str, Path]] = []

        if args.mode == "inject":
            app_class = args.app_class or manifest_app_class(universal) or die("no Application class")
            log("host Application:", app_class)
            dexname, new_dex = patch_dex(universal, app_class, work)
            replace[dexname] = new_dex.read_bytes()

            stable = jar("pancake-stable.dex")
            additions.append((next_dex_slot(universal), stable))

            mod = ROOT / "mod" / "build" / "pancake.dex"
            if mod.exists():
                additions.append(("assets/pancake/pancake.dex", mod))
            font = ROOT / "stable" / "assets" / "Inter.ttf"
            if font.exists():
                additions.append(("assets/pancake/Inter.ttf", font))
            css = ROOT / "mod" / "src" / "main" / "assets" / "themes" / "default.css"
            if css.exists():
                additions.append(("assets/themes/default.css", css))
            sos = sorted(TOOLS.glob("lib*.so"))
            for so in sos:
                additions.append((f"lib/arm64-v8a/{so.name}", so))
            if not sos:
                log("WARN: no tools/lib*.so — hooking disabled")

        if args.label:
            # The app label is a resource reference, so rename the value in resources.arsc
            # (the exact "Spotify" value entry), not the manifest string pool.
            with zipfile.ZipFile(universal) as z:
                arsc = z.read("resources.arsc")
            renamed = axml.rename_arsc_value(arsc, {"Spotify": args.label})
            if renamed != arsc:
                replace["resources.arsc"] = renamed
                log(f"renamed app label 'Spotify' -> '{args.label}' in resources.arsc")
            else:
                log("label 'Spotify' not found in resources.arsc; skipping rename")

        patched = work / "patched.apk"
        repack(universal, patched, replace, additions)
        align_and_sign(patched, out, ROOT / "pancakeify-debug.keystore")
        log("DONE ->", out)
        log("install:  adb install -r", out)
    finally:
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
