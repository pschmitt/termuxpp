#!/usr/bin/env python3
"""Build an AArch64 Termux bootstrap archive from the upstream bootstrap plus extra packages.

The result is a bootstrap-aarch64.zip in the exact format TermuxInstaller consumes:
paths relative to $PREFIX, a SYMLINKS.txt with "target<-arrow->path" lines, and the dpkg
database (var/lib/dpkg/{status,info/*}) kept consistent for every package that is added,
upgraded or replaced.

Subcommands:
  fetch    resolve and download .deb files (and a lock file) from a Termux apt repository
  build    merge the upstream bootstrap with .deb files, a prepared prefix and/or nixpp
  verify   check an archive (symlinks, dpkg metadata, md5sums, dependencies)

Only the Python standard library is used. Nothing is built here: packages come from prebuilt
.deb files (for example from the Termux repository) or from a prepared package prefix.
"""

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import stat
import sys
import tarfile
import urllib.request
import zipfile

PREFIX_ABS = "/data/data/com.termux/files/usr"
PREFIX_DATA = PREFIX_ABS.lstrip("/")  # as found in deb data.tar and md5sums
ARROW = "←"  # separator used by SYMLINKS.txt
ARCH = "aarch64"

# Upstream bootstrap pinned by app/build.gradle (apt-android-7 variant, aarch64). Keep in sync.
UPSTREAM_URL = (
    "https://github.com/termux/termux-packages/releases/download/"
    "bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
)
UPSTREAM_SHA256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"

DEFAULT_REPO = "https://packages.termux.dev/apt/termux-main"
SETUP_CONFIG = "etc/nixpp/setup.conf"
DEFAULT_REQUIRED = ["openssh", "curl", "ca-certificates", "gzip", "xz-utils"]
ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 183
FIXED_DATE = (2026, 1, 1, 0, 0, 0)


class BootstrapError(Exception):
    pass


# --------------------------------------------------------------------------- helpers


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def log(msg):
    print(msg, file=sys.stderr)


def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "termuxpp-bootstrap/1"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def safe_relpath(name):
    """Normalise an archive-relative path and refuse anything that escapes the prefix."""
    name = name.replace("\\", "/")
    while name.startswith("./"):
        name = name[2:]
    name = name.rstrip("/")
    if name in ("", "."):
        return ""
    parts = name.split("/")
    if name.startswith("/") or ".." in parts:
        raise BootstrapError("unsafe path: %r" % name)
    return "/".join(p for p in parts if p not in ("", "."))


def is_aarch64_elf(data):
    if data[:4] != ELF_MAGIC:
        return None
    if len(data) < 20:
        return False
    return data[5] == 1 and int.from_bytes(data[18:20], "little") == EM_AARCH64


def parse_stanzas(text):
    """Parse dpkg/apt control text into a list of ordered [(field, value)] stanzas."""
    stanzas, cur = [], []
    for line in text.split("\n"):
        if line.strip() == "":
            if cur:
                stanzas.append(cur)
                cur = []
        elif line[0] in " \t" and cur:
            cur[-1] = (cur[-1][0], cur[-1][1] + "\n" + line)
        else:
            key, _, value = line.partition(":")
            cur.append((key, value.strip()))
    if cur:
        stanzas.append(cur)
    return stanzas


def stanza_get(stanza, key, default=None):
    for k, v in stanza:
        if k.lower() == key.lower():
            return v
    return default


def format_stanza(stanza):
    out = []
    for k, v in stanza:
        out.append("%s: %s" % (k, v))
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- dpkg version compare


def _order(c):
    if c == "~":
        return -1
    if c.isdigit():
        return 0
    if c.isalpha():
        return ord(c)
    return ord(c) + 256


def _verrevcmp(a, b):
    i = j = 0
    while i < len(a) or j < len(b):
        first_diff = 0
        while (i < len(a) and not a[i].isdigit()) or (j < len(b) and not b[j].isdigit()):
            ac = _order(a[i]) if i < len(a) else 0
            bc = _order(b[j]) if j < len(b) else 0
            if ac != bc:
                return ac - bc
            i += 1
            j += 1
        while i < len(a) and a[i] == "0":
            i += 1
        while j < len(b) and b[j] == "0":
            j += 1
        while i < len(a) and a[i].isdigit() and j < len(b) and b[j].isdigit():
            if not first_diff:
                first_diff = ord(a[i]) - ord(b[j])
            i += 1
            j += 1
        if i < len(a) and a[i].isdigit():
            return 1
        if j < len(b) and b[j].isdigit():
            return -1
        if first_diff:
            return first_diff
    return 0


def _split_version(v):
    epoch = 0
    if ":" in v:
        e, _, v = v.partition(":")
        epoch = int(e)
    rev = ""
    if "-" in v:
        v, _, rev = v.rpartition("-")
    return epoch, v, rev


def version_cmp(a, b):
    ea, va, ra = _split_version(a)
    eb, vb, rb = _split_version(b)
    if ea != eb:
        return -1 if ea < eb else 1
    for x, y in ((va, vb), (ra, rb)):
        r = _verrevcmp(x, y)
        if r:
            return -1 if r < 0 else 1
    return 0


def version_satisfies(have, op, want):
    if not op:
        return True
    c = version_cmp(have, want)
    return {
        "<<": c < 0, "<": c <= 0, "<=": c <= 0, "=": c == 0,
        ">=": c >= 0, ">": c >= 0, ">>": c > 0,
    }[op]


