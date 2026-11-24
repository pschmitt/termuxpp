#!/usr/bin/env python3
"""Tests for build-aarch64-bootstrap.py.

Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'  (from the repository root)
Set TERMUX_TEST_BASE_ZIP to the upstream aarch64 bootstrap to also run the real-archive tests.
"""

import hashlib
import importlib.util
import io
import lzma
import os
import stat
import tarfile
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bab", os.path.join(HERE, "build-aarch64-bootstrap.py"))
bab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bab)

P = bab.PREFIX_DATA
A = bab.ARROW


def elf(machine=bab.EM_AARCH64, pad=b"x"):
    hdr = bytearray(64)
    hdr[:4] = b"\x7fELF"
    hdr[4], hdr[5] = 2, 1
    hdr[18:20] = machine.to_bytes(2, "little")
    return bytes(hdr) + pad


def md5(b):
    return hashlib.md5(b).hexdigest()


def stanza_text(name, version, depends=None, arch="aarch64"):
    s = "Package: %s\nArchitecture: %s\nVersion: %s\nMaintainer: t\n" % (name, arch, version)
    if depends:
        s += "Depends: %s\n" % depends
    return s + "Description: test\n"


def make_base(path, packages=None):
    """A small bootstrap: package 'base' (bin/sh, a symlink, etc/conf) and 'libfoo'."""
    packages = packages or {}
    sh = b"#!/data/data/com.termux/files/usr/bin/sh\n"
    files = {"bin/sh": (sh, 0o700), "etc/base.conf": (b"conf\n", 0o600),
             "lib/libfoo.so": (elf(pad=b"foo1"), 0o600)}
    pkgs = {"base": (["bin/", "bin/sh", "etc/", "etc/base.conf", "bin/bash"], "1.0", None),
            "libfoo": (["lib/", "lib/libfoo.so"], "1.0", None)}
    pkgs.update(packages)
    status = ""
    out = dict((k, v) for k, v in files.items())
    for name, (entries, ver, deps) in pkgs.items():
        status += stanza_text(name, ver, deps) + "Status: install ok installed\n\n"
        lst = ["/."]
        acc = ""
        for part in P.split("/"):
            acc += "/" + part
            lst.append(acc + "/")
        lst += ["/%s/%s" % (P, e) for e in entries]
        out["var/lib/dpkg/info/%s.list" % name] = ("\n".join(lst) + "\n").encode(), 0o600
        sums = "".join("%s  %s/%s\n" % (md5(files[e][0]), P, e) for e in entries if e in files)
        out["var/lib/dpkg/info/%s.md5sums" % name] = sums.encode(), 0o600
    out["var/lib/dpkg/status"] = status.encode(), 0o600
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("SYMLINKS.txt", "dash%s./bin/bash\n" % A)
        for d in ("bin", "etc", "lib", "var", "var/lib", "var/lib/dpkg", "var/lib/dpkg/info"):
            zi = zipfile.ZipInfo(d + "/", (2026, 2, 12, 0, 0, 0))
            zi.external_attr = ((stat.S_IFDIR | 0o700) << 16) | 0x10
            z.writestr(zi, b"")
        for k, (data, mode) in out.items():
            zi = zipfile.ZipInfo(k, (2026, 2, 12, 0, 0, 0))
            zi.external_attr = (stat.S_IFREG | mode) << 16
            z.writestr(zi, data)


