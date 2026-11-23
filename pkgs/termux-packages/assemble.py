"""Assemble a Termux runtime closure and generate its guest launchers."""

import os
from pathlib import Path
from shlex import quote
import shutil
import subprocess
import sys

TERMUX_PREFIX = "/data/data/com.termux/files/usr"
ANDROID_LINKER = "/system/bin/linker64"


def unpack(package, directory):
    subprocess.run(["dpkg-deb", "--extract", package, directory], check=True)


def relocate(prefix):
    for directory, subdirs, files in os.walk(prefix):
        for name in subdirs + files:
            path = Path(directory, name)
            if path.is_symlink():
                target = os.readlink(path)
                if target.startswith(TERMUX_PREFIX + "/"):
                    path.unlink()
                    path.symlink_to(str(prefix) + target[len(TERMUX_PREFIX) :])
                continue
            if name in subdirs:
                continue
            with path.open("rb") as stream:
                if stream.read(4) != b"\x7fELF":
                    continue
            rpath = subprocess.run(
                ["patchelf", "--print-rpath", path], capture_output=True, text=True
            )
            if rpath.returncode == 0:
                paths = (
                    rpath.stdout.strip().replace(TERMUX_PREFIX, str(prefix)).split(":")
                )
                paths.append(str(prefix / "lib"))
                subprocess.run(
                    [
                        "patchelf",
                        "--page-size",
                        "16384",
                        "--set-rpath",
                        ":".join(dict.fromkeys(filter(None, paths))),
                        path,
                    ],
                    check=True,
                )


def launcher(program, prefix, wrappers, shell):
    with program.open("rb") as stream:
        signature = stream.read(4)
        if signature.startswith(b"#!"):
            stream.seek(2)
            interpreter = stream.readline().decode().strip().split(maxsplit=1)

    arguments = [str(program)]
    if signature == b"\x7fELF":
        result = subprocess.run(
            ["patchelf", "--print-interpreter", program], capture_output=True, text=True
        )
        if result.stdout.strip() == ANDROID_LINKER:
            arguments.insert(0, ANDROID_LINKER)
        if program.name == "bash":
            arguments.extend(["--noprofile", "--norc"])
    elif signature.startswith(b"#!") and interpreter:
        if interpreter[0].startswith(TERMUX_PREFIX + "/bin/"):
            interpreter[0] = str(wrappers / Path(interpreter[0]).name)
        arguments = interpreter + arguments

    executable = " ".join(map(quote, arguments))
    wrapper = wrappers / program.name
    wrapper.write_text(
        f"#!{shell}\n"
        f'export PATH="$PATH":{quote(str(wrappers))}\n'
        f"export TERMINFO_DIRS={quote(str(prefix / 'lib/terminfo'))}:\"${{TERMINFO_DIRS-}}\"\n"
        f'exec {executable} "$@"\n'
    )
    wrapper.chmod(0o755)


def main(out, shell, package, *dependencies):
    output = Path(out)
    wrappers = output / "libexec" / "wrappers"
    wrappers.mkdir(parents=True)
    exposed = output / "bin"
    exposed.mkdir()

    unpack(package, "selected")
    selected = Path("selected" + TERMUX_PREFIX + "/bin")
    selected_names = {path.name for path in selected.glob("*")}
    for dependency in dependencies:
        unpack(dependency, "root")
    unpack(package, "root")

    prefix = output / "libexec" / "termux"
    payload = Path("root" + TERMUX_PREFIX)
    if payload.exists():
        shutil.move(payload, prefix)
    relocate(prefix)
    for program in sorted((prefix / "bin").glob("*")):
        if (
            program.is_file()
            and program.resolve().is_relative_to(prefix)
            and os.access(program, os.X_OK)
        ):
            launcher(program, prefix, wrappers, shell)
            if program.name in selected_names:
                (exposed / program.name).symlink_to(
                    f"../libexec/wrappers/{program.name}"
                )


if __name__ == "__main__":
    main(*sys.argv[1:])
