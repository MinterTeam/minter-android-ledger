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

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import network.minter.blockchain.models.operational.SignatureSingleData;
import network.minter.core.crypto.BytesData;
import network.minter.core.crypto.MinterAddress;
import network.minter.core.internal.helpers.StringHelper;
import network.minter.ledger.connector.exceptions.ConnectionException;
import network.minter.ledger.connector.exceptions.ResponseException;
import timber.log.Timber;

public class MinterLedger extends LedgerNanoS {

    /**
     * Use -1 to infinite wait, but it's bad idea
     */
    public static int READ_TIMEOUT_SECONDS = 60;

    public enum Command {
        GetVersion(0x01, 0, 0),
        GetAddress(0x01 << 1, 0, 0),
        GetAddressSilent(0x01 << 1, 1, 0),
        SignHash(0x01 << 2, 0, 0),
        ;
        byte mIns;
        byte mP1;
        byte mP2;

        Command(int ins, int p1, int p2) {
            mIns = (byte) (ins & 0xFF);
            mP1 = (byte) (p1 & 0xFF);
            mP2 = (byte) (p2 & 0xFF);
        }
    }

    public enum Status {
        Ok(0x9000),
        UserRejected(0x6985),
        InvalidParameter(0x6b01),
        Unknown(0xFF00),
        ConnectionLost(0xFF01),
        EmptyResponse(0xFF02),
        InvalidResponse(0xFF03),
        ReadTimeout(0xFF04),
        CommonIOError(0xFF05),
        DeviceError(0xFF06),
        Canceled(0xFF07),
        ;
        short mValue;

        Status(int status) {
            mValue = (short) status;
        }

        public static Status findByValue(short val) {
            for (Status s : values()) {
                if (s.mValue == val) {
                    return s;
                }
            }

            return Unknown;
        }

        public int getCode() {
            return mValue;
        }
    }

    public MinterLedger(Context context, UsbManager manager) {
        super(context, manager);
    }

    public UsbDevice getDevice() {
        return mDev;
    }

    public Pair<Status, SignatureSingleData> signTxHash(BytesData unsignedTxHash) throws ResponseException {
        return signTxHash(0, unsignedTxHash);
    }

    public Pair<Status, SignatureSingleData> signTxHash(int deriveIndex, BytesData unsignedTxHash) throws ResponseException {
        final ExchangeResult result;
        try {
            BytesData tmp = new BytesData(unsignedTxHash.size() + 4);
            tmp.write(0, deriveIndex);
            tmp.write(4, unsignedTxHash);
            result = exchange(Command.SignHash, tmp.getBytes());
        } catch (IOException e) {
            throw new ResponseException(e);
        }

        if (result == null) {
            throw new ResponseException(MinterLedger.Status.EmptyResponse);
        } else if (result.status != MinterLedger.Status.Ok) {
            throw new ResponseException(result);
        } else if (result.data == null || result.data.size() == 0) {
            throw new ResponseException(result);
        }

        SignatureSingleData sig = new SignatureSingleData(
                result.data.takeRange(0, 32),
                result.data.takeRange(32, 64),
                result.data.takeLast(1)
        );

        return new Pair<>(result.status, sig);
    }

    public Pair<Status, MinterAddress> getAddress() throws ResponseException {
        return getAddress(0, false);
    }

    public Pair<Status, MinterAddress> getAddress(int deriveIndex, boolean silent) throws ResponseException {
        final ExchangeResult result;
        try {
            BytesData payload = new BytesData(4);
            payload.write(0, deriveIndex);
            result = exchange(silent ? Command.GetAddressSilent : Command.GetAddress, payload.getBytes());
        } catch (IOException e) {
            throw new ResponseException(e);
        }

        if (result == null) {
            throw new ResponseException(MinterLedger.Status.EmptyResponse);
        } else if (result.status != MinterLedger.Status.Ok) {
            throw new ResponseException(result);
        } else if (result.data == null || result.data.size() == 0) {
            throw new ResponseException(result);
        }

        return new Pair<>(result.status, new MinterAddress(result.data.getData()));
    }

