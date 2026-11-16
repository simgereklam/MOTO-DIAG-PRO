#!/usr/bin/env python3
"""Apply the MOTO DIAG PRO embedded-workspace patch to a SavvyCAN QT6WIP checkout.

The SavvyCAN CAN-analysis workspace remains native Qt. The existing MOTO DIAG PRO
Flask diagnostic UI is hosted in a QtWebEngine tab in the same top-level window.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    content = path.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one patch point, found {count}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="SavvyCAN QT6WIP source directory")
    parser.add_argument("--backend", required=True, help="Built MOTO DIAG PRO backend executable")
    args = parser.parse_args()

    root = Path(args.source).resolve()
    backend = Path(args.backend).resolve()
    project = root / "SavvyCAN.pro"
    cpp = root / "mainwindow.cpp"
    if not project.is_file() or not cpp.is_file() or not (root / "mainwindow.h").is_file():
        raise SystemExit("SavvyCAN source tree is incomplete; expected SavvyCAN.pro and mainwindow files")
    if not backend.is_file():
        raise SystemExit(f"Backend executable not found: {backend}")

    pro = project.read_text(encoding="utf-8")
    if "webenginewidgets" not in pro:
        if "QT = core gui" not in pro:
            raise SystemExit("Unknown SavvyCAN Qt module declaration; refusing unsafe project edit")
        pro = pro.replace("QT = core gui", "QT = core gui webenginewidgets", 1)
    if "TARGET = SavvyCAN" in pro:
        pro = pro.replace("TARGET = SavvyCAN", 'TARGET = MOTO_DIAG_PRO', 1)
    elif 'TARGET = MOTO_DIAG_PRO' not in pro:
        raise SystemExit("Unknown SavvyCAN target declaration")
    project.write_text(pro, encoding="utf-8")

    includes = """// MOTO DIAG PRO embedded workspace integration
#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QHostAddress>
#include <QProcess>
#include <QTabWidget>
#include <QTcpSocket>
#include <QTimer>
#include <QUrl>
#include <QWebEngineView>
#include "connections/canconfactory.h"
"""
    replace_once(cpp, '#include "mainwindow.h"\n',
                 '#include "mainwindow.h"\n' + includes,
                 "mainwindow.cpp includes")

    integration = r'''
    // MOTO DIAG PRO and SavvyCAN share one top-level Qt application window.
    // The original SavvyCAN central workspace is retained as the first tab.
    QWidget *savvyCanWorkspace = takeCentralWidget();
    QTabWidget *unifiedWorkspace = new QTabWidget(this);
    unifiedWorkspace->setObjectName("motoDiagUnifiedWorkspace");
    unifiedWorkspace->addTab(savvyCanWorkspace, tr("CAN ANALİZ"));

    QWebEngineView *motoDiagView = new QWebEngineView(unifiedWorkspace);
    motoDiagView->setObjectName("motoDiagEmbeddedView");
    motoDiagView->setHtml("<html><body style='background:#101820;color:#dce7f2;font-family:sans-serif;padding:28px'><h2>MOTO DIAG PRO</h2><p>Yerel teşhis servisi başlatılıyor...</p></body></html>");
    unifiedWorkspace->addTab(motoDiagView, tr("MOTO DIAG PRO"));
    setCentralWidget(unifiedWorkspace);
    unifiedWorkspace->setCurrentWidget(motoDiagView);

    // Feed the Python/J2534 CAN RX bridge into SavvyCAN through its native socketcand connection.
    CANConnection *motoDiagCanBridge = CanConFactory::create(
        CANCon::KAYAK, "can0@can://127.0.0.1:29536/", "", 0, 500000, false, 0, false, true);
    if (motoDiagCanBridge)
    {
        CANConManager::getInstance()->add(motoDiagCanBridge);
        motoDiagCanBridge->start();
    }

    const QString backendPath = QDir(QCoreApplication::applicationDirPath())
        .filePath("backend/MOTO-DIAG-PRO-Backend.exe");
    if (QFileInfo::exists(backendPath))
    {
        QProcess *motoDiagBackend = new QProcess(this);
        motoDiagBackend->setProgram(backendPath);
        motoDiagBackend->setWorkingDirectory(QCoreApplication::applicationDirPath());
        connect(motoDiagBackend, &QProcess::errorOccurred, motoDiagView,
                [motoDiagView](QProcess::ProcessError) {
            motoDiagView->setHtml("<html><body style='background:#101820;color:#ff9b9b;font-family:sans-serif;padding:28px'><h2>MOTO DIAG PRO başlatılamadı</h2><p>Yerel teşhis servisi açılamadı. Kurulum dosyalarının eksiksiz olduğunu kontrol edin.</p></body></html>");
        });
        motoDiagBackend->start();

        QTimer *backendProbeTimer = new QTimer(motoDiagView);
        connect(backendProbeTimer, &QTimer::timeout, motoDiagView,
                [motoDiagView, backendProbeTimer]() {
            QTcpSocket probe;
            probe.connectToHost(QHostAddress::LocalHost, 5000);
            if (probe.waitForConnected(150))
            {
                probe.disconnectFromHost();
                backendProbeTimer->stop();
                motoDiagView->load(QUrl("http://127.0.0.1:5000/"));
            }
        });
        backendProbeTimer->start(300);
        QTimer::singleShot(20000, motoDiagView, [motoDiagView, backendProbeTimer]() {
            if (backendProbeTimer->isActive())
            {
                backendProbeTimer->stop();
                motoDiagView->setHtml("<html><body style='background:#101820;color:#ffcc80;font-family:sans-serif;padding:28px'><h2>MOTO DIAG PRO servisi yanıt vermedi</h2><p>Yerel teşhis servisi 20 saniye içinde hazır olmadı. Programı kapatıp yeniden açın.</p></body></html>");
            }
        });
    }
    else
    {
        motoDiagView->setHtml("<html><body style='background:#101820;color:#ff9b9b;font-family:sans-serif;padding:28px'><h2>MOTO DIAG PRO bileşeni bulunamadı</h2><p>backend/MOTO-DIAG-PRO-Backend.exe dosyası kurulum paketinde yok.</p></body></html>");
    }
'''
    replace_once(cpp, "    ui->setupUi(this);\n",
                 "    ui->setupUi(this);\n" + integration,
                 "MainWindow constructor integration")

    replace_once(cpp,
                 '    this->setWindowTitle("Savvy CAN V" + QString::number(VERSION) + " [Built " + QString(__DATE__) +"]");',
                 '    this->setWindowTitle("MOTO DIAG PRO | SavvyCAN V" + QString::number(VERSION));',
                 "unified application window title")

    target_backend = root / "backend" / "MOTO-DIAG-PRO-Backend.exe"
    target_backend.parent.mkdir(parents=True, exist_ok=True)
    target_backend.write_bytes(backend.read_bytes())
    print("SavvyCAN patched: one Qt window, CAN ANALİZ + embedded MOTO DIAG PRO tabs.")


if __name__ == "__main__":
    main()
