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
import network.minter.ledger.connector.exceptions.EmptyResponseException;
import network.minter.ledger.connector.exceptions.ReadTimeoutException;
import timber.log.Timber;

public class MinterLedger extends LedgerNanoS {

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
        //const uint16_t CODE_SUCCESS = 0x9000;
        //const uint16_t CODE_USER_REJECTED = 0x6985;
        //const uint16_t CODE_INVALID_PARAM = 0x6b01;
        //constexpr const uint16_t CODE_NO_STATUS_RESULT = CODE_SUCCESS + 1;
        Ok(0x9000),
        UserRejected(0x6985),
        InvalidParameter(0x6b01),
        Unknown(0xFF00),
        ConnectionLost(0xFF01),
        EmptyResponse(0xFF02),
        InvalidResponse(0xFF03),
        ReadTimeout(0xFF04),
        CommonIOError(0xFF05),
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

    public Pair<Status, SignatureSingleData> signTxHash(BytesData unsignedTxHash) {
        final ExchangeResult result;
        try {
            result = exchange(Command.SignHash, unsignedTxHash.getBytes());
        } catch (IOException e) {
            notifyError(CODE_EXCHANGE_ERROR, e);
            return null;
        }

        if (result.data.size() == 0) {
            notifyError(CODE_EMPTY_RESPONSE, null);
            return null;
        }

        SignatureSingleData sig = new SignatureSingleData(
                result.data.takeRange(0, 32),
                result.data.takeRange(32, 64),
                result.data.takeLast(1)
        );

        return new Pair<>(result.status, sig);
    }

    public Pair<Status, MinterAddress> getAddress() {
        return getAddress(0, false);
    }

    public Pair<Status, MinterAddress> getAddress(int deriveIndex, boolean silent) {
        final ExchangeResult result;
        try {
            BytesData payload = new BytesData(4);
            payload.write(0, deriveIndex);
            result = exchange(silent ? Command.GetAddressSilent : Command.GetAddress, payload.getBytes());
        } catch (IOException e) {
            notifyError(CODE_EXCHANGE_ERROR, e);
            return null;
        }

        if (result.data.size() == 0) {
            notifyError(CODE_EMPTY_RESPONSE, null);
            return null;
        }

        return new Pair<>(result.status, new MinterAddress(result.data.getData()));
    }

    public Pair<Status, String> getVersion() {
        final ExchangeResult response;
        try {
            response = exchange(Command.GetVersion, null);
        } catch (IOException e) {
            notifyError(CODE_EXCHANGE_ERROR, e);
            return null;
        }

        if (response.data.size() == 0) {
            notifyError(CODE_EMPTY_RESPONSE, null);
            return null;
        }

        dumpData(response.data);

        char maj = response.data.at(0);
        char min = response.data.at(1);
        char pat = response.data.at(2);

        String vers = String.format(Locale.getDefault(), "%d.%d.%d", (int) maj, (int) min, (int) pat);
        return new Pair<>(response.status, vers);
    }

    public ExchangeResult exchange(@NonNull Command command, byte[] payload) throws IOException {
        return exchange(command, payload, 60);
    }

    public ExchangeResult exchange(@NonNull Command command, byte[] payload, long timeoutS) throws IOException {
        APDU apdu = new APDU(command.mIns, command.mP1, command.mP2, payload);
        Timber.d("Write APDU frame: %s", dumpData(apdu.getData()));
        try {
            write(apdu);
        } catch (ConnectionException e) {
            return new ExchangeResult(Status.ConnectionLost);
        }
        BytesData resp;

        resp = new BytesData(read());

        int wait = 0;

        //todo read until responded data not equals data len
        while (resp.size() == 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new ReadTimeoutException(e);
            }
            resp = new BytesData(read());
            wait++;
            if (wait == timeoutS) {
                throw new ReadTimeoutException();
            }
        }

        if (resp.size() == 0) {
            throw new EmptyResponseException();
        }

        short channelId = resp.toUShortBigInt(0).shortValue();
        char commandTag = resp.at(2);
        short seqn = resp.toUShortBigInt(3).shortValue();

        if (channelId != 0x0101) {
            throw new IOException(String.format("Unknown channel id %d", channelId));
        }

        if (commandTag != 0x05) {
            throw new IOException("Response has invalid command id");
        }

        resp.takeRangeFromMutable(5);

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
