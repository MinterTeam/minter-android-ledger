Minter Ledger Nano S SDK for Android
====================================
[![Download](https://api.bintray.com/packages/minterteam/android/ledger-connector/images/download.svg) ](https://bintray.com/minterteam/android/ledger-connector/_latestVersion)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Setup

Gradle
root build.gradle
```groovy
allprojects {
    repositories {
       // ... some repos
        maven { url "https://dl.bintray.com/minterteam/android" }
    }
}
```

project build.gradle
```groovy

ext {
    minterLedgerSDK = "0.1.0"
}

dependencies {
    implementation "network.minter.android:ledger-connector:${minterLedgerSDK}"
}
```


If you're using RxJava2, there is adapter:
```groovy
dependencies {
    // base
    implementation "network.minter.android:ledger-connector:${minterLedgerSDK}"
    // adapter
    implementation "network.minter.android:ledger-rxjava2-connector:${minterLedgerSDK}"
    
    //rxjava2 (see actual version at official repo)
    implementation 'io.reactivex.rxjava2:rxjava:2.2.10'
    implementation 'io.reactivex.rxjava2:rxandroid:2.1.1'

}
```


## Usage
### Pure Java
```java
package my.great.app;

import network.minter.ledger.connector.MinterLedget;
import androidx.appcompat.app.AppCompatActivity;
import android.hardware.usb.UsbManager;
import network.minter.ledger.connector.*;

class MainActivity extends AppCompatActivity {
    private MinterLedger mDevice;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        mDevice = new MinterLedget(/*Context*/ this, /*UsbManager*/ usb);
        
        mDevice.setDeviceListener(new LedgerNanoS.DeviceListener() {
                          @Override
                          public void onDeviceReady() {
                              // device ready, you can send instructions
                              
                              try {
                                Pair<Status, String> res = mDevice.getVersion();
                                myTextView.setText(String.format("Minter Ledger App Version: %s", res.second));
                              } catch(Throwable e) {
                                // anything can happens while getting result
                              }
                              
                          }
              
                          @Override
                          public void onDisconnected() {
                              // device disconnected
                              // do search again
                              while(!mDevice.isReady()) {
                                  mDevice.search();    
                              }
                          }
              
                          @Override
                          public void onError(int code, Throwable t) {
                              // check error code in MinterLedger.CODE_*
                          }
                      });

        while(!mDevice.isReady()) {
            mDevice.search();
            // you should manually run search() method until you need the Nano S
            // ATTENTION: this is just example, don't do it in your app, instead run this loop inside a new thread
        }
        
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDevice.destroy();
    }
}
```

## RxJava2
See [example module](example).


# License

This software is released under the [MIT](LICENSE) License.

© 2019 Eduard Maximovich <edward.vstock@gmail.com>, All rights reserved.