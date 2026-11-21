#!/usr/bin/env python3
"""Static checks for the managed guest's tpp backends (pkgs/nixpp/android-api.nix).

The guest runs this repository's one tpp binary, which contains every command
(crate tpp-api), not the upstream droid-nix scripts (which have no -h and can
change service state on `--help`). The build itself runs every command's -h
(tests.dispatcher-help); these tests keep the Nix expression from drifting back
to command scripts and from losing the settings the binary needs.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NIX = (ROOT / "pkgs" / "nixpp" / "android-api.nix").read_text()
PKG = ROOT / "pkgs" / "tpp-api-bridge"


def impls():
    """The implementations the command table of the Rust tpp names."""
    table = (ROOT / "pkgs" / "tpp-cli" / "src" / "commands.rs").read_text()
    return set(re.findall(r'imp:\s*"([^"]+)"', table))


class GuestBackendTests(unittest.TestCase):
    def test_no_droid_nix_command_scripts(self):
        self.assertNotIn("droid-nix-api", NIX)
        self.assertNotIn("bin/droid-nix-$impl", NIX)
        self.assertNotIn("termux-api.nix", NIX)

    def test_backend_is_built_from_the_repository_package(self):
        self.assertRegex(NIX, r"src = lib\.cleanSource \.\./tpp-api-bridge;")
        self.assertIn("cmake", NIX)

    def test_tpp_is_the_rust_binary_and_runs_the_commands_itself(self):
        self.assertIn("callPackage ./tpp.nix", NIX)
        self.assertIn("exec ${tppCli}/bin/tpp", NIX)
        # No shell dispatcher, awk renderer, TSV tables or command scripts are left
        for stale in ("tpp.in", "gawk", "render.awk", ".tsv", "TPP_AWK", "TPP_LIBEXEC_DIR", "TPP_API_SCRIPTS",
                      "libexec/tpp/commands", "scriptPrelude"):
            self.assertNotIn(stale, NIX)
        tpp = (ROOT / "pkgs" / "nixpp" / "tpp.nix").read_text()
        self.assertIn("cargoLock.lockFile", tpp)
        self.assertIn('"tpp"', tpp)
        self.assertIn('"/pkgs/tpp-api"', tpp)

    def test_the_binary_reaches_the_app_through_termux_am(self):
        self.assertIn("export TPP_API_AM=${termux-am}/bin/am", NIX)
        self.assertIn("export TPP_API_PATH_CMD=${androidPath}", NIX)

    def test_tpp_session_is_the_same_binary(self):
        self.assertIn("exec -a tpp-session ${tppCli}/bin/tpp", NIX)
        self.assertNotIn("python3 \"$script\"", NIX)

    def test_build_validates_every_command(self):
        self.assertIn("doInstallCheck = true", NIX)
        self.assertRegex(NIX, r'timeout 20 tpp "[^"]*" "\$flag"')
        self.assertIn("tests.dispatcher-help", NIX)

    def test_guest_adaptations_replace_fail(self):
        # A moved source line must break the build, not silently skip a patch.
        block = re.search(r"substituteInPlace [^\n]*termux-api\.c[^\n]*\\\n(?:[^\n]*\\\n)*[^\n]*\n", NIX)
        self.assertIsNotNone(block)
        self.assertIn("--replace-fail", block.group(0))
        self.assertNotIn("--replace-quiet", block.group(0))

    def test_no_command_scripts_in_the_package(self):
        # The oracle copies of the shell scripts live with the tpp-api tests
        self.assertFalse((PKG / "scripts").exists())
        self.assertTrue((ROOT / "pkgs" / "tpp-api" / "tests" / "oracle" / "termux-camera-photo.in").is_file())

    def test_every_table_command_has_an_oracle_script(self):
        for impl in sorted(impls()):
            with self.subTest(impl=impl):
                self.assertTrue((ROOT / "pkgs" / "tpp-api" / "tests" / "oracle" / ("termux-%s.in" % impl)).is_file())

    def test_setup_storage_uses_the_guest_am_not_the_outer_one(self):
        # The outer prefix's `am` carries a stock identity; the guest's
        # termux-am talks to the TermuxPP app's socket server.
        root = ROOT / "pkgs" / "nixpp"
        rust = (ROOT / "pkgs" / "tpp-cli" / "src" / "storage.rs").read_text()
        self.assertIn('Command::new("am")', rust)
        self.assertNotIn('"--outer"', rust)
        storage = (root / "storage.nix").read_text()
        self.assertIn("termux-am", storage)
        packages = (root / "packages.nix").read_text()
        self.assertIn("inherit (termuxpp-api) termux-am", packages)
        self.assertIn("inherit backend termux-am;", NIX)


if __name__ == "__main__":
    unittest.main()
