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

import javax.annotation.Nullable;

import network.minter.core.crypto.BytesData;

import static network.minter.core.internal.common.Preconditions.firstNonNull;

public class APDU {
    //// Status codes
    public static final int CODE_SUCCESS = 0x9000;
    public static final int CODE_USER_REJECTED = 0x6985;
    public static final int CODE_INVALID_PARAM = 0x6b01;

    public static final int CMD_GET_PUB_KEY = (0x01 << 1);
    public static final int CMD_SIGN_TX = (0x01 << 2);

    public final static int MAX_PAYLOAD_SIZE = Short.MAX_VALUE - 5 - 6 - 2;

    private byte mCls = (byte) 0xe0;
    private byte mIns = 0x00;
    private byte mP1 = 0x00;
    private byte mP2 = 0x00;
    private byte[] mPayload = new byte[0];

    public APDU(int ins, int p1, int p2, @Nullable byte[] payload) {
        mIns = (byte) ins;
        mP1 = (byte) p1;
        mP2 = (byte) p2;
        mPayload = firstNonNull(payload, new byte[0]);
    }

    public APDU(int cmd, byte[] payload) {
        this(cmd, 0x0, 0x0, payload);
    }

    public APDU(int cmd, String hexPayload) {
        this(cmd, new BytesData(hexPayload).getBytes());
    }

    public byte[] getData() {
        //5 - control data
        //2 - length prefix
        //6 - apdu data (5 bytes control + 2 bytes data size)
        //N - payload
        short plSize = (short) mPayload.length;
        short ledgerFrameSz = (short) (5 + plSize);
        byte[] out = new byte[64];
        int off = 0;
        out[off++] = 0x01; // channel id[0]
        out[off++] = 0x01; // channel id[1]
        out[off++] = 0x05; // command tag
        out[off++] = 0x00; // sequence[0]
        out[off++] = 0x00; // sequence[1]

        out[off++] = (byte) (ledgerFrameSz >> 8);
        out[off++] = (byte) (ledgerFrameSz & 0xFF);

        out[off++] = mCls; // dev class
        out[off++] = mIns; // instruction
        out[off++] = mP1;  // param1
        out[off++] = mP2;  // param2
        out[off++] = (byte) (plSize); // payload size

        for (byte v : mPayload) {
            out[off++] = v;
        }

        for (int i = off; off < 64; off++) {
            out[i] = 0;
        }

        return out;
    }
}
