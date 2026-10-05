/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pen;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.settings.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redmi / POCO Smart Pen settings: status and battery, button actions (applied by
 * DiziPen's KeyHandler in system_server), always-on pen input and forgetting
 * the pen (DiziPen re-pairs it automatically when its buttons are held).
 */
public class PenSettingsFragment extends PreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    // Keep in sync with DiziPen KeyHandler.
    private static final String KEY_ACTION_UP = "dizi_pen_button_up_action";
    private static final String KEY_ACTION_DOWN = "dizi_pen_button_down_action";
    private static final String KEY_APP_UP = "dizi_pen_button_up_app";
    private static final String KEY_APP_DOWN = "dizi_pen_button_down_app";
    private static final String PROP_FORCE = "persist.vendor.pen.force";
    // Keep in sync with DiziPen PenMonitor, which copies it to PROP_FORCE:
    // devicesettings_app may read the property but not set it.
    private static final String SETTING_FORCE = "dizi_pen_force";
    // Keep in sync with DiziPen PenPairer.
    private static final Set<String> PEN_NAMES = Set.of("Redmi Smart Pen", "POCO Smart Pen");

    private static final String PREF_STATUS = "pen_status";
    private static final String PREF_FORGET = "pen_forget";
    private static final String PREF_FORCE = "pen_force_enable";

    private BluetoothAdapter mAdapter;
    private Preference mStatus;
    private Preference mForget;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pen_settings);
        mAdapter = getContext().getSystemService(BluetoothManager.class).getAdapter();

        mStatus = findPreference(PREF_STATUS);
        mForget = findPreference(PREF_FORGET);
        mForget.setOnPreferenceClickListener(p -> {
            BluetoothDevice pen = findPen();
            if (pen != null) {
                pen.removeBond();
            }
            updateStatus();
            return true;
        });

        SwitchPreferenceCompat force = findPreference(PREF_FORCE);
        force.setChecked(SystemProperties.getBoolean(PROP_FORCE, false));
        force.setOnPreferenceChangeListener(this);

        setupButton(KEY_ACTION_UP, KEY_APP_UP);
        setupButton(KEY_ACTION_DOWN, KEY_APP_DOWN);
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        getContext().registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        updateStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        getContext().unregisterReceiver(mReceiver);
    }

    private void setupButton(String actionKey, String appKey) {
        ContentResolver cr = getContext().getContentResolver();
        ListPreference action = findPreference(actionKey);
        ListPreference app = findPreference(appKey);

        String current = Settings.Secure.getString(cr, actionKey);
        action.setValue(current == null ? "default" : current);
        action.setOnPreferenceChangeListener(this);

        // Launchable apps for the "open app" action.
        PackageManager pm = getContext().getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> packages = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            if (!packages.contains(ri.activityInfo.packageName)) {
                labels.add(ri.loadLabel(pm));
                packages.add(ri.activityInfo.packageName);
            }
        }
        app.setEntries(labels.toArray(new CharSequence[0]));
        app.setEntryValues(packages.toArray(new CharSequence[0]));
        app.setValue(Settings.Secure.getString(cr, appKey));
        app.setOnPreferenceChangeListener(this);
        app.setVisible("app".equals(action.getValue()));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (PREF_FORCE.equals(key)) {
            Settings.Secure.putInt(getContext().getContentResolver(), SETTING_FORCE,
                    (Boolean) newValue ? 1 : 0);
            return true;
        }
        Settings.Secure.putString(getContext().getContentResolver(), key, (String) newValue);
        if (KEY_ACTION_UP.equals(key)) {
            findPreference(KEY_APP_UP).setVisible("app".equals(newValue));
        } else if (KEY_ACTION_DOWN.equals(key)) {
            findPreference(KEY_APP_DOWN).setVisible("app".equals(newValue));
        }
        return true;
    }

    private BluetoothDevice findPen() {
        if (mAdapter == null) {
            return null;
        }
        for (BluetoothDevice device : mAdapter.getBondedDevices()) {
            if (PEN_NAMES.contains(device.getName())) {
                return device;
            }
        }
        return null;
    }

    private void updateStatus() {
        BluetoothDevice pen = findPen();
        if (pen == null) {
            mStatus.setSummary(R.string.pen_status_not_paired);
            mForget.setEnabled(false);
            return;
        }
        mForget.setEnabled(true);
        if (!pen.isConnected()) {
            mStatus.setSummary(R.string.pen_status_paired);
            return;
        }
        int level = pen.getBatteryLevel();
        mStatus.setSummary(level >= 0
                ? getString(R.string.pen_status_connected_battery, level)
                : getString(R.string.pen_status_connected));
    }
}
