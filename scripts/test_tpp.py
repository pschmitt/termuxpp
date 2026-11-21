#!/usr/bin/env python3
"""Tests of the command table and of the "tpp" binary: completion, plugins and help.

The command table is in tpp-cli/src/commands.rs and the renderer is Rust, so
the unit tests of those live in tpp-cli, and the tests of the commands (output
shaping, the API calls, usb) are Rust tests of tpp-cli and tpp-api. Set
TPP_BIN to the tpp binary (cargo build -p tpp) to run the binary tests."""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "pkgs" / "tpp-api-bridge"
ORACLE = ROOT / "pkgs" / "tpp-api" / "tests" / "oracle"
COMMANDS_RS = ROOT / "pkgs" / "tpp-cli" / "src" / "commands.rs"

BASH = shutil.which("bash")
TPP_BIN = os.environ.get("TPP_BIN")


def _rows(kind, fields):
    """The `kind { field: "value", ... }` rows of commands.rs."""
    text = COMMANDS_RS.read_text()
    pattern = r"\b%s \{ %s \}" % (kind, ", ".join('%s: "((?:[^"\\\\]|\\\\.)*)"' % f for f in fields))
    rows = re.findall(pattern, text)
    assert rows, kind
    return rows


def read_table():
    return [
        dict(zip(("group", "action", "impl", "cls", "args", "desc"), r))
        for r in _rows("Command", ("group", "action", "imp", "class", "args", "desc"))
    ]


def read_options():
    return [(g, a, w.split()) for g, a, w in _rows("Options", ("group", "action", "words"))]


def option_name(word):
    """The option a table word stands for: "-p:gps" and "--x=" are -p and --x."""
    return re.split(r"[:=]", word, maxsplit=1)[0]


class TableTests(unittest.TestCase):
    def test_table_is_well_formed(self):
        rows = read_table()
        self.assertGreater(len(rows), 50)
        seen = set()
        for row in rows:
            for key, value in row.items():
                self.assertTrue(value, (key, row))
                self.assertFalse(set(";[]") & set(value), (key, row))
            base, *overrides = row["cls"].split(",")
            self.assertIn(base, ("auto", "text", "raw"))
            for override in overrides:
                self.assertRegex(override, r"^(-[A-Za-z]|[a-z][a-z0-9-]*)=(auto|text|raw)$", row)
            key = (row["group"], row["action"])
            self.assertNotIn(key, seen)
            seen.add(key)

    def test_option_table_is_well_formed(self):
        commands = {(row["group"], row["action"]): row for row in read_table()}
        seen = set()
        for group, action, words in read_options():
            key = (group, action)
            self.assertIn(key, commands, "options for an unknown command: %s" % (key,))
            self.assertNotIn(key, seen, "duplicate options row: %s" % (key,))
            seen.add(key)
            self.assertTrue(words, key)
            names = [option_name(w) for w in words if w.startswith("-")]
            self.assertEqual(len(names), len(set(names)), "repeated option: %s" % (key,))
            for word in words:
                self.assertFalse(set(";[]|") & set(word), (key, word))
                if word.startswith("-"):
                    self.assertRegex(word, r"^(-[A-Za-z]|--[a-z][a-z0-9-]*)([:=].*)?$", (key, word))
                    if "=" in word:
                        self.assertTrue(word.endswith("="), (key, word))
                        self.assertNotIn(":", word, (key, word))
                    self.assertNotIn(option_name(word), ("-h", "--help", "--json"), (key, word))
                else:
                    self.assertRegex(word, r"^[a-z][a-z0-9_-]*$", (key, word))
            if commands[key]["cls"].split(",")[0] == "raw":
                self.assertNotIn("--json", words)

    def test_every_oracle_script_is_in_the_table_and_back(self):
        # The shell scripts the Rust commands are tested against
        impls = {row["impl"] for row in read_table()}
        oracle = {p.name[len("termux-"):-len(".in")] for p in ORACLE.glob("termux-*.in")}
        self.assertEqual(oracle - impls, set(), "oracle script without a table row")
        self.assertEqual(impls - oracle, set(), "table row without an oracle script")

    def test_no_group_mixes_plain_and_action_rows(self):
        by_group = {}
        for row in read_table():
            by_group.setdefault(row["group"], []).append(row["action"])
        for group, actions in by_group.items():
            if "-" in actions:
                self.assertEqual(actions, ["-"], group)

    def test_the_command_scripts_are_gone(self):
        self.assertFalse((PKG / "scripts").exists())


