# Licensing

TermuxPP is based on `termux/termux-app` and is distributed under the [GNU General Public License, version 3 only](https://www.gnu.org/licenses/gpl-3.0.html) (GPL-3.0-only), except where a component or file specifies different terms. The Rust crates [`tpp-cli`](pkgs/tpp-cli), [`tpp-api`](pkgs/tpp-api), and [`tpp-session`](pkgs/tpp-session) also declare GPL-3.0-only.

## Component licenses and exceptions

- [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) include code from [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator), licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- [`termux-shared`](termux-shared/LICENSE.md) is MIT-licensed with documented GPL-3.0-only, GPL-2.0-only with Classpath exception, and Apache-2.0 exceptions; see its license file for the applicable paths and file-level terms.
- [`android/components/termux-am`](android/components/termux-am/LICENSE) is licensed under Apache-2.0.
- [`android/components/termux-float`](android/components/termux-float/LICENSE.md), [`termux-styling`](android/components/termux-styling/LICENSE.md), [`termux-tasker`](android/components/termux-tasker/LICENSE.md), and [`termux-widget`](android/components/termux-widget/LICENSE.md) retain their GPL-3.0-only notices and any exceptions described there.
- [`pkgs/tpp-api-bridge`](pkgs/tpp-api-bridge/LICENSE) retains the upstream MIT license and copyright notice.

Individual files and third-party dependencies may carry additional notices or different terms. Consult those notices and the dependency's own license when redistributing them; moving a component does not change its license.
