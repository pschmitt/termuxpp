"""Generate the pinned aarch64 catalog using python3-apt and a Termux keyring."""

import argparse
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import apt
import apt_pkg

MIRROR = "https://packages.termux.dev/apt"
REPOSITORIES = {
    "termux-main": "stable main",
    "termux-root": "root stable",
    "termux-x11": "x11 main",
}


def load_catalog(root, keyring):
    (root / "etc/apt/preferences.d").mkdir(parents=True)
    (root / "var/lib/dpkg").mkdir(parents=True)
    (root / "var/lib/dpkg/status").touch()
    (root / "etc/apt/sources.list").write_text(
        "".join(
            f"deb [arch=aarch64 signed-by={keyring}] {MIRROR}/{repository} {suite}\n"
            for repository, suite in REPOSITORIES.items()
        )
    )

    apt_pkg.init_config()
    apt_pkg.config.set("APT::Architecture", "aarch64")
    apt_pkg.config.clear("APT::Architectures")
    apt_pkg.config.set("APT::Architectures::", "aarch64")
    apt_pkg.config.set("APT::Install-Recommends", "false")
    apt_pkg.config.set("APT::Install-Suggests", "false")
    apt_pkg.init_system()

    cache = apt.Cache(rootdir=str(root), memonly=True)
    cache.update()
    cache.open()
    releases = {
        repository: hashlib.sha256(
            next(
                (root / "var/lib/apt/lists").glob(f"*_{repository}_*_InRelease")
            ).read_bytes()
        ).hexdigest()
        for repository in REPOSITORIES
    }

    packages = {
        package.name: package
        for package in cache
        if package.candidate is not None and package.candidate.downloadable
    }
    entries = {}
    for name, package in sorted(packages.items()):
        candidate = package.candidate
        record = candidate.record
        entry = dict(
            version=candidate.version,
            file=candidate.uri.removeprefix(MIRROR + "/"),
            sha256=record["SHA256"],
        )
        try:
            package.mark_install()
            if not package.marked_install or cache.broken_count:
                raise SystemError("no installation plan")
            entry["dependencies"] = sorted(
                dependency.name
                for dependency in cache.get_changes()
                if dependency.name != name and dependency.marked_install
            )
        except SystemError:
            entry["dependencies"] = None
        finally:
            cache.clear()
        entries[name] = entry
    return releases, entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("keyring", type=Path, help="trusted Termux APT keyring (.gpg)")
    parser.add_argument("output", type=Path, help="path for the generated catalog")
    args = parser.parse_args()
    with TemporaryDirectory(prefix="termux-apt-") as directory:
        releases, packages = load_catalog(
            Path(directory), args.keyring.resolve(strict=True)
        )

    header = {
        "mirror": MIRROR,
        "architecture": "aarch64",
        "releaseSha256": releases,
    }
    with args.output.open("w") as output:
        output.write(json.dumps(header, indent=2)[:-2] + ',\n  "packages": {\n')
        output.write(
            ",\n".join(
                f"    {json.dumps(name)}: {json.dumps(entry, separators=(',', ':'))}"
                for name, entry in packages.items()
            )
        )
        output.write("\n  }\n}\n")
    broken = sum(entry["dependencies"] is None for entry in packages.values())
    print(
        f"Pinned {len(packages)} packages, with {broken} unsatisfiable dependency plans"
    )


if __name__ == "__main__":
    main()
