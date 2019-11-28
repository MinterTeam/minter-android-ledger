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

package network.minter.ledger.app;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import network.minter.core.crypto.BytesData;
import network.minter.ledger.connector.LedgerNanoS;
import network.minter.ledger.connector.MinterLedger;
import network.minter.ledger.connector.rxjava2.RxMinterLedger;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private TextView mProgressBarTitle;
    private ProgressBar mProgressBar;
    private TextView mDumpTextView;
    private NonFocusingScrollView mScrollView;
    private Button actionGetVersion;
    private Button actionGetAddress;
    private Button actionSign;
    private RxMinterLedger mDevice;
    private Disposable mActionDisp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Timber.plant(new Timber.DebugTree());

        mProgressBar = findViewById(R.id.progressBar);
        mProgressBarTitle = findViewById(R.id.progressBarTitle);
        mDumpTextView = findViewById(R.id.consoleText);
        mScrollView = findViewById(R.id.demoScroller);
        mScrollView.setSmoothScrollingEnabled(true);

        actionGetVersion = findViewById(R.id.btnGetVersion);
        actionGetAddress = findViewById(R.id.btnGetAddress);
        actionSign = findViewById(R.id.btnSign);
        enableActions(true);

        mDevice = new RxMinterLedger(this, (UsbManager) getSystemService(Context.USB_SERVICE));
        mDevice.setDeviceListener(new LedgerNanoS.DeviceListener() {
            @Override
            public void onDeviceReady() {
                enableActions(true);
                hideProgressBar();
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    searchNanoS();
                });
            }

            @SuppressLint("DefaultLocale")
            @Override
            public void onError(int code, Throwable t) {
                appendResult(String.format("Error code: %d", code));
                if (code == MinterLedger.CODE_PERMISSION_DENIED) {
                    Timber.d("Stop searching device, ask user for permissions");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("To access to your Nanos S, you should grant permission")
                            .setPositiveButton("Grant permissions", (d, w) -> {
                                searchNanoS();
                                d.dismiss();
                            })
                            .setNegativeButton("Deny", (d, w) -> {
                                hideProgressBar();
                                mProgressBarTitle.setText("Click here to search device");
                                mProgressBarTitle.setOnClickListener(v -> {
                                    searchNanoS();
                                    mProgressBarTitle.setOnClickListener(null);
                                });
                                d.dismiss();
                            })
                            .create()
                            .show();
                    // stop searching, ask user for grant permission
                }
            }
        });

//        searchNanoS();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mActionDisp != null) {
            mActionDisp.dispose();
            mActionDisp = null;
        }
        mDevice.destroy();
    }

    private void searchNanoS() {
        enableActions(false);
        showProgressBar();
        mDevice.init();
    }

    private void enableActions(boolean enable) {
        actionGetVersion.setEnabled(enable);
        actionGetAddress.setEnabled(enable);
        actionSign.setEnabled(enable);

        if (enable) {
            actionGetVersion.setOnClickListener(runAction(MinterLedger.Command.GetVersion));
            actionGetAddress.setOnClickListener(runAction(MinterLedger.Command.GetAddress));
            actionSign.setOnClickListener(runAction(MinterLedger.Command.SignHash));
        }
    }

    private View.OnClickListener runAction(MinterLedger.Command command) {
        return v -> {
//            if (!mDevice.isReady()) {
//                appendResult("Device did not initialized yet");
//                return;
//            }
            if (command == MinterLedger.Command.GetVersion) {
                mActionDisp = mDevice.getVersion()
                        .subscribe(res -> {
                            Timber.d("GetVersion response: %s", res);
                            appendResult(res);
                        }, t -> {
                            Timber.e(t);
                            appendResult(t.getMessage());
                        });
            } else if (command == MinterLedger.Command.GetAddress) {
                RxMinterLedger ledger = new RxMinterLedger(this, ((UsbManager) getSystemService(USB_SERVICE)));
                if (!ledger.isReady()) {
                    appendResult("Connecting...");
                }

                final android.app.AlertDialog dialog = new ProgressDialog.Builder(this)
                        .setTitle("Getting address")
                        .setMessage("Connecting...")
                        .setPositiveButton("Cancel", (d, w) -> {
                            Timber.d("Cancel request");
                            mActionDisp.dispose();
                            d.dismiss();
                        })
                        .setOnDismissListener(dialog1 -> {
                            Timber.d("Cancel request");
                            mActionDisp.dispose();
                        })
                        .create();

                mActionDisp = RxMinterLedger.initObserve(ledger)
                        .flatMap(dev -> {
                            dialog.setMessage("Connected!");
                            return dev.getAddress(false);
                        })
                        .doFinally(ledger::destroy)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .subscribe(res -> {
                            appendResult(res.toString());
                            dialog.dismiss();
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Address")
                                    .setMessage(res.toString())
                                    .setPositiveButton("Ok", (d, w) -> d.dismiss())
                                    .create()
                                    .show();
                        }, t -> {
                            dialog.dismiss();
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Error")
                                    .setMessage(t.getMessage())
                                    .setPositiveButton("Close", (d, w) -> d.dismiss())
                                    .create()
                                    .show();
                        });

                dialog.show();
            } else if (command == MinterLedger.Command.SignHash) {
                BytesData hash = new BytesData("1ee24f115b579f0f1ba7278515f8c438c2da201dc37fa44c2d9f431d94a9693e");
                mActionDisp = mDevice.signTxHash(hash)
                        .subscribe(res -> {
                            Timber.d("SignHash[0] response: %s %s %s", res.getR(), res.getS(), res.getV());
                            appendResult(res.toString());
                        }, t -> {
                            Timber.e(t);
                            appendResult(t.getMessage());
                        });
            }
        };
    }

    private void appendResult(final String res) {
        runOnUiThread(() -> {
            mDumpTextView.append("- ");
            mDumpTextView.append(res);
            mDumpTextView.append("\n");
            mScrollView.post(() -> mScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }


    private void showProgressBar() {
        runOnUiThread(() -> {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBarTitle.setText(R.string.refreshing);
        });
    }

    private void hideProgressBar() {
        runOnUiThread(() -> {
            mProgressBar.setVisibility(View.INVISIBLE);
            mProgressBarTitle.setText(R.string.dev_ready);
        });
    }
}
