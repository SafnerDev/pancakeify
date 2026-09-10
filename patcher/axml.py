"""
Minimal binary AndroidManifest (AXML) string-pool editor.

Just enough to rename strings in the pool (e.g. the app label "Spotify" -> "Pancakeify")
without a full apktool/resource round-trip. Downstream XML chunks reference strings by
*index*, not byte offset, so rewriting the pool's internal offsets is safe as long as we fix
the pool and outer chunk sizes. Supports UTF-16 pools (Spotify's manifest); UTF-8 pools raise.
"""
from __future__ import annotations
import struct


def _read_pool(d: bytes, o: int):
    ptyp, phs, psize = struct.unpack_from("<HHI", d, o)
    scount, stycount, flags, strStart, styStart = struct.unpack_from("<IIIII", d, o + 8)
    if flags & 0x100:
        raise NotImplementedError("UTF-8 string pool not supported")
    if stycount:
        raise NotImplementedError("string styles present; not supported")
    offs = [struct.unpack_from("<I", d, o + phs + 4 * i)[0] for i in range(scount)]
    base = o + strStart
    strings = []
    for off in offs:
        p = base + off
        ln = struct.unpack_from("<H", d, p)[0]
        strings.append(d[p + 2:p + 2 + ln * 2].decode("utf-16-le"))
    return dict(typ=ptyp, hs=phs, size=psize, scount=scount, strStart=strStart), strings


def _build_pool(hdr, strings: list[str]) -> bytes:
    scount = len(strings)
    hs = 28
    strStart = hs + 4 * scount
    data = bytearray()
    offs = []
    for s in strings:
        offs.append(len(data))
        u = s.encode("utf-16-le")
        data += struct.pack("<H", len(s)) + u + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    size = strStart + len(data)
    out = bytearray()
    out += struct.pack("<HHI", 0x0001, hs, size)
    out += struct.pack("<IIIII", scount, 0, 0, strStart, 0)
    for off in offs:
        out += struct.pack("<I", off)
    out += data
    assert len(out) == size, (len(out), size)
    return bytes(out)


def _enc_len_utf8(n: int) -> bytes:
    return bytes([n]) if n < 0x80 else bytes([(n >> 8) | 0x80, n & 0xFF])


def rename_arsc_value(arsc: bytes, mapping: dict[str, str]) -> bytes:
    """Replace strings in a resources.arsc global value pool (UTF-8), preserving styles.

    The app label is a resource reference, so renaming the app means editing this pool, not
    the manifest. Only string *data* is rebuilt; the style offset table and style data are
    kept byte-identical (spans reference string indices, which don't change)."""
    d = bytearray(arsc)
    ttyp, ths, tsize, pkgcount = struct.unpack_from("<HHII", d, 0)
    o = ths  # global value string pool follows the RES_TABLE header
    ptyp, phs, psize = struct.unpack_from("<HHI", d, o)
    scount, stycount, flags, strStart, styStart = struct.unpack_from("<IIIII", d, o + 8)
    if not (flags & 0x100):
        raise NotImplementedError("expected UTF-8 arsc value pool")

    str_off = [struct.unpack_from("<I", d, o + phs + 4 * i)[0] for i in range(scount)]
    sty_off_table = bytes(d[o + phs + 4 * scount: o + phs + 4 * scount + 4 * stycount])
    sbase = o + strStart

    def read_utf8(off):
        p = sbase + off
        c = d[p]; p += 1
        if c & 0x80: c = ((c & 0x7F) << 8) | d[p]; p += 1
        b = d[p]; p += 1
        if b & 0x80: b = ((b & 0x7F) << 8) | d[p]; p += 1
        return d[p:p + b].decode("utf-8")

    strings = [read_utf8(off) for off in str_off]
    changed = False
    for i, s in enumerate(strings):
        if s in mapping:
            strings[i] = mapping[s]; changed = True
    if not changed:
        return bytes(d)

    sty_data = bytes(d[o + styStart: o + psize]) if stycount else b""

    # rebuild string data
    data = bytearray(); new_off = []
    for s in strings:
        new_off.append(len(data))
        u = s.encode("utf-8")
        data += _enc_len_utf8(len(s.encode("utf-16-le")) // 2) + _enc_len_utf8(len(u)) + u + b"\x00"
    while len(data) % 4:
        data += b"\x00"

    new_strStart = phs + 4 * scount + 4 * stycount
    new_styStart = (new_strStart + len(data)) if stycount else 0
    new_size = new_strStart + len(data) + len(sty_data)

    pool = bytearray()
    pool += struct.pack("<HHI", ptyp, phs, new_size)
    pool += struct.pack("<IIIII", scount, stycount, flags, new_strStart, new_styStart)
    for off in new_off:
        pool += struct.pack("<I", off)
    pool += sty_off_table
    pool += data
    pool += sty_data
    assert len(pool) == new_size, (len(pool), new_size)

    newfile = bytearray(d[:o]) + pool + d[o + psize:]
    struct.pack_into("<I", newfile, 4, len(newfile))  # fix RES_TABLE total size
    return bytes(newfile)


def rename_strings(axml: bytes, mapping: dict[str, str]) -> bytes:
    """Return a new AXML with pool strings replaced per `mapping` (exact match)."""
    d = bytearray(axml)
    otyp, ohs, osize = struct.unpack_from("<HHI", d, 0)
    o = 8  # string pool follows the 8-byte XML header
    hdr, strings = _read_pool(d, o)
    changed = False
    for i, s in enumerate(strings):
        if s in mapping:
            strings[i] = mapping[s]
            changed = True
    if not changed:
        return bytes(d)
    new_pool = _build_pool(hdr, strings)
    rest = d[o + hdr["size"]:]
    newfile = d[0:8] + new_pool + rest
    newfile = bytearray(newfile)
    struct.pack_into("<I", newfile, 4, len(newfile))  # fix outer chunk size
    return bytes(newfile)
