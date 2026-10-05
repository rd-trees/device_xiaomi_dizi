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
import android.os.SystemClock;
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
 * with any matching pen. A pen that keeps advertising for pairing although we
 * hold a bond for it has lost its keys (e.g. after a reflash), so that bond is
 * dropped and redone.
 */
final class PenPairer {

    private static final String TAG = "DiziPen";

    private static final ParcelUuid XIAOMI_SERVICE =
            ParcelUuid.fromString("0000fd2d-0000-1000-8000-00805f9b34fb");
    // Keep in sync with XiaomiParts PenSettingsFragment.
    private static final Set<String> PEN_NAMES = Set.of("Redmi Smart Pen", "POCO Smart Pen");

    // A bonded pen that has advertised for pairing this long is treated as stale.
    private static final long STALE_BOND_MS = 10_000;

    private final Context mContext;
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mWanted;   // no pen connected, so pairing may be needed
    private boolean mScanning;
    private String mStaleAddress;
    private long mStaleSince;

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
        mAdapter = context.getSystemService(BluetoothManager.class).getAdapter();
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
            mStaleAddress = null;
            update();
        });
    }

    private static boolean isPen(BluetoothDevice device) {
        return device != null && PEN_NAMES.contains(device.getName());
    }

    private void update() {
        boolean scan = mWanted && mAdapter != null && mAdapter.isEnabled();
        if (scan == mScanning) {
            return;
        }
        BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner == null) {
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
    }

    private void handleResult(BluetoothDevice device) {
        if (!mWanted || device == null) {
            return;
        }
        switch (device.getBondState()) {
            case BluetoothDevice.BOND_NONE:
                Log.i(TAG, "pairing pen " + device.getAddress());
                device.createBond();
                break;
            case BluetoothDevice.BOND_BONDED:
                // Bonded yet still asking to pair: the pen has lost our keys.
                long now = SystemClock.elapsedRealtime();
                if (!device.getAddress().equals(mStaleAddress)) {
                    mStaleAddress = device.getAddress();
                    mStaleSince = now;
                } else if (now - mStaleSince > STALE_BOND_MS) {
                    Log.i(TAG, "pen " + device.getAddress() + " lost its bond, re-pairing");
                    mStaleAddress = null;
                    device.removeBond();  // BOND_NONE, then the next result re-pairs
                }
                break;
            default:
                break;  // bonding in progress
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
