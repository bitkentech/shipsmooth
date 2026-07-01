cmake_minimum_required(VERSION 3.16)

project(ShipSmoothExplorer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Qt build helpers: run moc/uic/rcc automatically.
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTORCC ON)

# Plain Qt5 Widgets for now. To layer KDE Frameworks on later, add e.g.:
#   find_package(KF5CoreAddons)  find_package(KF5XmlGui)  ...
# and link KF5::CoreAddons KF5::XmlGui below.
find_package(Qt5 REQUIRED COMPONENTS Widgets)

add_executable(shipsmooth-explorer
    src/main.cpp
    src/MainWindow.cpp
    src/MainWindow.h
)

target_link_libraries(shipsmooth-explorer PRIVATE Qt5::Widgets)

install(TARGETS shipsmooth-explorer RUNTIME DESTINATION bin)
