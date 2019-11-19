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
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.reactivex.disposables.Disposable;
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
        enableActions(false);

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

        searchNanoS();
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

        if (enable) {
            actionGetVersion.setOnClickListener(runAction(MinterLedger.Command.GetVersion));
            actionGetAddress.setOnClickListener(runAction(MinterLedger.Command.GetAddress));
        }
    }

    private View.OnClickListener runAction(MinterLedger.Command command) {
        return v -> {
            if (!mDevice.isReady()) {
                appendResult("Device did not initialized yet");
                return;
            }
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
                mActionDisp = mDevice.getAddress()
                        .subscribe(res -> {
                            Timber.d("GetAddress[0] response: %s", res.toString());
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
