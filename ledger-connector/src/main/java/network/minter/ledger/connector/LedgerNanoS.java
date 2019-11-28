/*
 * Copyright (C) by MinterTeam. 2019
 * @link <a href="https://github.com/MinterTeam">Org Github</a>
 * @link <a href="https://github.com/edwardstock">Maintainer Github</a>
 *
 * The MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package network.minter.ledger.connector;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import network.minter.core.crypto.BytesData;
import network.minter.ledger.connector.exceptions.ConnectionException;
import timber.log.Timber;

import static android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED;

public class LedgerNanoS {
    public static final int CODE_PERMISSION_DENIED = 0x100;
    public static final int CODE_DEVICE_NO_OUTPUTS = 0x101;
    public static final int CODE_CANT_OPEN_DEVICE = 0x102;
    public static final int CODE_NO_CONNECTION = 0x103;

    public final static int NANOS_VID = 0x2c97;
    public final static int NANOS_PID = 0x0001;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    protected UsbDevice mDev;
    protected UsbInterface mUsbInterface;
    protected UsbEndpoint mInEndpoint;
    protected UsbEndpoint mOutEndpoint;
    protected UsbDeviceConnection mConnection;
    protected LedgerIO mLedgerIO;
    protected WeakReference<Context> mContext;
    protected WeakReference<UsbManager> mUsbManager;
    protected AtomicBoolean mPermissionsGranted = new AtomicBoolean(false);
    protected AtomicBoolean mDeviceReady = new AtomicBoolean(false);
    protected AtomicBoolean mAskedPerm = new AtomicBoolean(false);
    private DeviceListener mDeviceListener;
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    onPermissionsResult(context, intent);
                }
            }

            if (ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    if (mDeviceReady.get()) {
                        Timber.d("Ledger App Closed...");
                        disconnect();
                        if (mDeviceListener != null) {
                            mDeviceListener.onDisconnected();
                        }
                    }
                }
            }

        }
    };
    private PendingIntent mPermissionIntent;

    public LedgerNanoS(Context context, UsbManager manager) {
        mContext = new WeakReference<>(context);
        mUsbManager = new WeakReference<>(manager);
        mPermissionIntent = PendingIntent.getBroadcast(mContext.get(), 0, new Intent(ACTION_USB_PERMISSION), 0);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DEVICE_DETACHED);
        mContext.get().registerReceiver(mUsbReceiver, filter);
    }

    public void setDeviceListener(DeviceListener listener) {
        mDeviceListener = listener;
    }

    public boolean isConnected() {
        return findDevice() != null;
    }

    public synchronized boolean search() {
        if (isReady()) return true;
        Timber.i("Searching device...");
        mDev = findDevice();
        if (mDev != null) {
            askPermissions();
            return true;
        }

        return false;
    }

    public void disconnect() {
        mPermissionsGranted.set(false);
        mDeviceReady.set(false);

        if (mUsbInterface != null && mConnection != null) {
            Timber.d("Claiming interface.");
            mConnection.releaseInterface(mUsbInterface);
            mConnection.close();
        }
        if (mLedgerIO != null) {
            mLedgerIO.close();
            mLedgerIO = null;
        }

        if (mDeviceListener != null) {
            mDeviceListener.onDisconnected();
        }
    }

    public byte[] readRaw() throws IOException {
        return mLedgerIO.read();
    }

    public void destroy() {
        disconnect();
        if (mConnection != null) {
            mConnection.close();
        }
        if (mContext != null) {
            mContext.get().unregisterReceiver(mUsbReceiver);
            mContext.clear();
        }
        if (mUsbManager != null) {
            mUsbManager.clear();
        }
    }

    public BytesData read() throws IOException {
        return new BytesData(mLedgerIO.read());
    }

    protected UsbDevice findDevice() {
        if (mUsbManager == null || mUsbManager.get() == null) {
            return null;
        }

        for (UsbDevice usbDevice : mUsbManager.get().getDeviceList().values()) {
            if (usbDevice.getVendorId() == LedgerNanoS.NANOS_VID && usbDevice.getProductId() == LedgerNanoS.NANOS_PID) {
                if (usbDevice.getInterfaceCount() == 1) {
                    return usbDevice;
                }
            }
        }

        return null;
    }

    public void write(APDU apdu) throws IOException {
        try {
            mLedgerIO.write(apdu.getData());
        } catch (ConnectionException e) {
            disconnect();
            throw e;
        }
    }

    public boolean isReady() {
        return mDeviceReady.get() && mPermissionsGranted.get();
    }

    protected void notifyError(int status, Throwable t) {
        if (mDeviceListener != null) {
            mDeviceListener.onError(status, t);
        }
    }

    private synchronized void askPermissions() {
        if (mDev != null && mUsbManager.get().hasPermission(mDev)) {
            Timber.d("Permissions already granted for dev: %s", mDev.toString());
            mPermissionsGranted.set(true);
        }

        if (mPermissionsGranted.get()) {
            initUsbDevice();
        } else {
            if (mAskedPerm.get()) return;
            Timber.d("Asking Permissions for dev: %s", mDev.toString());
            mAskedPerm.set(true);
            mUsbManager.get().requestPermission(mDev, mPermissionIntent);
        }
    }

    private UsbInterface findInterface(UsbDevice usbDevice) {
        Timber.d("Searching for HID interface...");
        for (int nIf = 0; nIf < usbDevice.getInterfaceCount(); nIf++) {
            UsbInterface usbInterface = usbDevice.getInterface(nIf);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                return usbInterface;
            }
        }
        Timber.d("No one HID interface found");
        return null;
    }

    private void onPermissionsResult(Context context, Intent intent) {
        try {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                if (device != null) {
                    Timber.d("Permissions granted");
                    mPermissionsGranted.set(true);
                    if (mDeviceReady.get()) {
                        notifyDeviceReady();
                    }
                    initUsbDevice();
                }
            } else {
                mPermissionsGranted.set(false);
                notifyError(CODE_PERMISSION_DENIED, null);
                Timber.e("Permission denied for device %s", device);
            }
        } finally {
            mAskedPerm.set(false);
        }
    }

    private void notifyDeviceReady() {
        if (mDeviceListener != null) {
            mDeviceListener.onDeviceReady();
        }
    }

    private void initUsbDevice() {
        Timber.d("Start init device");
        if (mDev == null) {
            mDev = findDevice();
            if (mDev == null) {
                notifyError(CODE_CANT_OPEN_DEVICE, null);
                return;
            } else {
                mPermissionsGranted.set(false);
                mAskedPerm.set(false);
                askPermissions();
                return;
            }
        }
        mUsbInterface = mDev.getInterface(0);

        for (int nEp = 0; nEp < mUsbInterface.getEndpointCount(); nEp++) {
            UsbEndpoint tmpEndpoint = mUsbInterface.getEndpoint(nEp);

            if ((mOutEndpoint == null)
                    && (tmpEndpoint.getDirection() == UsbConstants.USB_DIR_OUT)) {
                mOutEndpoint = tmpEndpoint;
            } else if ((mInEndpoint == null)
                    && (tmpEndpoint.getDirection() == UsbConstants.USB_DIR_IN)) {
                mInEndpoint = tmpEndpoint;
            }
        }
        if (mOutEndpoint == null) {
            notifyError(CODE_DEVICE_NO_OUTPUTS, null);
            Timber.e("No output endpoints");
        }
        mConnection = mUsbManager.get().openDevice(mDev);
        if (mConnection == null) {
            Timber.e("Can't open device");
            notifyError(CODE_CANT_OPEN_DEVICE, null);
            return;
        }
        Timber.d("Claiming interface.");
        mConnection.claimInterface(mUsbInterface, true);
        initIO();
        Timber.d("Device is Ready");
        mDeviceReady.set(true);

        if (mDeviceListener != null && mPermissionsGranted.get()) {
            mDeviceListener.onDeviceReady();
        }
    }

    private void initIO() {
        if (mConnection != null) {
            Timber.i("Init IO");
            mLedgerIO = new LedgerIO(mInEndpoint, mOutEndpoint, mConnection);
        } else {
            notifyError(CODE_NO_CONNECTION, null);
            Timber.e("Can't init IO: connection is uninitialized");
        }
    }

    public interface DeviceListener {
        void onDeviceReady();
        void onDisconnected();
        void onError(int code, Throwable t);
    }

}
