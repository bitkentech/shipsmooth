# Plan 99 — KDE Explorer App (Qt5 C++ scaffold)

## Context

A minimal Qt5 (C++) desktop application, `kde-app/`, scaffolded to explore
shipsmooth interactively. It runs natively in a KDE/Plasma session and is
deliberately structured so KDE Frameworks (KXmlGui, KConfig, Kirigami, ...)
can be layered on later without restructuring.

This is exploratory tooling, not a shipped shipsmooth feature. It lives in a
self-contained `kde-app/` subtree, cleanly separated from the Java/Gradle
shipsmooth project it sits beside.

**Feature reference / backlog note:** No permanent backlog feature exists for
this — it is a fresh exploration surface. The feature/backlog concept is itself
being retired (see repo backlog note); the Linear MCP was offline at planning
time, so this Context section stands in for a `<backlog-issue>` reference per
the "feature definitions live in the plan Context" convention.

### Environment facts (established during planning)

- KDE Frameworks 5 *runtime* + Plasma are installed; no C++/Qt *dev* packages
  were present initially.
- Installed for this app: `qtbase5-dev`, `qtbase5-dev-tools`, `cmake` (g++ already present).
- Qt 5.15.13. cmake 3.28.3.
- Full KF5 C++ integration would additionally need `extra-cmake-modules` +
  `libkf5*-dev`.

### Decisions

- **Plain Qt5 Widgets now, KDE Frameworks later.** Lighter dependency
  footprint for both developer and end user; drops KDE-specific integration
  until there is demand for it. `CMakeLists.txt` and `README.md` mark the
  upgrade path.
- **C++, not PyQt5.** PyQt5 would build with zero installs, but a compiled Qt
  binary is the "real KDE app" direction and matches the intent to extend
  toward native KDE Frameworks.
- **Self-contained subtree.** `kde-app/` does not touch the Java build.

## Tasks

### Task 1: Qt5 C++ app scaffold [Low]

Create a buildable, runnable Qt5 Widgets app under `kde-app/`:
`CMakeLists.txt` (Qt5 Widgets, AUTOMOC/UIC/RCC, KF5-upgrade comments), `src/main.cpp`
(QApplication entry point), `src/MainWindow.{h,cpp}` (QMainWindow with File/Help
menus, central widget, a Ping button + pong counter, status bar), and a
`README.md` covering build/run and the KDE-Frameworks upgrade path. Verify it
configures, compiles, and launches (offscreen smoke test).

*Status: already implemented and verified during this session; this task
captures that work.*

### Task 2: Gitignore build artifacts [Low]

*Depends-on: 1*

Add a `.gitignore` for `kde-app/build/` (and any CMake cruft) so compiled
output is never committed.

### Task 3: shipsmooth CLI panel [Medium]

*Depends-on: 1*

Add a panel/action to the window that shells out to the shipsmooth CLI (e.g.
`store info`, `plan resume`) and renders the result — the first genuine
"explore shipsmooth" surface. Handle CLI-absent / error output gracefully.

### Task 4: Read .shipsmooth/ state [Medium]

*Depends-on: 3*

Surface local plan/task state by reading `.shipsmooth/plans/` (plan list, task
XML summary) into the UI, so the app reflects the current repo's shipsmooth
state without shelling out for everything.
