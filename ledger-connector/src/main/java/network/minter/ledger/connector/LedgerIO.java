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

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import network.minter.ledger.connector.exceptions.ConnectionException;


@SuppressWarnings("RedundantThrows")
public class LedgerIO {

    private static final int READ_TIMEOUT = 1000;
    private static final int WRITE_TIMEOUT = 1000;
    private static final int BUFSIZ = 4096;
    private final static Object mIOLock = new Object();
    private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);
    private UsbEndpoint mInEndpoint;
    private UsbEndpoint mOutEndpoint;
    private UsbDeviceConnection mConnection;

    public LedgerIO(UsbEndpoint inEndpoint, UsbEndpoint outEndpoint, UsbDeviceConnection connection) {
        mInEndpoint = inEndpoint;
        mOutEndpoint = outEndpoint;
        mConnection = connection;
    }

    public byte[] read() throws IOException {
        int len = read(mReadBuffer.array());
        byte[] data = new byte[0];
        if (len > 0) {
            data = new byte[len];
            mReadBuffer.get(data, 0, len);
        }
        mReadBuffer.clear();

        return data;
    }

    public int read(final byte[] data) throws IOException {
        final int size = Math.min(data.length, mInEndpoint.getMaxPacketSize());
        final int bytesRead;
        synchronized (mIOLock) {
            bytesRead = mConnection.bulkTransfer(mInEndpoint, data, size, READ_TIMEOUT);
        }
        return bytesRead;
    }

    public int write(final byte[] data) throws IOException {
        int length = data.length;
        int offset = 0;

        while (offset < length) {
            int size = Math.min(length - offset, mInEndpoint.getMaxPacketSize());
            int bytesWritten;
            synchronized (mIOLock) {
                bytesWritten = mConnection.bulkTransfer(mOutEndpoint,
                        Arrays.copyOfRange(data, offset, offset + size), size, WRITE_TIMEOUT);
            }

            if (bytesWritten <= 0) {
                throw new ConnectionException();
            } else {
                offset += bytesWritten;
            }

        }
        return offset;
    }
}
