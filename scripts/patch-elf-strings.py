#!/usr/bin/env python3
"""Rewrites DT_RUNPATH/DT_RPATH, DT_NEEDED and DT_SONAME strings of an ELF file in place.

Unlike patchelf, which appends any changed string to .dynstr and therefore moves sections into a
new PT_LOAD segment at the end of the file (a layout Android's dynamic linker is known to be picky
about), this only overwrites the existing bytes: every new string must be no longer than the one
it replaces, and the rest of the old string is filled with NUL bytes. Nothing else in the file
moves.

Refuses to patch a string that any other part of the file still references (another dynamic
entry, a dynamic symbol name or a symbol version entry pointing into its bytes - linkers merge
string tails), since that reference would silently change too.

Usage:
  patch-elf-strings.py FILE [--runpath NEW] [--replace-needed OLD NEW] [--soname NEW]
"""
import argparse
import struct
import sys

DT_NULL, DT_NEEDED, DT_STRTAB, DT_SONAME, DT_RPATH, DT_RUNPATH = 0, 1, 5, 14, 15, 29
SHT_DYNSYM, SHT_DYNAMIC = 11, 6
SHT_GNU_VERDEF, SHT_GNU_VERNEED = 0x6FFFFFFD, 0x6FFFFFFE


class Elf:
    def __init__(self, data):
        if data[:4] != b"\x7fELF":
            raise SystemExit("not an ELF file")
        self.data = data
        self.is64 = data[4] == 2
        self.e = "<" if data[5] == 1 else ">"
        if self.is64:
            shoff = self.unpack("Q", 0x28)[0]
            shentsize, shnum, shstrndx = self.unpack("HHH", 0x3A)
            fmt = "IIQQQQIIQQ"
        else:
            shoff = self.unpack("I", 0x20)[0]
            shentsize, shnum, shstrndx = self.unpack("HHH", 0x2E)
            fmt = "IIIIIIIIII"
        self.sections = []
        for i in range(shnum):
            name, type_, _flags, _addr, offset, size, link, _info, _align, entsize = self.unpack(fmt, shoff + i * shentsize)
            self.sections.append({"name": name, "type": type_, "offset": offset, "size": size, "link": link, "entsize": entsize})
        names = self.sections[shstrndx]
        for section in self.sections:
            section["name"] = self.cstring(names["offset"] + section["name"])

    def unpack(self, fmt, offset):
        return struct.unpack_from(self.e + fmt, self.data, offset)

    def cstring(self, offset):
        return self.data[offset:self.data.index(b"\0", offset)].decode()

    def section(self, type_):
        found = [s for s in self.sections if s["type"] == type_]
        return found[0] if found else None

    def dynamic_entries(self):
        """(tag, value) of every .dynamic entry, up to DT_NULL."""
        dynamic = self.section(SHT_DYNAMIC)
        if dynamic is None:
            raise SystemExit("no .dynamic section")
        size = 16 if self.is64 else 8
        entries = []
        for offset in range(dynamic["offset"], dynamic["offset"] + dynamic["size"], size):
            tag, value = self.unpack("qQ" if self.is64 else "iI", offset)
            if tag == DT_NULL:
                break
            entries.append((tag, value))
        return entries

    def dynstr(self):
        return self.sections[self.section(SHT_DYNAMIC)["link"]]

    def referenced_offsets(self):
        """Every .dynstr offset referenced from anywhere we know of, as (offset, description)."""
        refs = []
        for tag, value in self.dynamic_entries():
            if tag in (DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH):
                refs.append((value, f"dynamic tag {tag}"))
        dynsym = self.section(SHT_DYNSYM)
        if dynsym is not None:
            entsize = dynsym["entsize"] or (24 if self.is64 else 16)
            for offset in range(dynsym["offset"], dynsym["offset"] + dynsym["size"], entsize):
                refs.append((self.unpack("I", offset)[0], "dynamic symbol name"))
        verneed = self.section(SHT_GNU_VERNEED)
        if verneed is not None:
            offset = verneed["offset"]
            while True:
                _version, count, file_, aux, next_ = self.unpack("HHIII", offset)
                refs.append((file_, "version need file"))
                aux_offset = offset + aux
                for _ in range(count):
                    _hash, _flags, _other, name, aux_next = self.unpack("IHHII", aux_offset)
                    refs.append((name, "version need name"))
                    aux_offset += aux_next
                if not next_:
                    break
                offset += next_
        verdef = self.section(SHT_GNU_VERDEF)
        if verdef is not None:
            offset = verdef["offset"]
            while True:
                _version, _flags, _ndx, count, _hash, aux, next_ = self.unpack("HHHHIII", offset)
                aux_offset = offset + aux
                for _ in range(count):
                    name, aux_next = self.unpack("II", aux_offset)
                    refs.append((name, "version definition name"))
                    aux_offset += aux_next
                if not next_:
                    break
                offset += next_
        return refs


def patch(path, runpath=None, replace_needed=(), soname=None):
    with open(path, "rb") as f:
        elf = Elf(bytearray(f.read()))
    strtab = elf.dynstr()
    refs = elf.referenced_offsets()
    changes = []  # (dynstr offset, old, new)

    for tag, value in elf.dynamic_entries():
        if tag not in (DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH):
            continue
        old = elf.cstring(strtab["offset"] + value)
        new = None
        if tag in (DT_RPATH, DT_RUNPATH) and runpath is not None:
            new = runpath
        elif tag == DT_NEEDED and old in dict(replace_needed):
            new = dict(replace_needed)[old]
        elif tag == DT_SONAME and soname is not None:
            new = soname
        if new is not None and new != old:
            changes.append((value, old, new))

    if runpath is not None and not any(tag in (DT_RPATH, DT_RUNPATH) for tag, _ in elf.dynamic_entries()):
        raise SystemExit(f"{path}: no DT_RUNPATH/DT_RPATH to rewrite in place")

    for value, old, new in changes:
        old_bytes, new_bytes = old.encode(), new.encode()
        if len(new_bytes) > len(old_bytes):
            raise SystemExit(f"{path}: '{new}' is longer than '{old}', cannot rewrite in place")
        for ref, what in refs:
            inside = value < ref <= value + len(old_bytes)
            # A string starting earlier that runs into this one (no NUL in between) shares its tail.
            sharing_tail = ref < value and elf.data.index(b"\0", strtab["offset"] + ref) >= strtab["offset"] + value
            if inside or sharing_tail:
                raise SystemExit(f"{path}: a {what} shares bytes with '{old}', not rewriting it")
        start = strtab["offset"] + value
        elf.data[start:start + len(old_bytes)] = new_bytes + b"\0" * (len(old_bytes) - len(new_bytes))
        print(f"{path}: '{old}' -> '{new}'")

    with open(path, "wb") as f:
        f.write(elf.data)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file")
    parser.add_argument("--runpath")
    parser.add_argument("--replace-needed", nargs=2, action="append", default=[], metavar=("OLD", "NEW"))
    parser.add_argument("--soname")
    args = parser.parse_args()
    patch(args.file, args.runpath, [tuple(pair) for pair in args.replace_needed], args.soname)


if __name__ == "__main__":
    sys.exit(main())
