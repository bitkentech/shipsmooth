#pragma once

#include <QMainWindow>

class QLabel;

// The application's main window.
//
// Kept deliberately small so it can be extended to explore shipsmooth.
// When KDE Frameworks integration is wanted, this can inherit from
// KXmlGuiWindow instead of QMainWindow and use KDE standard actions.
class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);

private slots:
    void onPing();
    void onAbout();

private:
    void setupMenus();
    void setupCentralWidget();

    QLabel *m_status;
    int m_pingCount;
};
