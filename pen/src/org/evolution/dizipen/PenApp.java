/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.dizipen;

import android.app.Application;
import android.content.Context;

/**
 * Starts the pen logic with the process. The app is persistent, so the system restarts it after a
 * crash; starting only on LOCKED_BOOT_COMPLETED left a restarted process without pairing.
 */
public class PenApp extends Application {

    private static PenMonitor sMonitor;

    @Override
    public void onCreate() {
        super.onCreate();
        start(this);
    }

    static synchronized void start(Context context) {
        if (sMonitor != null) {
            return;
        }
        Context app = context.getApplicationContext();
        PenPairer pairer = new PenPairer(app);
        pairer.start();
        sMonitor = new PenMonitor(app, pairer);
        sMonitor.start();
    }
}
