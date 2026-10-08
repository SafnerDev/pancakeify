#!/usr/bin/env python3
"""Fast dev cycle (seconds, not minutes): swap the rebuilt stable dex into the already
patched dist APK, re-align, re-sign, install.  Use after `bash stable/build_stable.sh`.
The full patcher is only needed when the injected smali call / patched dex changes.

  python3 tools/dev_deploy.py [--no-install]
Env: ANDROID_HOME (default /mnt/data/Android/sdk), java on PATH.
"""
import glob, os, re, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", "/mnt/data/Android/sdk"))
BT = sorted(glob.glob(str(SDK / "build-tools" / "*")), key=lambda p: [int(x) for x in re.findall(r"\d+", p)])[-1]
SRC = ROOT / "dist" / "pancakeify-spotify.apk"
STABLE = ROOT / "tools" / "pancake-stable.dex"
OUT = ROOT / "work" / "dev.apk"
ALIGNED = ROOT / "work" / "dev-aligned.apk"


def align(src, dst, align_=4, so_align=4096):
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


def main():
    with zipfile.ZipFile(SRC) as z:
        names = z.namelist()
        # the stable dex = the highest classesN.dex (patcher appends it last)
        slots = sorted((int(re.match(r"classes(\d*)\.dex", n).group(1) or 1), n)
                       for n in names if re.match(r"classes\d*\.dex$", n))
        stable_name = slots[-1][1]
        print("replacing", stable_name)
        with zipfile.ZipFile(OUT, "w") as zout:
            for i in z.infolist():
                if i.filename.startswith("META-INF/") and re.search(r"\.(SF|RSA|DSA|EC)$|MANIFEST\.MF", i.filename):
                    continue          # drop old signature
                data = STABLE.read_bytes() if i.filename == stable_name else z.read(i.filename)
                zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
                zi.compress_type = i.compress_type
                zi.external_attr = i.external_attr
                zout.writestr(zi, data)
            # extra assets of the stable layer (lyrics font); same path the full patcher uses
            for arc, src in (("assets/pancake/Inter.ttf", ROOT / "stable" / "assets" / "Inter.ttf"),):
                if arc not in names and src.exists():
                    zi = zipfile.ZipInfo(arc, date_time=(2026, 10, 8, 0, 0, 0))
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    zout.writestr(zi, src.read_bytes())
    align(OUT, ALIGNED)
    subprocess.check_call(["java", "-jar", f"{BT}/lib/apksigner.jar", "sign", "--ks",
                           str(ROOT / "pancakeify-debug.keystore"), "--ks-pass", "pass:pancake",
                           "--key-pass", "pass:pancake", str(ALIGNED)])
    print("signed ->", ALIGNED)
    if "--no-install" not in sys.argv:
        subprocess.check_call(["adb", "install", "-r", str(ALIGNED)])


main()
