# Host ADB tools: Rust replacement contract

The end state is standalone Rust executables for `tpp-adb-shell` and `tpp-adb-ssh-proxy`, sharing device resolution code. Python is retained only as a test oracle during the port, not as a runtime dependency. The unit of parity is a complete invocation, including its arguments, environment, input streams, ADB and TLS interactions, output streams, exit status, and filesystem changes. Test credentials must be synthetic. Never record private keys, tokens, or user device configuration in test logs.

The replacement must preserve:

- CLI parsing, help, completion output, validation and error exit codes, including command argument boundaries and shell selection.
- Device configuration size, ownership and permission checks; strict schema validation; alias matching and unambiguous ready-device selection; and existing credential directory selection without merging or replacing identities.
- Explicit provisioning and fingerprint approval, token handling, certificate generation and storage permissions, server identity verification and mutual TLS authentication. Existing credentials must remain usable. No automatic trust, re-enrollment, credential migration or security downgrade is permitted.
- Authorization listing and revocation, framed protocol limits, shell and command streams, terminal handling and signal behavior.
- ADB forwarding allocation and cleanup on success, errors and interruption. Run-as fallback must remain restricted to the existing conditions.
- SSH proxy listener probing, timeout and failure distinctions, optional start command, re-probing after a start failure, and the final byte-transparent connection without consuming SSH input during probing.

Differences in nondeterministic generated keys, assigned ports, timestamps and operating-system error wording are acceptable only when they do not change security decisions, output that scripts rely on, or externally observable control flow. Compare cryptographic artifacts by validity, permissions, identity relationships and protocol behavior, not raw random bytes.

Before cutover, run the existing 77 Python tests as characterization and build a process-level differential corpus covering successful and failing argument parsing, alias/config validation, credential selection and permissions, provisioning/approval, TLS identity failures, authorization operations, shell and command I/O, signals and forward cleanup, and proxy listener/start/timeout paths. Use mock ADB and a local test TLS peer. Report the number of cases compared and differences; add tests for discovered gaps. The Nix package must run this corpus and must not remove Python from production until the Rust replacement passes it and safe device acceptance has been performed.
