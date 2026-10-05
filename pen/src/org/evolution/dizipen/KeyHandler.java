/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.dizipen;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

import java.util.Set;

/**
 * Remaps the Redmi / POCO Smart Pen buttons. Loaded into system_server by
 * PhoneWindowManager (config_deviceKeyHandlerLibs/Classes), so it must stay
 * small and never throw. The pen's "Keyboard" HID device sends KEY_PAGEUP (upper
 * button) and KEY_PAGEDOWN (lower); our key layouts Vendor_0022_Product_{4e83,3283}.kl
 * map them to KEYCODE_STYLUS_BUTTON_PRIMARY / _SECONDARY. Both forms are handled,
 * only on those devices.
 * Actions are chosen in XiaomiParts and stored in Settings.Secure.
 */
public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = "DiziPenKeys";

    // Keep in sync with XiaomiParts PenSettingsFragment.
    public static final String KEY_ACTION_UP = "dizi_pen_button_up_action";
    public static final String KEY_ACTION_DOWN = "dizi_pen_button_down_action";
    public static final String KEY_APP_UP = "dizi_pen_button_up_app";
    public static final String KEY_APP_DOWN = "dizi_pen_button_down_app";

    private static final int PEN_VENDOR_ID = 0x0022;
    // Redmi Smart Pen, POCO Smart Pen. Keep in sync with PenMonitor.
    private static final Set<Integer> PEN_PRODUCT_IDS = Set.of(0x4e83, 0x3283);

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public KeyHandler(Context context) {
        mContext = context;
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up;
        switch (code) {
            case KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY:
            case KeyEvent.KEYCODE_PAGE_UP:
                up = true;
                break;
            case KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                up = false;
                break;
            default:
                return event;
        }
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        if (device == null || device.getVendorId() != PEN_VENDOR_ID
                || !PEN_PRODUCT_IDS.contains(device.getProductId())) {
            return event;
        }
        ContentResolver cr = mContext.getContentResolver();
        String action = Settings.Secure.getStringForUser(cr,
                up ? KEY_ACTION_UP : KEY_ACTION_DOWN, UserHandle.USER_CURRENT);
        if (action == null || action.isEmpty() || action.equals("default")) {
            return event;  // the pen's own key (stylus button)
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            String app = Settings.Secure.getStringForUser(cr,
                    up ? KEY_APP_UP : KEY_APP_DOWN, UserHandle.USER_CURRENT);
            // Leave the input dispatch path before acting (injection re-enters it).
            mHandler.post(() -> perform(action, app));
        }
        return null;  // consume both the down and the up event
    }

    private void perform(String action, String app) {
        try {
            switch (action) {
                case "none":
                    break;
                case "back":
                    injectKey(KeyEvent.KEYCODE_BACK);
                    break;
                case "home":
                    injectKey(KeyEvent.KEYCODE_HOME);
                    break;
                case "recents":
                    injectKey(KeyEvent.KEYCODE_APP_SWITCH);
                    break;
                case "screenshot":
                    injectKey(KeyEvent.KEYCODE_SYSRQ);
                    break;
                case "play_pause":
                    injectKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    break;
                case "notes":
                    launch(new Intent(Intent.ACTION_CREATE_NOTE));
                    break;
                case "app":
                    if (app != null && !app.isEmpty()) {
                        launch(mContext.getPackageManager().getLaunchIntentForPackage(app));
                    }
                    break;
                default:
                    Log.w(TAG, "unknown pen action " + action);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "pen action " + action + " failed", e);
        }
    }

    private void launch(Intent intent) {
        if (intent == null) {
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private void injectKey(int code) {
        InputManager im = mContext.getSystemService(InputManager.class);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent ev = new KeyEvent(now, now, action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            im.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }
}