def parse_depends(value):
    """'a (>= 1), b | c' -> [[(name, op, ver), ...], ...]"""
    groups = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        alts = []
        for alt in part.split("|"):
            m = re.match(r"^\s*([^\s(\[<]+)(?:\s*\(\s*(<<|<=|=|>=|>>|<|>)\s*([^)\s]+)\s*\))?", alt)
            if not m:
                raise BootstrapError("cannot parse dependency %r" % alt)
            alts.append((m.group(1).split(":")[0], m.group(2), m.group(3)))
        groups.append(alts)
    return groups


# ------------------------------------------------------------------------ the tree


class Tree:
    """In-memory model of a bootstrap archive."""

    def __init__(self):
        self.files = {}     # relpath -> (bytes, mode)
        self.dirs = set()
        self.symlinks = {}  # relpath -> target (insertion ordered)
        self.dates = {}     # relpath -> zip date_time
        self.date_default = FIXED_DATE

    # reading ------------------------------------------------------------
    @classmethod
    def from_zip(cls, path):
        t = cls()
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if len(names) != len(set(names)):
                raise BootstrapError("duplicate entries in %s" % path)
            if "SYMLINKS.txt" not in names:
                raise BootstrapError("%s has no SYMLINKS.txt" % path)
            for info in z.infolist():
                data = z.read(info)
                if info.filename == "SYMLINKS.txt":
                    t.date_default = info.date_time
                    t.add_symlinks_text(data.decode("utf-8"))
                    continue
                rel = safe_relpath(info.filename)
                if info.is_dir():
                    t.dirs.add(rel)
                else:
                    mode = (info.external_attr >> 16) & 0o7777 or 0o600
                    t.files[rel] = (data, mode)
                t.dates[rel] = info.date_time
        return t

    def add_symlinks_text(self, text):
        for line in text.split("\n"):
            if not line:
                continue
            parts = line.split(ARROW)
            if len(parts) != 2:
                raise BootstrapError("malformed SYMLINKS.txt line: %r" % line)
            target, link = parts
            rel = safe_relpath(link)
            if not rel:
                raise BootstrapError("malformed SYMLINKS.txt line: %r" % line)
            self.symlinks[rel] = target

    # modification --------------------------------------------------------
    def _ensure_parents(self, rel):
        parts = rel.split("/")[:-1]
        for i in range(1, len(parts) + 1):
            self.dirs.add("/".join(parts[:i]))

    def exists(self, rel):
        return rel in self.files or rel in self.symlinks or rel in self.dirs

    def remove(self, rel):
        self.files.pop(rel, None)
        self.symlinks.pop(rel, None)
        self.dates.pop(rel, None)

    def add_file(self, rel, data, mode):
        self.remove(rel)
        self._ensure_parents(rel)
        self.files[rel] = (data, mode)

    def add_symlink(self, rel, target):
        self.remove(rel)
        self._ensure_parents(rel)
        self.symlinks[rel] = target

    def add_dir(self, rel):
        if rel:
            self._ensure_parents(rel + "/x")

    # dpkg database ---------------------------------------------------------
    def status_stanzas(self):
        data = self.files.get("var/lib/dpkg/status", (b"", 0))[0]
        return parse_stanzas(data.decode("utf-8"))

    def set_status_stanzas(self, stanzas):
        text = "\n".join(format_stanza(s) for s in stanzas)
        if stanzas:
            text += "\n"
        mode = self.files.get("var/lib/dpkg/status", (b"", 0o600))[1]
        self.add_file("var/lib/dpkg/status", text.encode("utf-8"), mode)

    def installed(self):
        """name -> stanza for packages that are fully installed."""
        out = {}
        for s in self.status_stanzas():
            name = stanza_get(s, "Package")
            if name and "installed" in (stanza_get(s, "Status", "") or "").split()[-1:]:
                out[name] = s
        return out

    def package_files(self, name):
        data = self.files.get("var/lib/dpkg/info/%s.list" % name)
        if data is None:
            return []
        out = []
        for line in data[0].decode("utf-8").split("\n"):
            if line.startswith(PREFIX_ABS + "/"):
                rel = safe_relpath(line[len(PREFIX_ABS) + 1:])
                if rel:
                    out.append(rel)
        return out

    def owners(self):
        owners = {}
        for rel in list(self.files):
            m = re.match(r"^var/lib/dpkg/info/(.+)\.list$", rel)
            if m:
                for f in self.package_files(m.group(1)):
                    owners.setdefault(f, set()).add(m.group(1))
        return owners

    # writing -----------------------------------------------------------
    def write_zip(self, path):
        lines = "".join("%s%s./%s\n" % (t, ARROW, p) for p, t in self.symlinks.items())
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            def put(name, data, mode, is_dir=False):
                zi = zipfile.ZipInfo(name, self.dates.get(name.rstrip("/"), self.date_default))
                zi.create_system = 3
                if is_dir:
                    zi.external_attr = ((stat.S_IFDIR | mode) << 16) | 0x10
                    zi.compress_type = zipfile.ZIP_STORED
                    z.writestr(zi, b"")
                else:
                    zi.external_attr = (stat.S_IFREG | mode) << 16
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(zi, data, compresslevel=9)

            put("SYMLINKS.txt", lines.encode("utf-8"), 0o600)
            for d in sorted(self.dirs):
                put(d + "/", b"", 0o700, True)
            for f in sorted(self.files):
                data, mode = self.files[f]
                put(f, data, mode)
        with open(path, "wb") as f:
            f.write(out.getvalue())


