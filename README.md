# Tanager

**English** | [한국어](README.ko.md)

[![CI](https://github.com/jjjuuuun/tanager/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jjjuuuun/tanager/actions/workflows/ci.yml)
[![Release](https://img.shields.io/badge/release-v0.3.0-blue)](https://github.com/jjjuuuun/tanager/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A desktop terminal manager for Windows. Split **WSL**, **Windows PowerShell 5.1** and **cmd** terminals in one window like tmux, and keep every directory you have worked in as a **folder tree** — so after a reboot you know exactly where you left off.

> [!NOTE]
> **Latest release: v0.3.0 — history and folder tree.** One terminal per window, and every directory you work in is kept in a folder tree on the left. Splits and opening a folder from the tree arrive in **v0.4.0**. See the [roadmap](#roadmap).

## Why Tanager?

If you run Claude Code (or any long-lived shell work) inside WSL, a reboot wipes out where you were: which project, which subfolder, which shell. Tanager remembers every working directory per environment, shows them as a tree on the left, and reopens your layout with each pane back in its last directory.

## Features

| Feature | Status |
|---|---|
| WSL distros, Windows PowerShell 5.1 and cmd in one window (auto-detected) | v0.2.0 |
| Soft, eye-friendly theme (rounded corners, short transitions), Korean-ready terminal font (D2Coding) | v0.2.0 |
| Folder → session tree of every directory you visited, per environment; pin, alias, search | v0.3.0 |
| tmux-style splits — split, resize, move, swap, zoom, close — **with keyboard and mouse** | v0.4.0 |
| Restore tabs, splits and each pane's last directory after a restart | v0.5.0 |
| System / dark / light theme, status bar with CPU, memory and disk (including the WSL VM) | v0.5.0 |
| Toolkit panel: Claude Code skills, subagents, `CLAUDE.md`, MCP servers, hooks and permissions available in the selected folder | v1.0.0 |
| macOS | v1.1.0 |

PowerShell 7 (`pwsh`) and custom shell profiles are intentionally out of scope.

## Install

**Requirements:** Windows 10 or 11 (x64; tested on Windows 11). No Java installation needed — a trimmed Java runtime is bundled.

Download one of these from the [latest release](https://github.com/jjjuuuun/tanager/releases). Neither needs administrator rights.

- **Installer** `Tanager-<version>-windows-x64.msi` — installs for the current user into `%LOCALAPPDATA%\Tanager` and adds **Tanager** to the Start menu. To uninstall, remove Tanager in **Settings → Apps**.
- **Zip** `Tanager-<version>-windows-x64.zip` — unzip it anywhere and run `Tanager\Tanager.exe`. To uninstall, delete the folder.

The executable is not code-signed yet, so Windows SmartScreen may warn you. Choose **More info → Run anyway**.

## Usage

### What works in v0.3.0

1. Launch `Tanager.exe`. Tanager lists the shells it found on this PC — your WSL distros, Windows PowerShell 5.1 and cmd.
2. Pick one with the arrow keys and <kbd>Enter</kbd> (or <kbd>1</kbd>–<kbd>9</kbd>). Leave the "open this shell directly next time" option on to skip the list from then on.
3. The shell runs in a full terminal (ConPTY). Korean text and IME input work thanks to the bundled D2Coding font.
4. As you `cd`, the pane header and the status bar show where the shell is, and the folder appears in the **sidebar** under its environment ("WSL · Ubuntu", "Windows"). Running sessions hang under the folder they are in.
5. In the sidebar:
   - <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F</kbd> searches the tree. <kbd>Esc</kbd> clears the search.
   - <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>E</kbd> shows or hides the sidebar.
   - Right-click a folder (or press the menu key or <kbd>Shift</kbd>+<kbd>F10</kbd>) to copy its path, pin it, give it an alias, hide it or delete it from history. <kbd>Delete</kbd> deletes the selected entry.
6. When the shell exits, the pane shows its exit code:
   - <kbd>Enter</kbd> restarts it in the last directory it reported.
   - <kbd>Tab</kbd> picks another shell.
   - <kbd>Esc</kbd> closes the window.

History is saved to `%APPDATA%\Tanager\state.json`.

The interface is in Korean for now.

### Planned key bindings (from v0.4.0)

Default key bindings (Windows). Everything can also be done with the mouse — pane header buttons, dragging dividers, and dragging panes or sidebar folders onto drop zones. Bindings may still change before v1.0.

| Action | Keys | Mouse |
|---|---|---|
| Split right / down | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>=</kbd> / <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>-</kbd> | Pane header ▥ / ▤ |
| Move focus | <kbd>Alt</kbd>+<kbd>←↑→↓</kbd> | Click a pane |
| Resize (5 %) | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>←↑→↓</kbd> | Drag the divider (double-click = 50:50) |
| Zoom | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Z</kbd> | Pane header □ or double-click the header |
| Close pane | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> | Pane header × |
| Move / swap panes | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>←→</kbd> (swap) | Drag the header ≡ onto another pane's edge (move) or centre (swap) |
| New tab / switch tab | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> / <kbd>Ctrl</kbd>+<kbd>Tab</kbd> | Tab bar |
| Open a folder from history | <kbd>Enter</kbd> in the sidebar | Double-click, or drag it onto a pane |
| Toggle toolkit | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>K</kbd> | — |

Tanager learns where each shell is through a small shell-integration hook that reports the current directory after every prompt (OSC 7 for bash, OSC 9;9 for PowerShell and cmd). It never records the commands you type or their output — only directories.

## Build from source

**Requirements**

- Git
- Any JDK 17 or newer on `PATH` to start Gradle. The project itself builds with **Java 25**, which Gradle downloads automatically if it is missing.
- Windows for running the app and building the package. Core modules and tests also build on Linux / WSL.

```bash
git clone https://github.com/jjjuuuun/tanager.git
cd tanager
./gradlew check                      # format check + all tests + architecture rules + coverage
./gradlew :bootstrap:run             # run the app
./gradlew :bootstrap:jpackageImage   # Windows: app/bootstrap/build/jpackage/Tanager/Tanager.exe
```

On Windows use `gradlew.bat` instead of `./gradlew`. When the same working tree is built from both WSL and Windows, WSL writes to `build/wsl/` so the two never mix.

## Project structure

Hexagonal architecture in Gradle modules ([ADR-0002](docs/adr/0002-hexagonal-multimodule.md)). Dependencies point inwards and are enforced by ArchUnit.

```
app/
├── domain/                pure Java rules (no I/O, UI or threads)
├── application/           use cases and ports
├── adapter/
│   ├── pty/               terminals via pty4j / ConPTY
│   ├── persistence/       state and settings (JSON)
│   └── toolkit/claude/    reads Claude Code configuration (read-only)
├── platform/
│   ├── windows/           WSL · PowerShell 5.1 · cmd specifics
│   └── macos/             macOS specifics (v1.1.0)
├── ui/                    Swing + FlatLaf + JediTerm UI
├── bootstrap/             composition root and packaging
└── architecture/          ArchUnit rules
build-logic/               shared Gradle convention plugins
docs/                      plans (HTML) and ADRs
```

Tech stack: Java 25 · Gradle 9.9 · Swing + FlatLaf · JediTerm · pty4j · Jackson · JUnit 6 · jqwik · ArchUnit · jpackage.

## Contributing

- **Branches:** `dev` is the integration branch; every change goes through a pull request into `dev` and is squash-merged. `main` only receives releases (`dev → main` merge commit, then a `vX.Y.Z` tag).
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) with a required module scope, e.g. `feat(ui): add sidebar folder tree`.
- **Tests first:** RED → GREEN → REFACTOR. Domain coverage ≥ 90 %, application ≥ 80 %.
- **One-time setup after cloning:**

  ```bash
  git config core.hooksPath .githooks     # commit-message and branch checks
  git config commit.template .gitmessage
  git config core.autocrlf false          # line endings come from .gitattributes
  ```

The full rules (in Korean) are in [`.claude/rules/git.md`](.claude/rules/git.md).

## Documentation

- [Development plan](docs/plans/01-프로젝트-구성-설계.html) — stack, structure, methodology, Git strategy, CI (Korean, HTML)
- [Software design description](docs/plans/02-SDD.html) — requirements, design, roadmap (Korean, HTML)
- [Architecture decision records](docs/adr/)

## Roadmap

| Version | Milestone |
|---|---|
| v0.1.0 | Scaffolding — modules, architecture rules, platform selection, packaging ✅ |
| v0.2.0 | Single terminal — shell detection, one terminal pane, restart on exit, soft theme ✅ |
| **v0.3.0** | History and folder tree — directory tracking, folder → session tree, pin and search ✅ |
| v0.4.0 | Splits — keyboard and mouse |
| v0.5.0 | Restore, tabs, settings, themes, resource monitor |
| v1.0.0 | Toolkit panel and stabilisation |
| v1.1.0 | macOS |

## License

Tanager is released under the [MIT License](LICENSE). Third-party components (JediTerm, pty4j, JNA, FlatLaf, Jackson and others) are used under their own licenses.
