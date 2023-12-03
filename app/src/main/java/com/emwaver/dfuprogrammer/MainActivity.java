package com.emwaver.dfuprogrammer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

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
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class MainActivity extends Activity implements
        Handler.Callback, Usb.OnUsbChangeListener, Dfu.DfuListener {

    private Usb usb;
    private Dfu dfu;
    private static final int REQUEST_CODE_ATTACH = 1;
    private static final int PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 100; // A unique request code
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check for the permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        }

        dfu = new Dfu(Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID, this);
        dfu.setListener(this);

        status = findViewById(R.id.status);




        Button clearTxtView = findViewById(R.id.clearTxt);
        clearTxtView.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("");
            }
        });



        Button writeBlockButton = findViewById(R.id.writeBlockButton);
        writeBlockButton.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                int BLOCK_SIZE = 2048;
                int startAddress = 0x08000000;
                byte [] buffer = new byte[BLOCK_SIZE];
                // Initialize all elements to 0x69
                byte valueToSet = (byte) 0x69; // Casting is important as 0x69 is an int literal
                for (int i = 0; i < BLOCK_SIZE; i++)
                    buffer[i] = valueToSet;
                try {

                    dfu.mass_erase();
                    dfu.set_address_pointer(0x08000000);

                    byte[] block = new byte[BLOCK_SIZE];
                    Arrays.fill(block, (byte) 0x69); // Fill the block with 0x69
                    dfu.write_block(block, 2, BLOCK_SIZE); // Assuming writeBlock method is implemented in your Dfu class
                    //status.append("wrote flash\n");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });


        Button massEraseButton = findViewById(R.id.massEraseButton);
        massEraseButton.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    dfu.mass_erase();
                } catch (Exception e) {
                    status.append(e.toString());
                }
            }
        });

        Button readFlashButton = findViewById(R.id.readFlashButton);
        readFlashButton.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    dfu.read_flash(0x6BE0);
                } catch (Exception e) {
                    status.append(e.toString());
                }
            }
        });

        Button writeFlashButton = findViewById(R.id.writeFlashButton);
        writeFlashButton.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    dfu.mass_erase();
                    dfu.set_address_pointer(0x08000000);

                    dfu.write_flash();
                } catch (Exception e) {
                    status.append(e.toString());
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

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*"); // Set type for file (e.g., "image/*" for images)
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(Intent.createChooser(intent, "Select a file"), REQUEST_CODE_ATTACH);
        } catch (android.content.ActivityNotFoundException ex) {
            // Handle if no file chooser is available
            Toast.makeText(this, "Please install a File Manager.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_ATTACH && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            // Handle the selected file
            // ...
        }
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