# -------------------------------------------------------------------- deb handling


def parse_ar(data):
    if data[:8] != b"!<arch>\n":
        raise BootstrapError("not an ar archive / .deb")
    pos, members = 8, {}
    while pos + 60 <= len(data):
        hdr = data[pos:pos + 60]
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        members[name] = body
        pos += 60 + size + (size & 1)
    return members


def open_tar(name, body):
    if name.endswith(".xz"):
        body = lzma.decompress(body)
    elif name.endswith(".gz"):
        body = gzip.decompress(body)
    elif name.endswith(".zst"):
        try:
            from compression import zstd  # Python 3.14+
        except ImportError:
            raise BootstrapError("%s is zstd-compressed; Python 3.14+ is required" % name)
        body = zstd.decompress(body)
    elif not name.endswith(".tar"):
        raise BootstrapError("unsupported deb member %s" % name)
    return tarfile.open(fileobj=io.BytesIO(body))


class Deb:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            raw = f.read()
        self.sha256 = sha256_bytes(raw)
        members = parse_ar(raw)
        ctl = next((n for n in members if n.startswith("control.tar")), None)
        dat = next((n for n in members if n.startswith("data.tar")), None)
        if not ctl or not dat:
            raise BootstrapError("%s: missing control.tar/data.tar" % path)
        self.control = {}
        with open_tar(ctl, members[ctl]) as t:
            for m in t.getmembers():
                if m.isfile():
                    self.control[safe_relpath(m.name)] = (t.extractfile(m).read(), m.mode & 0o7777)
        if "control" not in self.control:
            raise BootstrapError("%s: no control file" % path)
        stanzas = parse_stanzas(self.control["control"][0].decode("utf-8"))
        self.stanza = stanzas[0]
        self.name = stanza_get(self.stanza, "Package")
        self.version = stanza_get(self.stanza, "Version")
        arch = stanza_get(self.stanza, "Architecture")
        if arch not in (ARCH, "all"):
            raise BootstrapError("%s: architecture %s is not %s" % (path, arch, ARCH))
        self.entries = []  # (rel, kind, payload, mode); kind in dir/file/symlink
        with open_tar(dat, members[dat]) as t:
            by_name = {}
            for m in t.getmembers():
                rel = self._map(m.name)
                if rel is None:
                    continue
                if m.isdir():
                    self.entries.append((rel, "dir", None, m.mode & 0o7777))
                elif m.isfile():
                    data = t.extractfile(m).read()
                    by_name[safe_relpath(m.name)] = data
                    self.entries.append((rel, "file", data, m.mode & 0o7777))
                elif m.issym():
                    self.entries.append((rel, "symlink", m.linkname, 0))
                elif m.islnk():
                    src = by_name.get(safe_relpath(m.linkname))
                    if src is None:
                        raise BootstrapError("%s: hardlink to unseen %s" % (path, m.linkname))
                    self.entries.append((rel, "file", src, m.mode & 0o7777))
                else:
                    raise BootstrapError("%s: unsupported member %s" % (path, m.name))

    @staticmethod
    def _map(name):
        n = safe_relpath(name)
        if n == "" or PREFIX_DATA.startswith(n + "/") or n == PREFIX_DATA:
            return None  # the prefix itself or one of its parents
        if not n.startswith(PREFIX_DATA + "/"):
            raise BootstrapError("data member outside %s: %s" % (PREFIX_ABS, name))
        return n[len(PREFIX_DATA) + 1:]


def conffile_status(deb, conffile_text):
    payload = {"/" + PREFIX_DATA + "/" + rel: content
               for rel, kind, content, _mode in deb.entries if kind == "file"}
    status = []
    for line in conffile_text.splitlines():
        path = line.strip()
        if not path:
            continue
        if path not in payload:
            raise BootstrapError(f"{deb.name}: conffile {path} is absent from package data")
        status.append(f" {path} {hashlib.md5(payload[path]).hexdigest()}")
    return status


def _prefix_parents():
    out, acc = [], ""
    for p in PREFIX_DATA.split("/"):
        acc += "/" + p
        out.append(acc + "/")
    return out


