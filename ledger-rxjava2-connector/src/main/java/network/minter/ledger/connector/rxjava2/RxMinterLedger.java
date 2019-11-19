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

import android.content.Context;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import network.minter.blockchain.models.operational.SignatureSingleData;
import network.minter.core.crypto.BytesData;
import network.minter.core.crypto.MinterAddress;
import network.minter.ledger.connector.APDU;
import network.minter.ledger.connector.LedgerNanoS;
import network.minter.ledger.connector.MinterLedger;
import network.minter.ledger.connector.exceptions.ReadTimeoutException;

public class RxMinterLedger {
    private final static Object sSearchDispLock = new Object();
    private MinterLedger mHandle;
    private Disposable mSearchDisposable;
    private AtomicBoolean mPermissionDeniedByUser = new AtomicBoolean(false);

    public RxMinterLedger(Context context, UsbManager manager) {
        mHandle = new MinterLedger(context, manager);
    }

    public void init() {
        mHandle.search();
        if (!isReady()) {
            synchronized (sSearchDispLock) {
                if (mSearchDisposable != null) {
                    return;
                }
            }
            mSearchDisposable = Observable.interval(1, TimeUnit.SECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe(res -> {
                        if (isReady() || mPermissionDeniedByUser.get()) {
                            mPermissionDeniedByUser.set(false);
                            synchronized (sSearchDispLock) {
                                if (mSearchDisposable != null) {
                                    mSearchDisposable.dispose();
                                    mSearchDisposable = null;
                                }
                            }
                        } else {
                            mHandle.search();
                        }
                    });


        }
    }

    public Observable<MinterLedger.ExchangeResult> exchange(MinterLedger.Command command, byte[] payload) {
        return Observable
                .create((ObservableOnSubscribe<MinterLedger.ExchangeResult>) emitter -> {
                    MinterLedger.ExchangeResult result;
                    try {
                        result = mHandle.exchange(command, payload);
                    } catch (ReadTimeoutException e) {
                        emitter.onError(new ResponseException(e));
                        return;
                    }

                    if (result == null) {
                        emitter.onError(new RuntimeException("Exchange result did repond nothing"));
                        return;
                    } else if (result.status != MinterLedger.Status.Ok) {
                        emitter.onError(new ResponseException(result));
                        return;
                    } else if (result.data == null || result.data.size() == 0) {
                        emitter.onError(new ResponseException(result));
                        return;
                    }

                    emitter.onNext(result);
                    emitter.onComplete();
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io());
    }

    public Observable<SignatureSingleData> signTxHash(BytesData unsignedTxHash) {
        if (unsignedTxHash.size() != 32) {
            throw new IllegalArgumentException("Transaction hash must have exact 32 bytes");
        }

        return exchange(MinterLedger.Command.SignHash, unsignedTxHash.getBytes())
                .map(result -> new SignatureSingleData(
                        result.data.takeRange(0, 32),
                        result.data.takeRange(32, 64),
                        result.data.takeLast(1)
                ));
    }

    public Observable<MinterAddress> getAddress() {
        return getAddress(0, false);
    }

    public Observable<MinterAddress> getAddress(boolean silent) {
        return getAddress(0, silent);
    }

    public Observable<MinterAddress> getAddress(int deriveIndex) {
        return getAddress(deriveIndex, false);
    }

    public Observable<MinterAddress> getAddress(int deriveIndex, boolean silent) {
        BytesData payload = new BytesData(4);
        payload.write(0, deriveIndex);

        return exchange(silent ? MinterLedger.Command.GetAddressSilent : MinterLedger.Command.GetAddress, payload.getBytes())
                .map(result -> new MinterAddress(result.data.getData()));
    }

    public Observable<String> getVersion() {
        return exchange(MinterLedger.Command.GetVersion, null)
                .map(result -> {
                    char maj = result.data.at(0);
                    char min = result.data.at(1);
                    char pat = result.data.at(2);

                    return String.format(Locale.getDefault(), "%d.%d.%d", (int) maj, (int) min, (int) pat);
                });
    }

    public void setDeviceListener(LedgerNanoS.DeviceListener listener) {
        mHandle.setDeviceListener(new LedgerNanoS.DeviceListener() {
            @Override
            public void onDeviceReady() {
                if (listener != null) {
                    listener.onDeviceReady();
                }
            }

            @Override
            public void onDisconnected() {
                if (listener != null) {
                    listener.onDisconnected();
                }
            }

            @Override
            public void onError(int code, Throwable t) {
                if (code == MinterLedger.CODE_PERMISSION_DENIED) {
                    mPermissionDeniedByUser.set(true);
                }
                if (listener != null) {
                    listener.onError(code, t);
                }
            }
        });
    }

    public void destroy() {
        if (mSearchDisposable != null) {
            mSearchDisposable.dispose();
            mSearchDisposable = null;
        }

        mHandle.destroy();
    }

    public BytesData read() throws IOException {
        return mHandle.read();
    }

    public void write(APDU apdu) throws IOException {
        mHandle.write(apdu);
    }

    public boolean isReady() {
        return mHandle.isReady();
    }

    public void disconnect() {
        mHandle.disconnect();
    }
}