@unittest.skipUnless(BASH and TPP_BIN, "bash and TPP_BIN (the tpp binary) are required")
class DispatcherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.prefix = Path(self.tmp.name) / "usr"
        self.libexec = self.prefix / "libexec" / "tpp"
        (self.prefix / "bin").mkdir(parents=True)
        (self.libexec / "plugins").mkdir(parents=True)
        (self.prefix / "bin" / "bash").symlink_to(BASH)
        self.tpp = self.prefix / "bin" / "tpp"
        shutil.copy(TPP_BIN, self.tpp)
        self.tpp.chmod(0o755)
        self.env = {
            k: v
            for k, v in os.environ.items()
            if not k.startswith("TPP_") and k not in ("NO_COLOR", "CLICOLOR_FORCE")
        }
        self.env["TMPDIR"] = self.tmp.name

    def plugin(self, name, body):
        path = self.libexec / "plugins" / name
        path.write_text("#!%s\n%s\n" % (BASH, body))
        path.chmod(0o755)

    def run_tpp(self, *args, env=None, stdin=None):
        full = dict(self.env)
        full.update(env or {})
        return subprocess.run(
            [str(self.tpp), *args],
            capture_output=True,
            env=full,
            input=stdin,
            timeout=60,
        )

    def test_completion_of_overridden_raw_commands_has_no_json(self):
        rc, out = self.complete("keystore", "sign", "-")
        self.assertNotIn("--json", out)
        rc, out = self.complete("keystore", "list", "-")
        self.assertIn("--json", out)
        rc, out = self.complete("usb", "-e", "-")
        self.assertNotIn("--json", out)
        rc, out = self.complete("usb", "-l", "-")
        self.assertIn("--json", out)

    def test_list_names_every_command(self):
        out = self.run_tpp("list").stdout.decode()
        for row in read_table():
            name = "tpp " + row["group"] + ("" if row["action"] == "-" else " " + row["action"])
            self.assertIn(name, out)

    def test_unknown_commands(self):
        for argv in (("nope",), ("wifi", "nope"), ("--nope",), ()):
            res = self.run_tpp(*argv)
            self.assertEqual(res.returncode, 2, argv)
            self.assertEqual(res.stdout, b"")

    def test_plugin_argv_is_passed_unchanged(self):
        self.plugin("myplug", 'for a in "$@"; do printf "[%s]" "$a"; done; echo')
        res = self.run_tpp("myplug", "list", "--json", "-x", "a b", "--help")
        self.assertEqual(res.stdout, b"[list][--json][-x][a b][--help]\n")

    def test_plugin_gets_leading_json_at_the_end(self):
        self.plugin("myplug", 'for a in "$@"; do printf "[%s]" "$a"; done; echo')
        res = self.run_tpp("--json", "myplug", "list")
        self.assertEqual(res.stdout, b"[list][--json]\n")
        res = self.run_tpp("--json", "myplug", "run", "--", "x")
        self.assertEqual(res.stdout, b"[run][--json][--][x]\n")

    def test_plugin_status_and_streams(self):
        self.plugin("myplug", "printf 'out\\000bin'; echo err >&2; exit 5")
        res = self.run_tpp("myplug")
        self.assertEqual(res.returncode, 5)
        self.assertEqual(res.stdout, b"out\x00bin")
        self.assertEqual(res.stderr, b"err\n")

    def test_plugin_dir_override_and_empty(self):
        alt = Path(self.tmp.name) / "alt"
        alt.mkdir()
        path = alt / "hello"
        path.write_text("#!%s\necho hello $*\n" % BASH)
        path.chmod(0o755)
        self.plugin("myplug", "echo s")
        res = self.run_tpp("hello", "x", env={"TPP_PLUGIN_DIR": str(alt)})
        self.assertEqual(res.stdout, b"hello x\n")
        res = self.run_tpp("myplug", env={"TPP_PLUGIN_DIR": str(alt)})
        self.assertEqual(res.returncode, 2)
        res = self.run_tpp("myplug", env={"TPP_PLUGIN_DIR": ""})
        self.assertEqual(res.returncode, 2)
        res = self.run_tpp("myplug", env={"TPP_PLUGIN_DIR": "%s:%s" % (alt, self.libexec / "plugins")})
        self.assertEqual(res.stdout, b"s\n")

    def test_non_executable_plugin_is_ignored(self):
        path = self.libexec / "plugins" / "myplug"
        path.write_text("#!%s\necho s\n" % BASH)
        self.assertEqual(self.run_tpp("myplug").returncode, 2)

    def test_plugin_help_and_listing(self):
        self.plugin("myplug", 'echo "plugin args: $*"')
        res = self.run_tpp("help", "myplug")
        self.assertEqual(res.stdout, b"plugin args: --help\n")
        self.assertIn(b"myplug", self.run_tpp("--help").stdout)

    def complete(self, *words, env=None):
        res = self.run_tpp("--complete", *words, env=env)
        return res.returncode, res.stdout.decode().split()

    def test_completion_groups_actions_and_options(self):
        rc, out = self.complete("wi")
        self.assertEqual((rc, out), (0, ["wifi"]))
        rc, out = self.complete("")
        self.assertIn("vibrate", out)
        self.assertIn("help", out)
        rc, out = self.complete("wifi", "")
        self.assertEqual(sorted(out), ["connection", "disable", "enable", "scan"])
        rc, out = self.complete("--json", "wifi", "s")
        self.assertEqual(out, ["scan"])
        rc, out = self.complete("vibrate", "-")
        self.assertEqual(sorted(out), ["--help", "--json", "-d", "-f"])
        rc, out = self.complete("vibrate", "")
        self.assertEqual(rc, 1)
        rc, out = self.complete("help", "wifi", "")
        self.assertIn("scan", out)

    def test_completion_of_command_options(self):
        rc, out = self.complete("sms", "send", "-")
        self.assertEqual((rc, sorted(out)), (0, ["--help", "--json", "-n", "-s"]))
        rc, out = self.complete("sms", "send", "-n", "+49", "-")
        self.assertEqual(sorted(out), ["--help", "--json", "-s"])
        rc, out = self.complete("notification", "send", "--pri")
        self.assertEqual((rc, out), (0, ["--priority"]))
        rc, out = self.complete("dialog", "text", "-")
        self.assertIn("-t", out)
        self.assertIn("--json", out)
        rc, out = self.complete("camera", "photo", "-")
        self.assertEqual(sorted(out), ["--help", "--json", "-c"])
        # A command without a row only has the generic options
        rc, out = self.complete("wifi", "scan", "-")
        self.assertEqual(sorted(out), ["--help", "--json"])

    def test_completion_of_option_values(self):
        rc, out = self.complete("location", "-p", "")
        self.assertEqual((rc, out), (0, ["gps", "network", "passive"]))
        rc, out = self.complete("location", "-p", "n")
        self.assertEqual(out, ["network"])
        rc, out = self.complete("location", "-r", "")
        self.assertEqual(out, ["once", "last", "updates"])
        rc, out = self.complete("sms", "list", "-t", "i")
        self.assertEqual(out, ["inbox"])
        rc, out = self.complete("notification", "send", "--priority", "m")
        self.assertEqual(out, ["max", "min"])
        # The value is complete, so the next word is a new one
        rc, out = self.complete("location", "-p", "gps", "-")
        self.assertIn("-r", out)
        # No suggestion for a free value: complete file names
        rc, out = self.complete("vibrate", "-d", "")
        self.assertEqual((rc, out), (1, []))
        rc, out = self.complete("download", "-d", "")
        self.assertEqual(rc, 1)

    def test_completion_of_positional_words(self):
        rc, out = self.complete("torch", "")
        self.assertEqual((rc, out), (0, ["on", "off"]))
        rc, out = self.complete("torch", "o")
        self.assertEqual(out, ["on", "off"])
        rc, out = self.complete("torch", "of")
        self.assertEqual(out, ["off"])
        # Only the first word is a choice
        rc, out = self.complete("torch", "on", "")
        self.assertEqual(rc, 1)
        rc, out = self.complete("volume", "")
        self.assertIn("music", out)
        rc, out = self.complete("keystore", "")
        self.assertEqual(out, ["list", "delete", "generate", "sign", "verify"])
        rc, out = self.complete("media", "player", "")
        self.assertEqual(out, ["info", "play", "pause", "stop"])
        rc, out = self.complete("media", "player", "play", "-")
        self.assertEqual(sorted(out), ["--help", "--json"])
        rc, out = self.complete("brightness", "")
        self.assertEqual(out, ["auto"])

    def test_completion_option_with_attached_value(self):
        rc, out = self.complete("sms", "list", "--message-t")
        self.assertEqual((rc, out), (0, ["--message-type="]))
        rc, out = self.complete("sms", "list", "--conversation-l")
        self.assertEqual(out, ["--conversation-list", "--conversation-limit="])

    def test_completion_does_not_repeat_options(self):
        rc, out = self.complete("location", "-p", "gps", "-")
        self.assertNotIn("-p", out)
        rc, out = self.complete("sms", "list", "--message-type=sms", "--message-")
        self.assertNotIn("--message-type=", out)
        self.assertIn("--message-limit=", out)

    def test_completion_does_not_repeat_json(self):
        rc, out = self.complete("--json", "vibrate", "-")
        self.assertEqual(sorted(out), ["--help", "-d", "-f"])
        rc, out = self.complete("vibrate", "--json", "-")
        self.assertEqual(sorted(out), ["--help", "-d", "-f"])
        rc, out = self.complete("--json", "wifi", "scan", "-")
        self.assertEqual(out, ["--help"])
        rc, out = self.complete("--json", "wifi", "-")
        self.assertEqual(out, ["--help"])
        rc, out = self.complete("wifi", "--json", "s")
        self.assertEqual(out, ["scan"])

    def test_completion_of_raw_commands_has_no_json(self):
        raw = [r for r in read_table() if r["cls"].split(",")[0] == "raw"]
        self.assertTrue(raw)
        for row in raw:
            words = [row["group"]] + ([] if row["action"] == "-" else [row["action"]])
            with self.subTest(command=" ".join(words)):
                rc, out = self.complete(*words, "--")
                self.assertEqual(rc, 0)
                self.assertNotIn("--json", out)
                self.assertIn("--help", out)

    def test_completion_stops_after_double_dash(self):
        rc, out = self.complete("vibrate", "--", "-")
        self.assertEqual(rc, 1)

    def test_every_option_row_completes(self):
        for group, action, words in read_options():
            command = [group] + ([] if action == "-" else [action])
            with self.subTest(command=" ".join(command)):
                rc, out = self.complete(*command, "-")
                self.assertEqual(rc, 0)
                for word in words:
                    if not word.startswith("-"):
                        continue
                    name = option_name(word) + ("=" if word.endswith("=") else "")
                    self.assertIn(name, out)
                    if ":" in word and word.split(":", 1)[1]:
                        choices = word.split(":", 1)[1].split(",")
                        rc, out_values = self.complete(*command, option_name(word), "")
                        self.assertEqual((rc, out_values), (0, choices))

    def bash_completion(self, *words):
        """Run completions/tpp.bash for WORDS; returns COMPREPLY and the compopt calls."""
        script = (
            'compopt() { echo "compopt $*" >> "$COMPOPT_LOG"; }; source "$1"; shift; '
            'COMP_WORDS=(tpp "$@"); COMP_CWORD=$#; _tpp; printf "%s\\n" "${COMPREPLY[@]}"'
        )
        log = Path(self.tmp.name) / "compopt.log"
        log.unlink(missing_ok=True)
        env = dict(self.env)
        env["PATH"] = "%s:%s" % (self.tpp.parent, env["PATH"])
        env["COMPOPT_LOG"] = str(log)
        res = subprocess.run(
            [BASH, "-c", script, "bash", str(PKG / "completions" / "tpp.bash"), *words],
            capture_output=True,
            text=True,
            env=env,
            timeout=30,
        )
        return res.stdout.split(), log.read_text() if log.exists() else ""

    def test_bash_completion_script(self):
        out, err = self.bash_completion("vibrate", "-")
        self.assertEqual(sorted(out), ["--help", "--json", "-d", "-f"])
        self.assertNotIn("nospace", err)
        out, err = self.bash_completion("location", "-p", "n")
        self.assertEqual(out, ["network"])
        out, err = self.bash_completion("sms", "list", "--message-t")
        self.assertEqual(out, ["--message-type="])
        self.assertIn("compopt -o nospace", err)
        out, err = self.bash_completion("vibrate", "-d", "")
        self.assertEqual(out, [])
        self.assertIn("compopt -o default", err)

    def test_plugin_completion_is_delegated(self):
        self.plugin(
            "myplug",
            'if [[ $1 == __complete ]]; then shift; printf "%s\\n" "words:$*" ids; exit 0; fi',
        )
        rc, out = self.complete("myplug", "focus", "")
        self.assertEqual((rc, out), (0, ["words:focus", "ids"]))
        rc, out = self.complete("my")
        self.assertIn("myplug", out)

    def test_plugin_completion_status_is_passed_on(self):
        self.plugin("myplug", "exit 1")
        rc, _ = self.complete("myplug", "x")
        self.assertEqual(rc, 1)

    def test_help_comes_from_the_binary(self):
        # No app is involved: the usage text is in the binary
        for argv in (
            ("sensor", "--help", "--json"),
            ("sensor", "--json", "-h"),
            ("--json", "sensor", "--help"),
            ("saf", "read", "--help", "--json"),
            ("--json", "saf", "read", "-h"),
            ("help", "sensor"),
            ("wifi", "enable", "--help"),
            ("vibrate", "-h"),
            ("help", "vibrate"),
        ):
            with self.subTest(argv=argv):
                res = self.run_tpp(*argv)
                self.assertEqual(res.returncode, 0, res.stderr)
                self.assertEqual(res.stderr, b"")
                self.assertRegex(res.stdout.decode(), r"(?i)usage")
        res = self.run_tpp("--help")
        self.assertEqual(res.returncode, 0)
        self.assertIn(b"Usage: tpp", res.stdout)
        self.assertIn(b"wifi", res.stdout)
        res = self.run_tpp("wifi", "--help")
        self.assertEqual(res.returncode, 0)
        self.assertIn(b"scan", res.stdout)
        res = self.run_tpp("help", "wifi")
        self.assertIn(b"scan", res.stdout)

    def test_a_group_without_an_action_is_a_usage_error(self):
        res = self.run_tpp("wifi")
        self.assertEqual(res.returncode, 2)
        self.assertIn(b"connection", res.stderr)

    def test_no_command_script_is_needed(self):
        # The commands are in the binary: an empty prefix is enough to list and
        # to complete them, and a stale scripts directory is not looked at.
        stale = self.libexec / "commands"
        stale.mkdir()
        (stale / "vibrate").write_text("#!%s\necho stale\n" % BASH)
        (stale / "vibrate").chmod(0o755)
        res = self.run_tpp("vibrate", "-h")
        self.assertNotIn(b"stale", res.stdout)
        self.assertIn(b"vibrate", res.stdout.lower())

    def test_a_plugin_does_not_shadow_a_command(self):
        self.plugin("vibrate", "echo plugin")
        res = self.run_tpp("vibrate", "--help")
        self.assertNotIn(b"plugin", res.stdout)


if __name__ == "__main__":
    unittest.main()