def install_deb(tree, deb, upgrade, force_overwrite):
    installed = tree.installed()
    owners = tree.owners()
    name = deb.name
    old = installed.get(name)
    if old is not None:
        oldv = stanza_get(old, "Version")
        if oldv == deb.version:
            log("skip %s %s (already installed)" % (name, deb.version))
            return
        if not upgrade:
            raise BootstrapError(
                "%s %s is already in the bootstrap; pass --upgrade to replace it with %s"
                % (name, oldv, deb.version))
        log("upgrade %s %s -> %s" % (name, oldv, deb.version))
        remove_package(tree, name)
        owners = tree.owners()
    else:
        log("install %s %s" % (name, deb.version))

    for rel, kind, payload, mode in deb.entries:
        if kind == "dir":
            tree.add_dir(rel)
            continue
        other = owners.get(rel, set()) - {name}
        if (other or (tree.exists(rel) and rel not in tree.dirs and rel not in owners)) \
                and not force_overwrite:
            raise BootstrapError("%s: %s conflicts with %s" % (
                name, rel, ", ".join(sorted(other)) or "an unowned file in the bootstrap"))
        if rel in tree.dirs:
            raise BootstrapError("%s: %s is a directory in the bootstrap" % (name, rel))
        for o in other:
            disown(tree, o, rel)
        if kind == "file":
            if is_aarch64_elf(payload) is False:
                raise BootstrapError("%s: %s is an ELF that is not AArch64" % (name, rel))
            tree.add_file(rel, payload, mode or 0o600)
        else:
            tree.add_symlink(rel, payload)

    # dpkg database, in the form dpkg itself writes it
    info = "var/lib/dpkg/info/"
    # Same layout as the upstream bootstrap: directories carry a trailing slash.
    list_lines = ["/."] + _prefix_parents()
    for rel, kind, _, _ in sorted(deb.entries):
        list_lines.append("/%s/%s%s" % (PREFIX_DATA, rel, "/" if kind == "dir" else ""))
    tree.add_file(info + name + ".list", ("\n".join(list_lines) + "\n").encode(), 0o600)
    conffile_text = ""
    for cname, (data, cmode) in deb.control.items():
        if cname in ("control", "md5sums", "conffiles") or "/" in cname:
            if cname == "md5sums":
                tree.add_file(info + name + ".md5sums", data, 0o600)
            elif cname == "conffiles":
                conffile_text = data.decode("utf-8")
                tree.add_file(info + name + ".conffiles", data, 0o600)
            continue
        tree.add_file(info + name + "." + cname, data, cmode or 0o700)

    stanza = [(k, v) for k, v in deb.stanza if k.lower() not in ("status", "conffiles", "filename",
                                                                   "size", "md5sum", "sha1", "sha256", "sha512")]
    cfiles = conffile_status(deb, conffile_text)
    if cfiles:
        stanza.append(("Conffiles", "\n" + "\n".join(cfiles)))
    stanza.append(("Status", "install ok installed"))
    stanzas = [s for s in tree.status_stanzas() if stanza_get(s, "Package") != name]
    stanzas.append(stanza)
    tree.set_status_stanzas(stanzas)


def disown(tree, name, rel):
    """Drop rel from the file list and md5sums of package name (it is being overwritten)."""
    ab = "/%s/%s" % (PREFIX_DATA, rel)
    for suffix, match in (("list", lambda l: l == ab), ("md5sums", lambda l: l.endswith("  %s/%s" % (PREFIX_DATA, rel)))):
        key = "var/lib/dpkg/info/%s.%s" % (name, suffix)
        if key in tree.files:
            data, mode = tree.files[key]
            lines = [l for l in data.decode("utf-8").split("\n") if not match(l)]
            tree.files[key] = ("\n".join(lines).encode("utf-8"), mode)


def remove_package(tree, name):
    owners = tree.owners()
    for rel in tree.package_files(name):
        if rel in tree.dirs or owners.get(rel, set()) - {name}:
            continue
        tree.remove(rel)
    for rel in [r for r in tree.files if re.match(r"^var/lib/dpkg/info/%s\.[^/]+$" % re.escape(name), r)]:
        tree.remove(rel)
    tree.set_status_stanzas([s for s in tree.status_stanzas() if stanza_get(s, "Package") != name])


# ------------------------------------------------------------ prepared prefix, nixpp


def overlay_prefix(tree, root):
    """Merge a prepared package prefix (a tree laid out like $PREFIX) into the bootstrap.

    var/lib/dpkg/status is merged stanza-by-stanza instead of replaced; info files may replace
    those of packages whose stanza the overlay carries. Everything else must not clobber files
    owned by a package that stays untouched.
    """
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        raise BootstrapError("prepared prefix %s is not a directory" % root)
    overlay_status = []
    pkgs = set()
    status_path = os.path.join(root, "var/lib/dpkg/status")
    if os.path.isfile(status_path):
        with open(status_path, encoding="utf-8") as f:
            overlay_status = parse_stanzas(f.read())
        pkgs = {stanza_get(s, "Package") for s in overlay_status}
    owners = tree.owners()
    for name in pkgs:
        if name in tree.installed():
            remove_package(tree, name)
    owners = tree.owners()
    count = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for dname in list(dirnames):
            full = os.path.join(dirpath, dname)
            rel = safe_relpath(os.path.relpath(full, root))
            if os.path.islink(full):
                dirnames.remove(dname)
                tree.add_symlink(rel, os.readlink(full))
                count += 1
            else:
                tree.add_dir(rel)
        for fname in sorted(filenames):
            full = os.path.join(dirpath, fname)
            rel = safe_relpath(os.path.relpath(full, root))
            if rel == "var/lib/dpkg/status":
                continue
            if rel == "SYMLINKS.txt":
                with open(full, encoding="utf-8") as f:
                    tree.add_symlinks_text(f.read())
                continue
            if os.path.islink(full):
                tree.add_symlink(rel, os.readlink(full))
                count += 1
                continue
            other = owners.get(rel, set()) - pkgs
            if other:
                raise BootstrapError("prepared prefix file %s conflicts with %s" % (rel, ", ".join(sorted(other))))
            with open(full, "rb") as f:
                data = f.read()
            if is_aarch64_elf(data) is False:
                raise BootstrapError("prepared prefix file %s is an ELF that is not AArch64" % rel)
            tree.add_file(rel, data, stat.S_IMODE(os.lstat(full).st_mode) & 0o777 or 0o600)
            count += 1
    if overlay_status:
        existing = tree.status_stanzas()
        tree.set_status_stanzas(existing + overlay_status)
    log("overlaid prepared prefix %s (%d entries, %d package stanzas)" % (root, count, len(overlay_status)))


