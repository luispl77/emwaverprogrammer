/*
 * Copyright 2015 Umbrela Smart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emwaver.dfuprogrammer;

import android.content.Context;
import android.content.res.AssetManager;
import android.nfc.FormatException;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class Dfu {
    private static final String TAG = "Dfu";
    private final static int USB_DIR_OUT = 0;
    private final static int USB_DIR_IN = 128;       //0x80
    private final static int DFU_RequestType = 0x21;  // '2' => Class request ; '1' => to interface

    private final static int STATE_IDLE = 0x00;
    private final static int STATE_DETACH = 0x01;
    private final static int STATE_DFU_IDLE = 0x02;
    private final static int STATE_DFU_DOWNLOAD_SYNC = 0x03;
    private final static int STATE_DFU_DOWNLOAD_BUSY = 0x04;
    private final static int STATE_DFU_DOWNLOAD_IDLE = 0x05;
    private final static int STATE_DFU_MANIFEST_SYNC = 0x06;
    private final static int STATE_DFU_MANIFEST = 0x07;
    private final static int STATE_DFU_MANIFEST_WAIT_RESET = 0x08;
    private final static int STATE_DFU_UPLOAD_IDLE = 0x09;
    private final static int STATE_DFU_ERROR = 0x0A;
    private final static int STATE_DFU_UPLOAD_SYNC = 0x91;
    private final static int STATE_DFU_UPLOAD_BUSY = 0x92;

    // DFU Commands, request ID code when using controlTransfers
    private final static int DFU_DETACH = 0x00;
    private final static int DFU_DNLOAD = 0x01;
    private final static int DFU_UPLOAD = 0x02;
    private final static int DFU_GETSTATUS = 0x03;
    private final static int DFU_CLRSTATUS = 0x04;
    private final static int DFU_GETSTATE = 0x05;
    private final static int DFU_ABORT = 0x06;

    public final static int ELEMENT1_OFFSET = 293;  // constant offset in file array where image data starts
    public final static int TARGET_NAME_START = 22;
    public final static int TARGET_NAME_MAX_END = 276;
    public final static int TARGET_SIZE = 277;
    public final static int TARGET_NUM_ELEMENTS = 281;


    // Device specific parameters
    public static final String mInternalFlashString = "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg"; // STM32F405RG, 1MB Flash, 192KB SRAM
    public static final int mInternalFlashSize = 1048575;
    public static final int mInternalFlashStartAddress = 0x08000000;
    public static final int mOptionByteStartAddress = 0x1FFFC000;
    private static final int OPT_BOR_1 = 0x08;
    private static final int OPT_BOR_2 = 0x04;
    private static final int OPT_BOR_3 = 0x00;
    private static final int OPT_BOR_OFF = 0x0C;
    private static final int OPT_WDG_SW = 0x20;
    private static final int OPT_nRST_STOP = 0x40;
    private static final int OPT_nRST_STDBY = 0x80;
    private static final int OPT_RDP_OFF = 0xAA00;
    private static final int OPT_RDP_1 = 0x3300;


    private final int deviceVid;
    private final int devicePid;

    private Usb usb;
    private int deviceVersion;  //STM bootloader version

    private final List<DfuListener> listeners = new ArrayList<>();

    public interface DfuListener {
        void onStatusMsg(String msg);
    }

    private Context context;


    public Dfu(int usbVendorId, int usbProductId, Context context) {
        this.deviceVid = usbVendorId;
        this.devicePid = usbProductId;


        this.context = context;
    }

    private void onStatusMsg(final String msg) {
        for (DfuListener listener : listeners) {
            listener.onStatusMsg(msg);
        }
    }

    public void setListener(final DfuListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is null");
        listeners.add(listener);
    }

    public void setUsb(Usb usb) {
        this.usb = usb;
        this.deviceVersion = this.usb.getDeviceVersion();
    }


    public void massErase() {

        if (!isUsbConnected()) return;

        DfuStatus dfuStatus = new DfuStatus();
        long startTime = System.currentTimeMillis();  // note current time

        try {
            clearWaitIDLE(dfuStatus);
            massEraseCommand();                 // sent erase command request
            getStatus(dfuStatus);                // initiate erase command, returns 'download busy' even if invalid address or ROP
            int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
            do {
            /* wait specified time before next getStatus call */
                Thread.sleep(pollingTime);
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);
            onStatusMsg("Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            onStatusMsg(e.toString());
        }
    }

    // check if usb device is active
    private boolean isUsbConnected() {
        if (usb != null && usb.isConnected()) {
            return true;
        }
        onStatusMsg("No device connected");
        return false;
    }

    private void massEraseCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = 0x41;
        download(buffer); //single byte buffer for mass erase
    }

    private void unProtectCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) 0x92;
        download(buffer);
    }

    public void setAddressPointer(int Address) throws Exception {
        byte[] buffer = new byte[5];
        DfuStatus dfuStatus = new DfuStatus();
        buffer[0] = 0x21;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer);
        executeVerifyStatus(dfuStatus, "set address pointer");
    }

    public void writeBlock(int address, byte[] block, int blockNumber) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        while (dfuStatus.bState != STATE_DFU_IDLE){
            clearStatus();
            getStatus(dfuStatus);
        }

        //set block number for the first block written
        /*if (0 == blockNumber) {
            Log.i(TAG, "writing address: 0x" + Integer.toHexString(address));
            setAddressPointer(address);
        }*/
        Log.i(TAG, "writing address: 0x" + Integer.toHexString(address));
        setAddressPointer(address);

        while (dfuStatus.bState != STATE_DFU_IDLE){
            clearStatus();
            getStatus(dfuStatus);
        }

        Log.i(TAG, "writing block: " + blockNumber);
        download(block, (blockNumber + 2));
        executeVerifyStatus(dfuStatus, "block write");

        //clearWaitIDLE(dfuStatus);
    }

    public void readBlock(int address, byte[] block, int blockNumber) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();


        clearWaitIDLE(dfuStatus);

        //set address for the first block written
        /*if (0 == blockNumber) {
            Log.i(TAG, "reading address: 0x" + Integer.toHexString(address));
            setAddressPointer(address);
        }*/
        //Log.i(TAG, "reading address: 0x" + Integer.toHexString(address));
        setAddressPointer(address);

        clearWaitIDLE(dfuStatus);

        upload(block, block.length, (blockNumber + 2));
        getStatus(dfuStatus); //for upload its reading from memory so its very fast. No BUSY state.
        //executeVerifyStatus(dfuStatus, "block read");

        //clearWaitIDLE(dfuStatus);
    }


    //execute and verify action using getStatus(). arguments: where the error occurs (for what operation)
    private void executeVerifyStatus(DfuStatus dfuStatus, String operation) throws Exception {
        getStatus(dfuStatus);   // to execute
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("error on " + operation + " , execution failed (dfuBUSY not returned)");
        }
        getStatus(dfuStatus);   // to verify action
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("error on " + operation + " , verification failed (dfuERROR)");
        }
    }

    // Clears status and waits for DFU_IDLE status. useful to crear DFU_ERROR status.
    public void clearWaitIDLE(DfuStatus dfuStatus) throws Exception {
        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);
    }

    private void getStatus(DfuStatus status) throws Exception {
        byte[] buffer = new byte[6];
        int length = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_GETSTATUS, 0, 0, buffer, 6, 500);

        if (length < 0) {
            throw new Exception("USB Failed during getStatus");
        }
        status.bStatus = buffer[0]; // state during request
        status.bState = buffer[4]; // state after request
        status.bwPollTimeout = (buffer[3] & 0xFF) << 16;
        status.bwPollTimeout |= (buffer[2] & 0xFF) << 8;
        status.bwPollTimeout |= (buffer[1] & 0xFF);
    }

    private void clearStatus() throws Exception {
        int length = usb.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0);
        if (length < 0) {
            throw new Exception("USB Failed during clearStatus");
        }
    }


    public void upload(byte[] data, int length, int blockNum) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_UPLOAD, blockNum, 0, data, length, 100);
        Log.i(TAG, "block number: " + blockNum + "length");
        if (len < 0) {
            throw new Exception("USB comm failed during upload");
        }
    }

    // use for commands
    private void download(byte[] data) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, data.length, 50);
        if (len < 0) {
            throw new Exception("USB Failed during command download");
        }
    }

    // use for firmware download
    private void download(byte[] data, int nBlock) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, data.length, 0);
        if (len < 0) {
            throw new Exception("USB Failed during multi-block download");
        }
    }

    //for tests only
    public void readImage(byte[] deviceFw) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        int maxBlockSize = 4;
        int startAddress = 0x08000004;
        byte[] block = new byte[maxBlockSize];
        int nBlock = 0;
        int remLength = deviceFw.length;
        int numOfBlocks = remLength / maxBlockSize;

        clearWaitIDLE(dfuStatus);

        setAddressPointer(startAddress);

        clearWaitIDLE(dfuStatus);

        upload(block, maxBlockSize, nBlock + 2);
        getStatus(dfuStatus); //for
        Log.i(TAG, "reading: 0x" + Integer.toHexString(block[0]) + Integer.toHexString(block[1]) + Integer.toHexString(block[2]) + Integer.toHexString(block[3]));

    }

    // stores the result of a GetStatus DFU request
    public class DfuStatus {
        byte bStatus;       // state during request
        int bwPollTimeout;  // minimum time in ms before next getStatus call should be made
        byte bState;        // state after request
    }

}
