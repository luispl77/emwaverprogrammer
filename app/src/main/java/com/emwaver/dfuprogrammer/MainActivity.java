package com.emwaver.dfuprogrammer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Arrays;

public class MainActivity extends Activity implements
        Handler.Callback, Usb.OnUsbChangeListener, Dfu.DfuListener {

    private Usb usb;
    private Dfu dfu;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dfu = new Dfu(Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID, this);
        dfu.setListener(this);

        status = findViewById(R.id.status);

        Button massErase = findViewById(R.id.btnMassErase);
        massErase.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                dfu.massErase();
            }
        });

        Button program = findViewById(R.id.btnCustom);
        program.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                int writeAddress = 0x08000004;
                byte[] dataBlock = new byte[] { 0x70, 0x70, 0x70, 0x70 };
                int blockNumber = 0;
                try {
                    dfu.writeBlock(writeAddress, dataBlock, blockNumber);
                    status.append("Data written successfully to address 0x" + Integer.toHexString(writeAddress) + "\n");
                } catch (Exception e) {
                    status.append("Error writing data: " + e.getMessage() + "\n");
                }
            }
        });

        Button read = findViewById(R.id.btnCustom2);
        read.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                int readAddress = 0x08000004;
                int blockNumber = 0;
                byte[] dataBlockRead = new byte[8];
                try {
                    dfu.readBlock(readAddress, dataBlockRead, blockNumber);
                    StringBuilder hexString = new StringBuilder();
                    for (byte b : dataBlockRead)
                        hexString.append(String.format("%02X", b));
                    status.append("Data read successfully: 0x" + hexString.toString() + "\n");
                } catch (Exception e) {
                    status.append("Error reading data: " + e.getMessage() + "\n");
                }

                byte[] image = new byte[900];
                try {
                    dfu.readImage(image);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        /* Setup USB */
        usb = new Usb(this);
        usb.setUsbManager((UsbManager) getSystemService(Context.USB_SERVICE));
        usb.setOnUsbChangeListener(this);

        // Handle two types of intents. Device attachment and permission
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(Usb.ACTION_USB_PERMISSION));
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));


        // Handle case where USB device is connected before app launches;
        // hence ACTION_USB_DEVICE_ATTACHED will not occur so we explicitly call for permission
        usb.requestPermission(this, Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID);
    }

    @Override
    protected void onStop() {
        super.onStop();

        /* USB */
        dfu.setUsb(null);
        usb.release();
        try {
            unregisterReceiver(usb.getmUsbReceiver());
        } catch (IllegalArgumentException e) { /* Already unregistered */ }
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        return false;
    }

    @Override
    public void onStatusMsg(String msg) {
        // TODO since we are appending we should make the TextView scrollable like a log
        status.append(msg);
    }

    @Override
    public void onUsbConnected() {
        final String deviceInfo = usb.getDeviceInfo(usb.getUsbDevice());
        status.setText(deviceInfo);
        dfu.setUsb(usb);
    }
}