def install_nixpp(tree, path, version, assets=None, setup_config=None):
    with open(path, "rb") as f:
        data = f.read()
    if is_aarch64_elf(data) is not True:
        raise BootstrapError("%s is not a little-endian AArch64 ELF executable" % path)
    digest = sha256_bytes(data)
    version = version or "0+%s" % digest[:12]
    if "nixpp" in tree.installed():
        remove_package(tree, "nixpp")
    # The enrollment logic is native: nixpp reads the credentials from Bitwarden
    # with the bundled rbw, leases a build worker and activates the system.
    launcher = f"""#!{PREFIX_ABS}/bin/bash
exec nixpp setup "$@"

# vim: set ft=sh et ts=2 sw=2 :
""".encode()
    files = {"bin/nixpp": data, "bin/nixpp-setup": launcher}
    if setup_config:
        # Defaults of `nixpp setup` that stay out of the public source: the rworker
        # controller and its host key, the binary cache and the private inputs.
        try:
            with open(setup_config, "rb") as f:
                config = f.read()
        except OSError as error:
            raise BootstrapError(f"cannot read nixpp setup config {setup_config}: {error}") from error
        if not config.strip():
            raise BootstrapError(f"empty nixpp setup config: {setup_config}")
        files[SETUP_CONFIG] = config
    if assets:
        for name in ("launcher", "manifest.json", "bootstrap-aarch64.zip"):
            source = os.path.join(assets, name)
            try:
                with open(source, "rb") as f:
                    content = f.read()
            except OSError as error:
                raise BootstrapError(f"cannot read nixpp asset {source}: {error}") from error
            if not content:
                raise BootstrapError(f"empty nixpp asset: {source}")
            if name == "launcher" and is_aarch64_elf(content) is not True:
                raise BootstrapError(f"nixpp launcher is not AArch64: {source}")
            files[f"share/nixpp/{name}"] = content
    for relative, content in files.items():
        if tree.exists(relative):
            raise BootstrapError("nixpp cannot overwrite existing " + relative)
        mode = 0o700 if relative.startswith("bin/") or relative == "share/nixpp/launcher" else 0o644
        if relative == SETUP_CONFIG:
            mode = 0o600
        tree.add_file(relative, content, mode)
    checksums = "".join(
        f"{hashlib.md5(content).hexdigest()}  {PREFIX_DATA}/{relative}\n"
        for relative, content in files.items()
    )
    tree.add_file("var/lib/dpkg/info/nixpp.md5sums", checksums.encode(), 0o600)
    listing = ["/."] + _prefix_parents() + [f"/{PREFIX_DATA}/bin/"]
    if assets:
        listing.append(f"/{PREFIX_DATA}/share/nixpp/")
    listing.extend(f"/{PREFIX_DATA}/{relative}" for relative in files)
    tree.add_file("var/lib/dpkg/info/nixpp.list", ("\n".join(listing) + "\n").encode(), 0o600)
    stanza = [
        ("Package", "nixpp"), ("Architecture", ARCH),
        ("Installed-Size", str((sum(map(len, files.values())) + 1023) // 1024)),
        ("Maintainer", "local"), ("Version", version),
        ("Depends", "bash, curl, ca-certificates, gzip, xz-utils, openssh"),
        ("Description", "nixpp client binary installed by build-aarch64-bootstrap.py"),
        ("Status", "install ok installed"),
    ]
    tree.set_status_stanzas(tree.status_stanzas() + [stanza])
    log("installed nixpp %s (sha256 %s)" % (version, digest))


def install_rbw(tree, root, version):
    """Install the Android build of rbw (bin/rbw and bin/rbw-agent of ROOT) as package rbw."""
    files = {}
    for name in ("rbw", "rbw-agent"):
        path = os.path.join(root, "bin", name)
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError as error:
            raise BootstrapError(f"cannot read {path}: {error}") from error
        if is_aarch64_elf(data) is not True:
            raise BootstrapError("%s is not a little-endian AArch64 ELF executable" % path)
        files[f"bin/{name}"] = data
    license_path = os.path.join(root, "share/licenses/rbw/LICENSE")
    if os.path.isfile(license_path):
        with open(license_path, "rb") as f:
            files["share/licenses/rbw/LICENSE"] = f.read()
    version = version or "0+%s" % sha256_bytes(files["bin/rbw"])[:12]
    if "rbw" in tree.installed():
        remove_package(tree, "rbw")
    for relative, content in files.items():
        if tree.exists(relative):
            raise BootstrapError("rbw cannot overwrite existing " + relative)
        tree.add_file(relative, content, 0o700 if relative.startswith("bin/") else 0o644)
    checksums = "".join(
        f"{hashlib.md5(content).hexdigest()}  {PREFIX_DATA}/{relative}\n"
        for relative, content in files.items()
    )
    tree.add_file("var/lib/dpkg/info/rbw.md5sums", checksums.encode(), 0o600)
    listing = ["/."] + _prefix_parents() + [f"/{PREFIX_DATA}/bin/"]
    listing.extend(f"/{PREFIX_DATA}/{relative}" for relative in files)
    tree.add_file("var/lib/dpkg/info/rbw.list", ("\n".join(listing) + "\n").encode(), 0o600)
    stanza = [
        ("Package", "rbw"), ("Architecture", ARCH),
        ("Installed-Size", str((sum(map(len, files.values())) + 1023) // 1024)),
        ("Maintainer", "local"), ("Version", version),
        ("Depends", "ca-certificates"),
        ("Description", "rbw Bitwarden client built from source for TermuxPP"),
        ("Status", "install ok installed"),
    ]
    tree.set_status_stanzas(tree.status_stanzas() + [stanza])
    log("installed rbw %s (sha256 %s)" % (version, sha256_bytes(files["bin/rbw"])))


# -------------------------------------------------------------------------- verify


def check_dependencies(tree):
    errors = []
    installed = tree.installed()
    provided = {}
    for name, s in installed.items():
        provided.setdefault(name, []).append((stanza_get(s, "Version"), name))
        for prov in parse_depends(stanza_get(s, "Provides", "") or ""):
            for pn, _, _ in prov:
                provided.setdefault(pn, []).append((None, name))
    for name, s in installed.items():
        for field in ("Pre-Depends", "Depends"):
            for group in parse_depends(stanza_get(s, field, "") or ""):
                ok = False
                for dn, op, dv in group:
                    for have, _ in provided.get(dn, []):
                        if have is None:
                            ok = ok or not op
                        elif version_satisfies(have, op, dv):
                            ok = True
                if not ok:
                    errors.append("%s: unsatisfied %s: %s" % (name, field, " | ".join(
                        a[0] + (" (%s %s)" % (a[1], a[2]) if a[1] else "") for a in group)))
    return errors


def verify_tree(tree, required=(), check_deps=True):
    errors = []
    for link, target in tree.symlinks.items():
        if link in tree.files:
            errors.append("%s is both a file and a symlink" % link)
    installed = tree.installed()
    for name in required:
        if name not in installed:
            errors.append("required package %s is not installed in the bootstrap" % name)
    for name, s in installed.items():
        if "var/lib/dpkg/info/%s.list" % name not in tree.files:
            errors.append("%s: missing dpkg info/%s.list" % (name, name))
        if stanza_get(s, "Architecture") not in (ARCH, "all"):
            errors.append("%s: architecture %s" % (name, stanza_get(s, "Architecture")))
        for line in (stanza_get(s, "Conffiles") or "").splitlines():
            if line.strip() and not re.fullmatch(r"\s*/.+ [0-9a-f]{32}(?: [a-z-]+)*\s*", line):
                errors.append("%s: malformed Conffiles entry" % name)
        md5 = tree.files.get("var/lib/dpkg/info/%s.md5sums" % name)
        if md5 is None:
            continue
        for line in md5[0].decode("utf-8").split("\n"):
            if not line.strip():
                continue
            h, _, path = line.partition("  ")
            if not path.startswith(PREFIX_DATA + "/"):
                errors.append("%s: md5sums entry outside prefix: %s" % (name, path))
                continue
            rel = path[len(PREFIX_DATA) + 1:]
            if rel in tree.files:
                if hashlib.md5(tree.files[rel][0]).hexdigest() != h:
                    errors.append("%s: md5 mismatch for %s" % (name, rel))
            elif rel not in tree.symlinks:
                errors.append("%s: md5sums lists missing file %s" % (name, rel))
        for rel in tree.package_files(name):
            if not tree.exists(rel):
                errors.append("%s: list entry missing from archive: %s" % (name, rel))
    for rel, (data, mode) in tree.files.items():
        if is_aarch64_elf(data) is False:
            errors.append("%s is an ELF that is not AArch64" % rel)
    if check_deps:
        errors += check_dependencies(tree)
    return errors


# --------------------------------------------------------------------------- fetch


def load_index(repo, suite, component, index_file=None):
    if index_file:
        with open(index_file, "rb") as f:
            raw = f.read()
        if raw[:2] == b"\x1f\x8b":
            raw = gzip.decompress(raw)
    else:
        url = "%s/dists/%s/%s/binary-%s/Packages.gz" % (repo.rstrip("/"), suite, component, ARCH)
        raw = gzip.decompress(http_get(url))
    out = {}
    for s in parse_stanzas(raw.decode("utf-8")):
        name = stanza_get(s, "Package")
        if not name:
            continue
        prev = out.get(name)
        if prev is None or version_cmp(stanza_get(s, "Version"), stanza_get(prev, "Version")) > 0:
            out[name] = s
    return out


def resolve(index, installed, wanted):
    """Return the stanzas to download so that wanted and all their dependencies are satisfied."""
    chosen = {}
    provides = {}
    for name, s in index.items():
        for group in parse_depends(stanza_get(s, "Provides", "") or ""):
            for pn, _, _ in group:
                provides.setdefault(pn, []).append(name)

    def satisfied_by_installed(dn, op, dv):
        s = installed.get(dn)
        return s is not None and version_satisfies(stanza_get(s, "Version"), op, dv)

    def add(name):
        if name in chosen:
            return
        s = index.get(name)
        if s is None:
            raise BootstrapError("package %s not found in the repository index" % name)
        chosen[name] = s
        for field in ("Pre-Depends", "Depends"):
            for group in parse_depends(stanza_get(s, field, "") or ""):
                if any(satisfied_by_installed(*a) or
                       (a[0] in chosen and version_satisfies(stanza_get(chosen[a[0]], "Version"), a[1], a[2]))
                       for a in group):
                    continue
                for dn, op, dv in group:
                    cand = index.get(dn)
                    if cand is None and provides.get(dn):
                        cand = index[provides[dn][0]]
                        dn = stanza_get(cand, "Package")
                    if cand is not None and version_satisfies(stanza_get(cand, "Version"), op, dv):
                        add(dn)
                        break
                else:
                    raise BootstrapError("cannot satisfy dependency of %s: %s" % (name, group))

    for name in wanted:
        if name in installed:
            log("keep bundled %s %s" % (name, stanza_get(installed[name], "Version")))
            continue
        add(name)
    return chosen


def cmd_fetch(args):
    tree = Tree.from_zip(args.base_zip)
    os.makedirs(args.out_dir, exist_ok=True)
    if args.lock_in:
        with open(args.lock_in) as f:
            lock = json.load(f)
        entries = lock["packages"]
        repo = lock["repo"]
    else:
        index = load_index(args.repo, args.suite, args.component, args.index_file)
        chosen = resolve(index, tree.installed(), args.package)
        repo = args.repo
        entries = [{
            "name": n, "version": stanza_get(s, "Version"),
            "filename": stanza_get(s, "Filename"), "sha256": stanza_get(s, "SHA256"),
            "size": int(stanza_get(s, "Size", "0")),
        } for n, s in sorted(chosen.items())]
    for e in entries:
        dest = os.path.join(args.out_dir, os.path.basename(e["filename"]))
        if os.path.isfile(dest) and sha256_file(dest) == e["sha256"]:
            log("cached %s" % dest)
            continue
        url = "%s/%s" % (repo.rstrip("/"), e["filename"])
        log("download %s" % url)
        data = http_get(url)
        if sha256_bytes(data) != e["sha256"]:
            raise BootstrapError("sha256 mismatch for %s" % url)
        tmp = dest + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, dest)
    if not args.lock_in:
        lock = {"repo": repo, "suite": args.suite, "component": args.component,
                "architecture": ARCH, "base_sha256": sha256_file(args.base_zip), "packages": entries}
        lock_out = args.lock_out or os.path.join(args.out_dir, "debs.lock.json")
        with open(lock_out, "w") as f:
            json.dump(lock, f, indent=2, sort_keys=True)
            f.write("\n")
        log("wrote %s (%d packages)" % (lock_out, len(entries)))
    return 0


# --------------------------------------------------------------------------- build


def get_base(args):
    if args.base_zip:
        if not args.base_sha256 and not args.insecure_skip_base_check:
            raise BootstrapError("--base-zip needs --base-sha256 (or --insecure-skip-base-check)")
        path, want = args.base_zip, args.base_sha256
    else:
        cache = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
        d = os.path.join(cache, "termuxpp-bootstrap")
        os.makedirs(d, exist_ok=True)
        path, want = os.path.join(d, "bootstrap-aarch64-%s.zip" % UPSTREAM_SHA256[:12]), UPSTREAM_SHA256
        if not (os.path.isfile(path) and sha256_file(path) == want):
            log("download %s" % UPSTREAM_URL)
            data = http_get(UPSTREAM_URL)
            if sha256_bytes(data) != want:
                raise BootstrapError("upstream bootstrap checksum mismatch")
            with open(path + ".tmp", "wb") as f:
                f.write(data)
            os.replace(path + ".tmp", path)
    if want and sha256_file(path) != want.lower():
        raise BootstrapError("base bootstrap %s: sha256 %s, expected %s" % (path, sha256_file(path), want))
    return path


def collect_debs(args):
    paths = []
    lock = None
    if args.lock:
        with open(args.lock) as f:
            lock = json.load(f)
    for d in args.deb_dir or []:
        if lock:
            for e in lock["packages"]:
                p = os.path.join(d, os.path.basename(e["filename"]))
                if os.path.isfile(p):
                    if sha256_file(p) != e["sha256"]:
                        raise BootstrapError("%s does not match the lock file" % p)
                    paths.append(p)
        else:
            paths += [os.path.join(d, n) for n in sorted(os.listdir(d)) if n.endswith(".deb")]
    paths += args.deb or []
    return paths


def order_debs(debs):
    """Install dependencies first where possible; otherwise by name."""
    by_name = {d.name: d for d in debs}
    done, order = set(), []

    def visit(d, stack=()):
        if d.name in done or d.name in stack:
            return
        for group in parse_depends(stanza_get(d.stanza, "Depends", "") or ""):
            for dn, _, _ in group:
                if dn in by_name:
                    visit(by_name[dn], stack + (d.name,))
        done.add(d.name)
        order.append(d)

    for n in sorted(by_name):
        visit(by_name[n])
    return order


def cmd_build(args):
    tree = Tree.from_zip(get_base(args))
    if args.source_date_epoch is not None:
        import time
        tm = time.gmtime(args.source_date_epoch)
        tree.date_default = tm[:6]
    debs = order_debs([Deb(p) for p in collect_debs(args)])
    for d in debs:
        install_deb(tree, d, args.upgrade, args.force_overwrite)
    for root in args.prefix or []:
        overlay_prefix(tree, root)
    if args.nixpp_assets and not args.nixpp:
        raise BootstrapError("--nixpp-assets requires --nixpp")
    if args.nixpp:
        install_nixpp(tree, args.nixpp, args.nixpp_version, args.nixpp_assets, args.nixpp_setup_config)
    elif args.nixpp_setup_config:
        raise BootstrapError("--nixpp-setup-config requires --nixpp")
    if args.rbw:
        install_rbw(tree, args.rbw, args.rbw_version)
    for package in args.hold_package or []:
        if package not in tree.installed():
            raise BootstrapError("cannot hold missing package: %s" % package)
        stanzas = tree.status_stanzas()
        for stanza in stanzas:
            if stanza_get(stanza, "Package") == package:
                stanza[:] = [(key, "hold ok installed" if key == "Status" else value)
                             for key, value in stanza]
        tree.set_status_stanzas(stanzas)
    required = list(args.require_package) if args.require_package else list(DEFAULT_REQUIRED)
    if args.nixpp and "nixpp" not in required:
        required.append("nixpp")
    if args.rbw and "rbw" not in required:
        required.append("rbw")
    errors = verify_tree(tree, required, not args.no_check_deps)
    if errors:
        for e in errors:
            log("error: " + e)
        raise BootstrapError("%d verification error(s); not writing %s" % (len(errors), args.output))
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    tmp = args.output + ".tmp"
    tree.write_zip(tmp)
    os.replace(tmp, args.output)
    digest = sha256_file(args.output)
    with open(args.output + ".sha256", "w") as f:
        f.write("%s  %s\n" % (digest, os.path.basename(args.output)))
    print("wrote %s" % args.output)
    print("sha256 %s" % digest)
    print("use with Gradle: TERMUX_BOOTSTRAP_AARCH64_ZIP=%s TERMUX_BOOTSTRAP_AARCH64_SHA256=%s"
          % (os.path.abspath(args.output), digest))
    return 0


def cmd_verify(args):
    tree = Tree.from_zip(args.archive)
    errors = verify_tree(tree, args.require_package or [], not args.no_check_deps)
    for e in errors:
        print("error: " + e)
    print("%d package(s), %d file(s), %d symlink(s), %d error(s)" % (
        len(tree.installed()), len(tree.files), len(tree.symlinks), len(errors)))
    return 1 if errors else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="build the bootstrap archive")
    b.add_argument("-o", "--output", required=True)
    b.add_argument("--base-zip", help="base bootstrap archive (default: the pinned upstream aarch64 one)")
    b.add_argument("--base-sha256", help="expected SHA-256 of --base-zip")
    b.add_argument("--insecure-skip-base-check", action="store_true")
    b.add_argument("--deb", action="append", help="aarch64 .deb to add (repeatable)")
    b.add_argument("--deb-dir", action="append", help="directory of .deb files (repeatable)")
    b.add_argument("--lock", help="lock file from 'fetch'; with --deb-dir only locked debs are used, hash-checked")
    b.add_argument("--prefix", action="append", help="prepared package prefix laid out like $PREFIX (repeatable)")
    b.add_argument("--hold-package", action="append", help="mark an installed package held against APT upgrades (repeatable)")
    b.add_argument("--nixpp", help="Android/Bionic aarch64 nixpp binary to install as bin/nixpp")
    b.add_argument("--nixpp-assets", help="seed directory containing launcher, manifest.json and bootstrap-aarch64.zip")
    b.add_argument("--nixpp-version", help="dpkg version recorded for nixpp (default 0+<sha256 prefix>)")
    b.add_argument("--nixpp-setup-config",
                   help="KEY=VALUE defaults for `nixpp setup` (rworker controller, cache, ...), "
                        "installed as etc/nixpp/setup.conf; kept out of the public sources")
    b.add_argument("--rbw", metavar="DIR",
                   help="directory with bin/rbw and bin/rbw-agent (Android/Bionic aarch64), installed as package rbw")
    b.add_argument("--rbw-version", help="dpkg version recorded for rbw (default 0+<sha256 prefix>)")
    b.add_argument("--upgrade", action="store_true", help="let debs replace older packages of the base")
    b.add_argument("--force-overwrite", action="store_true", help="allow overwriting files of other packages")
    b.add_argument("--require-package", action="append",
                   help="package that must be installed in the result (default: %s)" % " ".join(DEFAULT_REQUIRED))
    b.add_argument("--no-check-deps", action="store_true")
    b.add_argument("--source-date-epoch", type=int, default=(int(os.environ["SOURCE_DATE_EPOCH"])
                                                            if "SOURCE_DATE_EPOCH" in os.environ else None),
                   help="timestamp for new entries (default: the base archive's)")
    b.set_defaults(func=cmd_build)

    f = sub.add_parser("fetch", help="resolve and download .debs from a Termux repository")
    f.add_argument("--base-zip", required=True, help="base bootstrap; packages in it are not fetched")
    f.add_argument("--package", action="append", default=[], help="package to fetch with its dependencies")
    f.add_argument("--out-dir", required=True)
    f.add_argument("--repo", default=DEFAULT_REPO)
    f.add_argument("--suite", default="stable")
    f.add_argument("--component", default="main")
    f.add_argument("--index-file", help="use this Packages(.gz) instead of downloading the index")
    f.add_argument("--lock-out", help="where to write the lock file (default: OUT_DIR/debs.lock.json)")
    f.add_argument("--lock-in", help="download exactly the packages of this lock file")
    f.set_defaults(func=cmd_fetch)

    v = sub.add_parser("verify", help="verify an archive")
    v.add_argument("archive")
    v.add_argument("--require-package", action="append")
    v.add_argument("--no-check-deps", action="store_true")
    v.set_defaults(func=cmd_verify)

    args = ap.parse_args(argv)
    if args.cmd == "fetch" and not args.package and not args.lock_in:
        ap.error("fetch needs --package or --lock-in")
    try:
        return args.func(args)
    except BootstrapError as e:
        log("error: %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