def ar(members):
    out = b"!<arch>\n"
    for name, body in members:
        out += ("%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name + "/", 0, 0, 0, "100644", len(body))).encode()
        out += body + (b"\n" if len(body) & 1 else b"")
    return out


def make_deb(path, name, version, files, symlinks=None, depends=None, arch="aarch64", conffiles=None):
    def tar_xz(add):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w") as t:
            add(t)
        return lzma.compress(buf.getvalue())

    def addfile(t, n, data, mode=0o644):
        ti = tarfile.TarInfo(n)
        ti.size, ti.mode = len(data), mode
        t.addfile(ti, io.BytesIO(data))

    def data(t):
        acc = "."
        for part in P.split("/"):
            acc += "/" + part
            ti = tarfile.TarInfo(acc + "/")
            ti.type, ti.mode = tarfile.DIRTYPE, 0o755
            t.addfile(ti)
        dirs = set()
        for f in files:
            parts = f.split("/")[:-1]
            for i in range(1, len(parts) + 1):
                dirs.add("/".join(parts[:i]))
        for d in sorted(dirs):
            ti = tarfile.TarInfo("./%s/%s/" % (P, d))
            ti.type, ti.mode = tarfile.DIRTYPE, 0o755
            t.addfile(ti)
        for f, (content, mode) in files.items():
            addfile(t, "./%s/%s" % (P, f), content, mode)
        for l, target in (symlinks or {}).items():
            ti = tarfile.TarInfo("./%s/%s" % (P, l))
            ti.type, ti.linkname = tarfile.SYMTYPE, target
            t.addfile(ti)

    def ctl(t):
        addfile(t, "./control", stanza_text(name, version, depends, arch).encode())
        sums = "".join("%s  %s/%s\n" % (md5(c), P, f)
                       for f, (c, _) in files.items() if f not in (conffiles or ()))
        addfile(t, "./md5sums", sums.encode())
        addfile(t, "./postinst", b"#!/data/data/com.termux/files/usr/bin/sh\n", 0o755)
        if conffiles:
            addfile(t, "./conffiles", "".join("/%s/%s\n" % (P, c) for c in conffiles).encode())

    with open(path, "wb") as f:
        f.write(ar([("debian-binary", b"2.0\n"), ("control.tar.xz", tar_xz(ctl)), ("data.tar.xz", tar_xz(data))]))


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.d = self.tmp.name
        self.base = os.path.join(self.d, "base.zip")
        make_base(self.base)
        self.base_sha = bab.sha256_file(self.base)
        self.out = os.path.join(self.d, "out", "bootstrap-aarch64.zip")

    def run_main(self, *argv):
        return bab.main(list(argv))

    def build(self, *extra, require=("base",)):
        args = ["build", "--base-zip", self.base, "--base-sha256", self.base_sha, "-o", self.out]
        for r in require:
            args += ["--require-package", r]
        return self.run_main(*(args + list(extra)))

    def deb(self, name, version, files, **kw):
        p = os.path.join(self.d, "%s_%s.deb" % (name, version))
        make_deb(p, name, version, files, **kw)
        return p

    def tree(self):
        return bab.Tree.from_zip(self.out)


class VersionTests(unittest.TestCase):
    def test_compare(self):
        c = bab.version_cmp
        self.assertEqual(c("1.0-1", "1.0-1"), 0)
        self.assertLess(c("1.0-1", "1.0-2"), 0)
        self.assertLess(c("1.9", "1.10"), 0)
        self.assertLess(c("1.0~rc1", "1.0"), 0)
        self.assertGreater(c("1:0.1", "9.9"), 0)
        self.assertLess(c("3.2.1-1", "1:3.2.1-1"), 0)
        self.assertGreater(c("10.6p2", "10.6"), 0)

    def test_depends(self):
        g = bab.parse_depends("a, b (>= 1:2.0-1) | c, d (<< 3)")
        self.assertEqual(g[0], [("a", None, None)])
        self.assertEqual(g[1], [("b", ">=", "1:2.0-1"), ("c", None, None)])
        self.assertEqual(g[2], [("d", "<<", "3")])


class BuildTests(Base):
    def test_roundtrip_without_additions(self):
        self.assertEqual(self.build(), 0)
        base, out = bab.Tree.from_zip(self.base), self.tree()
        self.assertEqual(base.files, out.files)
        self.assertEqual(base.symlinks, out.symlinks)

    def test_reproducible(self):
        d1 = self.deb("newpkg", "1.0", {"bin/new": (elf(), 0o700), "share/n.txt": (b"n", 0o644)})
        self.build("--deb", d1)
        first = bab.sha256_file(self.out)
        self.build("--deb", d1)
        self.assertEqual(first, bab.sha256_file(self.out))
        with open(self.out + ".sha256") as f:
            self.assertEqual(f.read().split()[0], first)

    def test_deb_install_preserves_dpkg_metadata_and_symlinks(self):
        d = self.deb("newpkg", "1.0-1", {"bin/new": (elf(), 0o700), "etc/new.conf": (b"c", 0o600)},
                     symlinks={"bin/alias": "new", "lib/abs": "/data/data/com.termux/files/usr/bin/new"},
                     depends="libfoo (>= 1.0)", conffiles=["etc/new.conf"])
        self.assertEqual(self.build("--deb", d, require=("base", "newpkg")), 0)
        t = self.tree()
        self.assertEqual(t.files["bin/new"][1], 0o700)
        self.assertEqual(t.symlinks["bin/alias"], "new")
        self.assertEqual(t.symlinks["lib/abs"], "/data/data/com.termux/files/usr/bin/new")
        self.assertEqual(t.symlinks["bin/bash"], "dash")  # base symlink untouched
        lst = t.files["var/lib/dpkg/info/newpkg.list"][0].decode().split("\n")
        self.assertEqual(lst[0], "/.")
        self.assertIn("/%s/bin/new" % P, lst)
        self.assertIn("/%s/bin/alias" % P, lst)
        self.assertIn("/%s/bin/" % P, lst)
        self.assertIn("var/lib/dpkg/info/newpkg.postinst", t.files)
        self.assertIn("var/lib/dpkg/info/newpkg.conffiles", t.files)
        st = t.installed()["newpkg"]
        self.assertEqual(bab.stanza_get(st, "Status"), "install ok installed")
        self.assertIn(md5(b"c"), bab.stanza_get(st, "Conffiles"))
        self.assertEqual(set(t.installed()), {"base", "libfoo", "newpkg"})
        self.assertEqual(bab.verify_tree(t, ["newpkg"]), [])
        # SYMLINKS.txt first, in the arrow format the installer splits on
        with zipfile.ZipFile(self.out) as z:
            self.assertEqual(z.namelist()[0], "SYMLINKS.txt")
            lines = z.read("SYMLINKS.txt").decode().splitlines()
        self.assertIn("new%s./bin/alias" % A, lines)
        self.assertTrue(all(len(l.split(A)) == 2 for l in lines))

    def test_rejects_wrong_arch_deb_and_elf(self):
        d = self.deb("armpkg", "1", {"bin/a": (elf(), 0o700)}, arch="arm")
        self.assertEqual(self.build("--deb", d), 1)
        d = self.deb("badelf", "1", {"bin/a": (elf(machine=40), 0o700)})
        self.assertEqual(self.build("--deb", d), 1)
        self.assertFalse(os.path.exists(self.out))

    def test_conflict_with_other_package(self):
        d = self.deb("evil", "1", {"bin/sh": (b"#!/bin/evil\n", 0o700)})
        self.assertEqual(self.build("--deb", d), 1)
        self.assertEqual(self.build("--deb", d, "--force-overwrite"), 0)

    def test_unsatisfied_dependency_fails(self):
        d = self.deb("needy", "1", {"bin/n": (elf(), 0o700)}, depends="missing-lib")
        self.assertEqual(self.build("--deb", d), 1)
        self.assertEqual(self.build("--deb", d, "--no-check-deps"), 0)
        d2 = self.deb("needy2", "1", {"bin/n2": (elf(), 0o700)}, depends="libfoo (>= 2.0)")
        self.assertEqual(self.build("--deb", d2), 1)

    def test_upgrade_replaces_files_and_metadata(self):
        d = self.deb("libfoo", "1.1", {"lib/libfoo.so": (elf(pad=b"foo2"), 0o600),
                                       "lib/libfoo-extra.so": (elf(pad=b"e"), 0o600)})
        self.assertEqual(self.build("--deb", d), 1)  # needs --upgrade
        self.assertEqual(self.build("--deb", d, "--upgrade"), 0)
        t = self.tree()
        self.assertEqual(bab.stanza_get(t.installed()["libfoo"], "Version"), "1.1")
        self.assertIn(b"foo2", t.files["lib/libfoo.so"][0])
        self.assertIn("lib/libfoo-extra.so", t.files)
        self.assertEqual(len([s for s in t.status_stanzas() if bab.stanza_get(s, "Package") == "libfoo"]), 1)

    def test_same_version_is_skipped(self):
        d = self.deb("libfoo", "1.0", {"lib/libfoo.so": (elf(pad=b"other"), 0o600)})
        self.assertEqual(self.build("--deb", d), 0)
        self.assertIn(b"foo1", self.tree().files["lib/libfoo.so"][0])

    def test_prepared_prefix_with_dpkg_metadata(self):
        root = os.path.join(self.d, "prefix")
        os.makedirs(root + "/bin")
        os.makedirs(root + "/var/lib/dpkg/info")
        with open(root + "/bin/tool", "wb") as f:
            f.write(elf(pad=b"tool"))
        os.chmod(root + "/bin/tool", 0o755)
        os.symlink("tool", root + "/bin/tool2")
        with open(root + "/var/lib/dpkg/status", "w") as f:
            f.write(stanza_text("tool", "2.0") + "Status: install ok installed\n\n")
        with open(root + "/var/lib/dpkg/info/tool.list", "w") as f:
            f.write("/.\n/%s/bin/tool\n/%s/bin/tool2\n" % (P, P))
        self.assertEqual(self.build("--prefix", root, require=("base", "tool")), 0)
        t = self.tree()
        self.assertEqual(t.symlinks["bin/tool2"], "tool")
        self.assertEqual(t.files["bin/tool"][1], 0o755)
        self.assertEqual(set(t.installed()), {"base", "libfoo", "tool"})
        # base database must not be replaced by the overlay's
        self.assertIn("var/lib/dpkg/info/base.list", t.files)

    def test_prepared_prefix_cannot_clobber_owned_file(self):
        root = os.path.join(self.d, "prefix")
        os.makedirs(root + "/bin")
        with open(root + "/bin/sh", "wb") as f:
            f.write(b"x")
        self.assertEqual(self.build("--prefix", root), 1)

    def test_nixpp(self):
        n = os.path.join(self.d, "nixpp")
        with open(n, "wb") as f:
            f.write(elf(pad=b"nixpp"))
        self.assertEqual(self.build("--nixpp", n, "--nixpp-version", "0.1"), 1)
        deps = []
        for package in ("bash", "curl", "ca-certificates", "gzip", "xz-utils", "openssh"):
            deps.extend(("--deb", self.deb(package, "1", {})))
        self.assertEqual(self.build(*deps, "--nixpp", n, "--nixpp-version", "0.1"), 0)
        t = self.tree()
        self.assertEqual(t.files["bin/nixpp"][1], 0o700)
        self.assertEqual(bab.stanza_get(t.installed()["nixpp"], "Version"), "0.1")
        self.assertIn("/%s/bin/nixpp" % P, t.files["var/lib/dpkg/info/nixpp.list"][0].decode())
        launcher, mode, *_ = t.files["bin/nixpp-setup"]
        self.assertEqual(mode, 0o700)
        self.assertIn(b'exec nixpp setup "$@"', launcher)
        self.assertNotIn(b"curl", launcher)
        self.assertNotIn(b"yadm", launcher)
        self.assertIn("bin/nixpp-setup", t.package_files("nixpp"))
        self.assertEqual(bab.verify_tree(t, ["nixpp"]), [])
        assets = os.path.join(self.d, "seed")
        os.mkdir(assets)
        for name, content in (
            ("launcher", elf(pad=b"launcher")),
            ("manifest.json", b"{}"),
            ("bootstrap-aarch64.zip", b"seed"),
        ):
            with open(os.path.join(assets, name), "wb") as output:
                output.write(content)
        self.assertEqual(self.build(*deps, "--nixpp", n, "--nixpp-assets", assets), 0)
        t = self.tree()
        self.assertEqual(t.files["share/nixpp/launcher"][1], 0o700)
        self.assertEqual(t.files["share/nixpp/manifest.json"][1], 0o644)
        self.assertIn("share/nixpp/bootstrap-aarch64.zip", t.package_files("nixpp"))
        self.assertEqual(bab.verify_tree(t, ["nixpp"]), [])
        os.unlink(os.path.join(assets, "manifest.json"))
        self.assertEqual(self.build(*deps, "--nixpp", n, "--nixpp-assets", assets), 1)
        with open(n, "wb") as f:
            f.write(elf(machine=62))  # x86_64
        self.assertEqual(self.build("--nixpp", n), 1)

    def make_rbw_dir(self, **names):
        root = os.path.join(self.d, "rbw-out")
        os.makedirs(root + "/bin", exist_ok=True)
        for name in ("rbw", "rbw-agent"):
            with open(os.path.join(root, "bin", name), "wb") as f:
                f.write(elf(pad=name.encode()))
        return root

    def test_rbw(self):
        root = self.make_rbw_dir()
        ca = ("--deb", self.deb("ca-certificates", "1", {}))
        self.assertEqual(self.build(*ca, "--rbw", root, "--rbw-version", "2.48.2"), 0)
        t = self.tree()
        self.assertEqual(t.files["bin/rbw"][1], 0o700)
        self.assertEqual(t.files["bin/rbw-agent"][1], 0o700)
        self.assertEqual(bab.stanza_get(t.installed()["rbw"], "Version"), "2.48.2")
        self.assertTrue({"bin/rbw", "bin/rbw-agent"} <= set(t.package_files("rbw")))
        self.assertIn("/%s/bin/rbw-agent" % P, t.files["var/lib/dpkg/info/rbw.list"][0].decode())
        self.assertEqual(bab.verify_tree(t, ["rbw"]), [])
        # A Termux rbw deb is replaced rather than left with conflicting files.
        deb = self.deb("rbw", "1", {"bin/rbw": (b"#!/bin/sh\n", 0o755)})
        self.assertEqual(self.build(*ca, "--deb", deb, "--rbw", root), 0)
        t = self.tree()
        self.assertTrue(t.files["bin/rbw"][0].startswith(b"\x7fELF"))
        self.assertNotEqual(bab.stanza_get(t.installed()["rbw"], "Version"), "1")
        # Only AArch64 Bionic executables are accepted.
        with open(os.path.join(root, "bin/rbw"), "wb") as f:
            f.write(elf(machine=62))
        self.assertEqual(self.build("--rbw", root), 1)
        os.unlink(os.path.join(root, "bin/rbw"))
        self.assertEqual(self.build("--rbw", root), 1)

    def test_nixpp_setup_config(self):
        n = os.path.join(self.d, "nixpp")
        with open(n, "wb") as f:
            f.write(elf(pad=b"nixpp"))
        deps = []
        for package in ("bash", "curl", "ca-certificates", "gzip", "xz-utils", "openssh"):
            deps.extend(("--deb", self.deb(package, "1", {})))
        config = os.path.join(self.d, "setup.conf")
        with open(config, "w") as f:
            f.write("controller=controller.example\n")
        self.assertEqual(self.build(*deps, "--nixpp", n, "--nixpp-setup-config", config), 0)
        t = self.tree()
        content, mode, *_ = t.files["etc/nixpp/setup.conf"]
        self.assertEqual(content, b"controller=controller.example\n")
        self.assertEqual(mode, 0o600)
        self.assertIn("etc/nixpp/setup.conf", t.package_files("nixpp"))
        self.assertEqual(bab.verify_tree(t, ["nixpp"]), [])
        # Without --nixpp there is nothing to configure.
        self.assertEqual(self.build("--nixpp-setup-config", config), 1)
        with open(config, "w") as f:
            f.write("\n")
        self.assertEqual(self.build(*deps, "--nixpp", n, "--nixpp-setup-config", config), 1)

    def test_hold_package(self):
        self.assertEqual(self.build("--hold-package", "base"), 0)
        self.assertEqual(bab.stanza_get(self.tree().installed()["base"], "Status"), "hold ok installed")
        self.assertEqual(self.build("--hold-package", "missing"), 1)

    def test_required_packages_default_enforced(self):
        self.assertEqual(self.run_main("build", "--base-zip", self.base, "--base-sha256", self.base_sha,
                                       "-o", self.out), 1)

    def test_base_checksum_enforced(self):
        self.assertEqual(self.run_main("build", "--base-zip", self.base, "--base-sha256", "0" * 64,
                                       "-o", self.out), 1)
        self.assertEqual(self.run_main("build", "--base-zip", self.base, "-o", self.out), 1)

    def test_lock_pins_debs(self):
        import json
        d = self.deb("newpkg", "1.0", {"bin/new": (elf(), 0o700)})
        lock = os.path.join(self.d, "lock.json")
        for sha, want in (("0" * 64, 1), (bab.sha256_file(d), 0)):
            with open(lock, "w") as f:
                json.dump({"repo": "x", "packages": [{"name": "newpkg", "filename": "pool/n/" + os.path.basename(d),
                                                       "sha256": sha}]}, f)
            self.assertEqual(self.build("--deb-dir", self.d, "--lock", lock), want)

    def test_dependency_order(self):
        a = self.deb("a", "1", {"bin/a": (elf(), 0o700)}, depends="b")
        b = self.deb("b", "1", {"bin/b": (elf(), 0o700)})
        order = [x.name for x in bab.order_debs([bab.Deb(a), bab.Deb(b)])]
        self.assertEqual(order, ["b", "a"])

    def test_path_traversal_rejected(self):
        with self.assertRaises(bab.BootstrapError):
            bab.safe_relpath("../etc/passwd")
        with self.assertRaises(bab.BootstrapError):
            bab.safe_relpath("/etc/passwd")


class FetchTests(Base):
    def test_resolve_skips_bundled_and_satisfied(self):
        index = {}
        for n, v, dep in (("openssh", "10", "libfoo (>= 1.0), newlib"), ("newlib", "2", "libfoo"),
                          ("libfoo", "1.5", None)):
            index[n] = bab.parse_stanzas(stanza_text(n, v, dep))[0]
        installed = bab.Tree.from_zip(self.base).installed()
        chosen = bab.resolve(index, installed, ["openssh", "base"])
        self.assertEqual(sorted(chosen), ["newlib", "openssh"])

    def test_resolve_upgrades_when_bundled_too_old(self):
        index = {n: bab.parse_stanzas(stanza_text(n, v, d))[0]
                 for n, v, d in (("openssh", "10", "libfoo (>= 1.5)"), ("libfoo", "1.5", None))}
        chosen = bab.resolve(index, bab.Tree.from_zip(self.base).installed(), ["openssh"])
        self.assertEqual(sorted(chosen), ["libfoo", "openssh"])

    def test_resolve_missing_dependency(self):
        index = {"openssh": bab.parse_stanzas(stanza_text("openssh", "1", "nope"))[0]}
        with self.assertRaises(bab.BootstrapError):
            bab.resolve(index, {}, ["openssh"])


@unittest.skipUnless(os.environ.get("TERMUX_TEST_BASE_ZIP"), "TERMUX_TEST_BASE_ZIP not set")
class RealArchiveTests(unittest.TestCase):
    def test_upstream_verifies_and_roundtrips(self):
        path = os.environ["TERMUX_TEST_BASE_ZIP"]
        self.assertEqual(bab.sha256_file(path), bab.UPSTREAM_SHA256)
        t = bab.Tree.from_zip(path)
        self.assertEqual(bab.verify_tree(t, bab.DEFAULT_REQUIRED[1:]), [])
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "o.zip")
            t.write_zip(out)
            t2 = bab.Tree.from_zip(out)
            self.assertEqual(t.files, t2.files)
            self.assertEqual(t.symlinks, t2.symlinks)
            self.assertEqual(t.dirs, t2.dirs)


if __name__ == "__main__":
    unittest.main()