    public Pair<Status, String> getVersion() throws ResponseException {
        final ExchangeResult result;
        try {
            result = exchange(Command.GetVersion, null);
        } catch (IOException e) {
            throw new ResponseException(e);
        }

        if (result == null) {
            throw new ResponseException(MinterLedger.Status.EmptyResponse);
        } else if (result.status != MinterLedger.Status.Ok) {
            throw new ResponseException(result);
        } else if (result.data == null || result.data.size() == 0) {
            throw new ResponseException(result);
        }

        dumpData(result.data);

        char maj = result.data.at(0);
        char min = result.data.at(1);
        char pat = result.data.at(2);

        String vers = String.format(Locale.getDefault(), "%d.%d.%d", (int) maj, (int) min, (int) pat);
        return new Pair<>(result.status, vers);
    }

    public ExchangeResult exchange(@NonNull Command command, byte[] payload) throws IOException {
        APDU apdu = new APDU(command.mIns, command.mP1, command.mP2, payload);
        Timber.d("Write APDU frame: %s", dumpData(apdu.getData()));
        try {
            write(apdu);
        } catch (ConnectionException e) {
            return new ExchangeResult(Status.ConnectionLost);
        }
        BytesData buffer = new BytesData(0xFF);

        short seqn = 0;
        byte[] buf = new byte[64];

        mLedgerIO.readWait(buf, READ_TIMEOUT_SECONDS);
        Timber.d("Read frame[%d:%d]: %s", seqn, 64, dumpData(buf));

        buffer.write(0, buf);

        short channelId = buffer.toUShortBigInt(0).shortValue();
        char commandTag = buffer.at(2);
        seqn = buffer.toUShortBigInt(3).shortValue();
        short commonDataLen = buffer.toUShortBigInt(5).shortValue();
        int offset = 0;
        if (channelId != 0x0101) {
            throw new IOException(String.format("Unknown channel id %d", channelId));
        }

        if (commandTag != 0x05) {
            throw new IOException("Response has invalid command id");
        }

        seqn++;

        Timber.d("Needs to read more data? (%d * %d) < %d", seqn, 64, commonDataLen);

        while ((seqn * 64) < commonDataLen) {
            mLedgerIO.readWait(buf, READ_TIMEOUT_SECONDS);
            seqn++;
            Timber.d("Read frame[%d:%d]: %s", seqn, 64, dumpData(buf));
            Timber.d("Needs to read more data? (%d * %d) < %d", seqn, 64, commonDataLen);
            offset += buf.length;

            buffer.write(offset, buf);

        }

        BytesData resp = new BytesData(seqn * 64 - (seqn * 5));
        for (int i = 0; i < seqn; i++) {
            resp.write(i * 59, buffer.takeRange((i * 64) + 5, 64 + (i * 64)));
        }

        ExchangeResult result = new ExchangeResult();

        if (resp.size() >= 2) {
            //2 bytes - len, N data, 2 bytes status, length prefix does not include 2 bytes of status code
            short dataLen = (short) (resp.toUShortBigInt(0).shortValue() + 2);

            BytesData rawResp = new BytesData(resp.takeRangeTo(dataLen));
            BytesData statusResp = new BytesData(rawResp.takeRange(dataLen - 2, dataLen));

            result.status = Status.findByValue(statusResp.toUShortBigInt(0).shortValue());
            result.data = new BytesData(rawResp.takeRange(2, dataLen - 2));
        } else {
            result.data = new BytesData(0);
            result.status = Status.Unknown;
        }

        return result;
    }

    private String dumpData(byte[] data) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < data.length; i++) {
            sb.append(StringHelper.bytesToHexString(new byte[]{data[i]}));
            if (i < data.length - 1) {
                sb.append(" ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private void dumpData(BytesData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < data.size(); i++) {
            sb.append(StringHelper.bytesToHexString(new byte[]{(byte) data.at(i)}));
            if (i < data.size() - 1) {
                sb.append(" ");
            }
        }
        sb.append(" ");
        Timber.d("Response[%d]: %s", data.size(), sb.toString());
    }

    public static class ExchangeResult {
        public Status status = Status.Unknown;
        public BytesData data = new BytesData(0);

        public ExchangeResult() {
        }

        public ExchangeResult(Status s) {
            status = s;
            data = new BytesData(0);
        }
    }
}
