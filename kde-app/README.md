# ShipSmooth Explorer

A minimal Qt5 (C++) desktop app, scaffolded to explore shipsmooth. Runs
natively in a KDE/Plasma session and is structured so KDE Frameworks
(KXmlGui, KConfig, Kirigami, ...) can be layered on later.

## Build

```sh
cmake -S . -B build
cmake --build build
```

## Run

```sh
./build/shipsmooth-explorer
```

## Structure

- `src/main.cpp` — entry point; sets up `QApplication`.
- `src/MainWindow.{h,cpp}` — the main window (menus, central widget, status bar).
- `CMakeLists.txt` — build config. Comments mark where to add KDE Frameworks.

## Extending toward KDE Frameworks

When full KDE integration is wanted:

1. `apt install extra-cmake-modules libkf5coreaddons-dev libkf5xmlgui-dev libkf5i18n-dev`
2. In `CMakeLists.txt`, `find_package(KF5 ... COMPONENTS CoreAddons XmlGui I18n)`
   and link `KF5::CoreAddons KF5::XmlGui KF5::I18n`.
3. Change `MainWindow` to inherit `KXmlGuiWindow` and use `KStandardAction`s.
