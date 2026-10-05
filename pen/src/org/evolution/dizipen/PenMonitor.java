/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.dizipen;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;

import java.util.Set;

/**
 * Tracks whether a Redmi / POCO Smart Pen is connected over Bluetooth and reports
 * transitions to the touch IC through vendor.pen.state (see init.dizi.rc).
 * The kernel counts connects, so only real transitions are reported.
 *
 * XiaomiParts' "Always enable pen input" switch is stored in Settings.Secure,
 * because only system_app (this app), not devicesettings_app (XiaomiParts),
 * may set persist.vendor.pen.force. It is copied to the property here.
 */
public final class PenMonitor implements InputManager.InputDeviceListener {

    private static final String TAG = "DiziPen";

    // Bluetooth HID identifiers (PnP ID): Redmi Smart Pen (M80P) 0x4e83,
    // POCO Smart Pen (N83C) 0x3283. Keep in sync with KeyHandler.
    private static final int PEN_VENDOR_ID = 0x0022;
    private static final Set<Integer> PEN_PRODUCT_IDS = Set.of(0x4e83, 0x3283);

    private static final String PROP_STATE = "vendor.pen.state";
    private static final String PROP_FORCE = "persist.vendor.pen.force";
    // Keep in sync with XiaomiParts PenSettingsFragment.
    private static final String SETTING_FORCE = "dizi_pen_force";

    private final ContentResolver mResolver;
    private final InputManager mInputManager;
    private final PenPairer mPairer;
    private boolean mConnected;

    PenMonitor(Context context, PenPairer pairer) {
        mResolver = context.getContentResolver();
        mInputManager = context.getSystemService(InputManager.class);
        mPairer = pairer;
    }

    void start() {
        mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_FORCE), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        applyForceSetting();
                    }
                });
        applyForceSetting();
        mInputManager.registerInputDeviceListener(this, null);
        // Re-evaluate when persist.vendor.pen.force is toggled (e.g. via adb).
        SystemProperties.addChangeCallback(this::refresh);
        refresh();
        mPairer.setPenConnected(mConnected);
    }

    private void applyForceSetting() {
        // Unset: leave the property alone (it may have been set via adb).
        int force = Settings.Secure.getInt(mResolver, SETTING_FORCE, -1);
        if (force >= 0) {
            SystemProperties.set(PROP_FORCE, force != 0 ? "true" : "false");
        }
    }

    private boolean isPen(int deviceId) {
        InputDevice device = mInputManager.getInputDevice(deviceId);
        return device != null && device.isExternal()
                && device.getVendorId() == PEN_VENDOR_ID
                && PEN_PRODUCT_IDS.contains(device.getProductId());
    }

    private synchronized void refresh() {
        boolean connected = SystemProperties.getBoolean(PROP_FORCE, false);
        for (int id : mInputManager.getInputDeviceIds()) {
            if (isPen(id)) {
                connected = true;
                break;
            }
        }
        if (connected == mConnected) {
            return;
        }
        mConnected = connected;
        Log.i(TAG, "pen " + (connected ? "connected" : "disconnected"));
        SystemProperties.set(PROP_STATE, connected ? "connected" : "disconnected");
        mPairer.setPenConnected(connected);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refresh();
    }
}
