#include "MainWindow.h"

#include <QAction>
#include <QApplication>
#include <QLabel>
#include <QMenuBar>
#include <QMessageBox>
#include <QPushButton>
#include <QStatusBar>
#include <QVBoxLayout>
#include <QWidget>

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent), m_status(nullptr), m_pingCount(0) {
    setWindowTitle(tr("ShipSmooth Explorer"));
    resize(640, 420);

    setupCentralWidget();
    setupMenus();

    statusBar()->showMessage(tr("Ready"));
}

void MainWindow::setupCentralWidget() {
    auto *central = new QWidget(this);
    auto *layout = new QVBoxLayout(central);

    auto *heading = new QLabel(tr("ShipSmooth Explorer"), central);
    QFont f = heading->font();
    f.setPointSize(f.pointSize() + 6);
    f.setBold(true);
    heading->setFont(f);
    heading->setAlignment(Qt::AlignCenter);

    m_status = new QLabel(tr("Press Ping to interact."), central);
    m_status->setAlignment(Qt::AlignCenter);

    auto *pingButton = new QPushButton(tr("Ping"), central);
    connect(pingButton, &QPushButton::clicked, this, &MainWindow::onPing);

    layout->addStretch();
    layout->addWidget(heading);
    layout->addWidget(m_status);
    layout->addWidget(pingButton);
    layout->addStretch();

    setCentralWidget(central);
}

void MainWindow::setupMenus() {
    QMenu *fileMenu = menuBar()->addMenu(tr("&File"));
    QAction *quitAction = fileMenu->addAction(tr("&Quit"));
    quitAction->setShortcut(QKeySequence::Quit);
    connect(quitAction, &QAction::triggered, qApp, &QApplication::quit);

    QMenu *helpMenu = menuBar()->addMenu(tr("&Help"));
    QAction *aboutAction = helpMenu->addAction(tr("&About"));
    connect(aboutAction, &QAction::triggered, this, &MainWindow::onAbout);
}

void MainWindow::onPing() {
    ++m_pingCount;
    m_status->setText(tr("Pong #%1").arg(m_pingCount));
    statusBar()->showMessage(tr("Pinged %1 time(s)").arg(m_pingCount), 2000);
}

void MainWindow::onAbout() {
    QMessageBox::about(
        this, tr("About ShipSmooth Explorer"),
        tr("A minimal Qt5 app scaffolded to explore shipsmooth.\n\n"
           "Extend MainWindow to add features."));
}
