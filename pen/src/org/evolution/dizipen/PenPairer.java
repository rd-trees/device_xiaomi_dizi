/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.dizipen;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pairs the Redmi / POCO Smart Pen without user interaction, as HyperOS does.
 *
 * In pairing mode the pen advertises Xiaomi service data (UUID 0xFD2D) and its
 * name, but no LE discoverable flag, so Settings never lists it. While no pen
 * is connected we keep a hardware-filtered, low-power scan running and bond
 * with any matching pen. A bonded pen advertises the same way while it waits
 * to reconnect, so bonded pens are left to the HID host; one that has lost its
 * keys starts a new pairing, which onPairingRequest confirms.
 */
final class PenPairer {

    private static final String TAG = "DiziPen";

    private static final ParcelUuid XIAOMI_SERVICE =
            ParcelUuid.fromString("0000fd2d-0000-1000-8000-00805f9b34fb");
    // Keep in sync with XiaomiParts PenSettingsFragment.
    private static final Set<String> PEN_NAMES = Set.of("Redmi Smart Pen", "POCO Smart Pen");

    // Bluetooth may still be starting when the persistent process comes up at boot.
    private static final long RETRY_MS = 5_000;
    private static final long IDLE_RETRY_MS = 60_000;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRetry = this::update;
    private BluetoothAdapter mAdapter;
    private String mWaiting;

    private boolean mWanted;   // no pen connected, so pairing may be needed
    private boolean mScanning;

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleResult(result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleResult(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "pen scan failed: " + errorCode);
            mScanning = false;
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    update();
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    onBondStateChanged(intent);
                    break;
                case BluetoothDevice.ACTION_PAIRING_REQUEST:
                    onPairingRequest(intent);
                    break;
            }
        }
    };

    PenPairer(Context context) {
        mContext = context;
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        // Ahead of the Settings pairing dialog.
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    /** Called by PenMonitor whenever the pen's HID connection changes. */
    void setPenConnected(boolean connected) {
        mHandler.post(() -> {
            mWanted = !connected;
            update();
        });
    }

    private static boolean isPen(BluetoothDevice device) {
        // Unnamed devices exist, and the immutable PEN_NAMES throws on contains(null).
        String name = device != null ? device.getName() : null;
        return name != null && PEN_NAMES.contains(name);
    }

    private void update() {
        mHandler.removeCallbacks(mRetry);
        if (mAdapter == null) {
            BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
            mAdapter = manager != null ? manager.getAdapter() : null;
        }
        boolean scan = mWanted && mAdapter != null && mAdapter.isEnabled();
        if (scan == mScanning) {
            if (mWanted && !scan) {
                waiting(mAdapter == null ? "no adapter" : "Bluetooth off");
                // ACTION_STATE_CHANGED normally restarts us; this only covers a missed one.
                mHandler.postDelayed(mRetry, mAdapter == null ? RETRY_MS : IDLE_RETRY_MS);
            }
            return;
        }
        BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            if (scan) {
                waiting("no LE scanner");
                mHandler.postDelayed(mRetry, RETRY_MS);
            }
            return;
        }
        if (scan) {
            List<ScanFilter> filters = new ArrayList<>();
            for (String name : PEN_NAMES) {
                filters.add(new ScanFilter.Builder()
                        .setServiceData(XIAOMI_SERVICE, new byte[0], new byte[0])
                        .setDeviceName(name)
                        .build());
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            scanner.startScan(filters, settings, mScanCallback);
            Log.i(TAG, "scanning for a pen to pair");
        } else {
            scanner.stopScan(mScanCallback);
            Log.i(TAG, "pen scan stopped");
        }
        mScanning = scan;
        mWaiting = null;
    }

    private void waiting(String reason) {
        if (!reason.equals(mWaiting)) {
            mWaiting = reason;
            Log.i(TAG, "pen scan waiting: " + reason);
        }
    }

    private void handleResult(BluetoothDevice device) {
        if (!mWanted || device == null) {
            return;
        }
        // Never drop a bond here: a bonded pen advertising is just reconnecting.
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.i(TAG, "pairing pen " + device.getAddress());
            device.createBond();
        }
    }

    private void onBondStateChanged(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (isPen(device)) {
            Log.i(TAG, "pen bond state " + intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1));
        }
    }

    private void onPairingRequest(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
        if (!isPen(device) || !mWanted) {
            return;
        }
        // The pen has no display or keys: accept consent / numeric comparison.
        if (variant == BluetoothDevice.PAIRING_VARIANT_CONSENT
                || variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
            Log.i(TAG, "confirming pen pairing (variant " + variant + ")");
            device.setPairingConfirmation(true);
            mReceiver.abortBroadcast();  // no Settings dialog
        }
    }
}
