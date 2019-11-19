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

package network.minter.ledger.connector.rxjava2;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import network.minter.core.crypto.BytesData;
import network.minter.ledger.connector.MinterLedger;
import network.minter.ledger.connector.exceptions.ConnectionException;
import network.minter.ledger.connector.exceptions.EmptyResponseException;
import network.minter.ledger.connector.exceptions.ReadTimeoutException;

import static com.google.common.base.MoreObjects.firstNonNull;

public class ResponseException extends IOException {
    private MinterLedger.ExchangeResult mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.Unknown);
    private String mMessage = null;

    public ResponseException(Throwable cause) {
        super(cause);
        if (getCause() instanceof ReadTimeoutException) {
            mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.ReadTimeout);
        } else if (getCause() instanceof EmptyResponseException) {
            mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.EmptyResponse);
        } else if (getCause() instanceof ConnectionException) {
            mMessage = "Connection to Nano S lost";
            mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.ConnectionLost);
        } else if (getCause() instanceof IOException) {
            mMessage = getCause().getMessage();
            mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.CommonIOError);
        } else if (getCause() != null) {
            mMessage = getCause().getMessage();
            mResult = new MinterLedger.ExchangeResult(MinterLedger.Status.Unknown);
        }
    }

    public ResponseException(MinterLedger.ExchangeResult result) {
        mResult = result;
    }

    public ResponseException(MinterLedger.Status status) {
        mResult = new MinterLedger.ExchangeResult();
    }

    public MinterLedger.Status getStatus() {
        return mResult.status;
    }

    public int getCode() {
        return getStatus().getCode();
    }

    @NonNull
    public BytesData getResponse() {
        return firstNonNull(mResult.data, new BytesData(0));
    }

    @Nullable
    @Override
    public String getMessage() {
        if (mMessage != null) {
            return mMessage;
        }

        if (mResult == null && getCause() != null) {
            return getCause().getMessage();
        }

        if (mResult != null && mResult.status != null) {
            return String.format("Response error: [0x%04x] %s", getCode(), getStatus().name());
        }

        return "Unknown error caused";
    }

    @NonNull
    @Override
    public String toString() {
        return firstNonNull(getMessage(), super.toString());
    }
}
