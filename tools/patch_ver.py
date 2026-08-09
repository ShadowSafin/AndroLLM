"""Patch sherpa-onnx .so files: make the OrtGetApiBase reference unversioned.

sherpa-onnx 1.13.4 JNI references OrtGetApiBase@VERS_1.27.0. ONNX Runtime
1.28.0 defines only VERS_1.28.0, so bionic cannot bind the versioned
reference -> UnsatisfiedLinkError. Changing the .gnu.version index of that
one symbol from 2 (VERS_1.27.0) to 1 (VER_NDX_GLOBAL / unversioned) makes it
bind to the default-versioned OrtGetApiBase@@VERS_1.28.0 exported by ORT
1.28.0. Every other ORT call goes through the OrtApi struct, so this single
symbol is the only versioned reference (verified with readelf).

Usage: python patch_ver.py <lib.so>...
"""
import struct
import sys

SHT_DYNSYM = 11
SHT_STRTAB = 3
SHT_GNU_VERSYM = 0x6FFFFFFF

ELF64_HDR = struct.Struct("<16sHHIQQQIHHHHHH")  # e_ident..e_shstrndx


def parse(path):
    data = bytearray(open(path, "rb").read())
    _, e_type, e_machine, e_version, e_entry, e_phoff, e_shoff, e_flags, \
        e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = \
        ELF64_HDR.unpack_from(data, 0)
    assert e_type == 3, f"{path}: not ET_DYN"

    shdrs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, \
            sh_info, sh_addralign, sh_entsize = struct.unpack_from("<IIQQQQIIQQ", data, off)
        shdrs.append(dict(name=name, type=sh_type, offset=sh_offset, size=sh_size,
                          link=sh_link, entsize=sh_entsize))

    shstr = shdrs[e_shstrndx]
    def sec_name(i):
        off = shstr["offset"] + shdrs[i]["name"]
        end = data.index(b"\0", off)
        return data[off:end].decode()

    dynsym = next((s for i, s in enumerate(shdrs) if s["type"] == SHT_DYNSYM
                   and sec_name(i) == ".dynsym"), None)
    dynstr = next((s for i, s in enumerate(shdrs) if s["type"] == SHT_STRTAB
                   and sec_name(i) == ".dynstr"), None)
    versym = next((s for i, s in enumerate(shdrs) if s["type"] == SHT_GNU_VERSYM), None)
    assert dynsym and dynstr and versym, f"{path}: missing dynsym/dynstr/versym"

    n = dynsym["size"] // 24
    patched = 0
    for i in range(n):
        off = dynsym["offset"] + i * 24
        st_name, st_info, st_other, st_shndx, st_value, st_size = \
            struct.unpack_from("<IBBHQQ", data, off)
        if st_shndx != 0:  # not undefined
            continue
        end = data.index(b"\0", dynstr["offset"] + st_name)
        name = data[dynstr["offset"] + st_name:end]
        if name == b"OrtGetApiBase":
            v_off = versym["offset"] + i * 2
            idx = struct.unpack_from("<H", data, v_off)[0]
            print(f"  {path}: OrtGetApiBase sym#{i} versym={idx} -> 1 (unversioned)")
            struct.pack_into("<H", data, v_off, 1)
            patched += 1
    if patched == 0:
        print(f"  {path}: no OrtGetApiBase reference (skipped)")
    open(path, "wb").write(bytes(data))
    return patched


if __name__ == "__main__":
    total = 0
    for p in sys.argv[1:]:
        total += parse(p)
    print(f"patched {total} reference(s)")
