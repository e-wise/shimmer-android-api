package com.shimmerresearch.service;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import com.shimmerresearch.android.Shimmer;
import com.shimmerresearch.database.DatabaseHandler;
import com.shimmerresearch.database.ShimmerConfiguration;
import com.shimmerresearch.driver.ObjectCluster;
import com.shimmerresearch.driver.ShimmerVerDetails;
import com.shimmerresearch.tools.Logging;

public class ShimmerDeviceService extends Service {

    public static Shimmer shimmerDevice = null;

    private static int UNKNOWN_VALUE = -1;

    private static final String TAG = "MyService";
    public static final int MESSAGE_CONFIGURATION_CHANGE = 34;
    public static final int MESSAGE_WRITING_STOPED = 1;
    public static Logging shimmerLog1 = null;
    private final IBinder mBinder = new LocalBinder();
    public static HashMap<String, Logging> mLogShimmer = new HashMap<>(7);
    public static String mLogFileName = "Default";
    public static HashMap<String, boolean[]> mExpandableStates = new HashMap<>(7); // the max for bluetooth should be 7
    boolean[][] mPlotSelectedSignals = new boolean[7][Shimmer.MAX_NUMBER_OF_SIGNALS];
    boolean[][] mPlotSelectedSignalsFormat = new boolean[7][Shimmer.MAX_NUMBER_OF_SIGNALS];
    int[][] mGroupChildColor = new int[7][Shimmer.MAX_NUMBER_OF_SIGNALS];
    private static Handler mHandlerGraph = null;
    private static Handler mHandlerWrite = null;
    private static boolean mGraphing = false;
    private static boolean mWriting = false;
    private static String mGraphBluetoothAddress = ""; //used to filter the msgs passed to the handler
    public static List<String> mBluetoothAddresstoConnect = new ArrayList<>(0);
    public static List<ShimmerConfiguration> mTempShimmerConfigurationList = new ArrayList<>(0);
    public static List<String> mDeviceNametoConnect = new ArrayList<>(0);
    public List<String> list = new ArrayList<>(0);
    private static WeakReference<ShimmerDeviceService> mService;
    public DatabaseHandler mDataBase;
    public List<ShimmerConfiguration> mShimmerConfigurationList = new ArrayList<>();
    public static String mBluetoothAddressToHeartRate;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        mDataBase = new DatabaseHandler(this);
        for (int[] row : mGroupChildColor) {
            Arrays.fill(row, Color.rgb(0, 0, 0));
        }
        mService = new WeakReference<ShimmerDeviceService>(this);
    }

    public class LocalBinder extends Binder {
        public ShimmerDeviceService getService() {
            // Return this instance of LocalService so clients can call public methods
            return ShimmerDeviceService.this;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        if (shimmerDevice != null) {
            shimmerDevice.stop();
            shimmerDevice = null;
        }
    }

    public void disconnectAllDevices() {
        Log.d(TAG, "onDestroy");

        if (shimmerDevice != null) {
            shimmerDevice.stop();
            shimmerDevice = null;
        }

        if (mWriting == true) {
            mWriting = false;
            closeAndRemoveFiles();
            mHandlerWrite.obtainMessage(MESSAGE_WRITING_STOPED).sendToTarget();
        }
        mLogShimmer.clear();
    }


    @Override
    public void onStart(Intent intent, int startid) {
        return;
    }


    public void connectShimmer(String bluetoothAddress, String deviceName) {
        Log.d("Shimmer", "net Connection");
        shimmerDevice = new Shimmer(this, mHandler, deviceName, false);

        shimmerDevice.connect(bluetoothAddress, "default");
    }


    public void connectandConfigureShimmer(ShimmerConfiguration shimmerConfiguration) {
        Log.d("Shimmer", "net Connection");

        if (shimmerDevice != null && shimmerConfiguration.getBluetoothAddress() == shimmerDevice.getBluetoothAddress() && shimmerDevice.getShimmerState() == Shimmer.STATE_CONNECTED) {
            Toast.makeText(this, "Device " + shimmerConfiguration.getBluetoothAddress() + " Already Connected", Toast.LENGTH_LONG).show();
        } else {
            if (shimmerConfiguration.getShimmerVersion() == 3) {
                shimmerDevice = new Shimmer(this, mHandler, shimmerConfiguration.getDeviceName(), shimmerConfiguration.getSamplingRate(), shimmerConfiguration.getAccelRange(), shimmerConfiguration.getGSRRange(), shimmerConfiguration.getEnabledSensors(), false, shimmerConfiguration.isLowPowerAccelEnabled(), shimmerConfiguration.isLowPowerGyroEnabled(), shimmerConfiguration.isLowPowerMagEnabled(), shimmerConfiguration.getGyroRange(), shimmerConfiguration.getMagRange());
            } else if (shimmerConfiguration.getShimmerVersion() == 2) {
                shimmerDevice = new Shimmer(this, mHandler, shimmerConfiguration.getDeviceName(), shimmerConfiguration.getSamplingRate(), shimmerConfiguration.getAccelRange(), shimmerConfiguration.getGSRRange(), shimmerConfiguration.getEnabledSensors(), false, shimmerConfiguration.getMagRange());
            } else {
                shimmerDevice = new Shimmer(this, mHandler, shimmerConfiguration.getDeviceName(), false);
            }

            shimmerDevice.connect(shimmerConfiguration.getBluetoothAddress(), "default");
            resetPlotActivity();
        }
    }

    public void readAndSaveConfiguration(int position, String bluetoothAddress) {

        ShimmerConfiguration shimmerConfiguration = mShimmerConfigurationList.get(position);
        shimmerConfiguration.setShimmerVersion(shimmerDevice.getShimmerVersion());
        shimmerConfiguration.setAccelRange(shimmerDevice.getAccelRange());
        shimmerConfiguration.setEnabledSensors(shimmerDevice.getEnabledSensors());
        shimmerConfiguration.setGSRRange(shimmerDevice.getGSRRange());
        shimmerConfiguration.setGyroRange(shimmerDevice.getGyroRange());
        shimmerConfiguration.setIntExpPower(shimmerDevice.getInternalExpPower());
        shimmerConfiguration.setLowPowerAccelEnabled(shimmerDevice.getLowPowerAccelEnabled());
        shimmerConfiguration.setLowPowerGyroEnabled(shimmerDevice.getLowPowerGyroEnabled());
        shimmerConfiguration.setLowPowerMagEnabled(shimmerDevice.getLowPowerMagEnabled());
        shimmerConfiguration.setMagRange(shimmerDevice.getMagRange());
        shimmerConfiguration.setPressureResolution(shimmerDevice.getPressureResolution());
        shimmerConfiguration.setSamplingRate(shimmerDevice.getSamplingRate());

        mShimmerConfigurationList.set(position, shimmerConfiguration);
    }

    public void connectAllShimmerSequentiallyWithConfig(List<ShimmerConfiguration> shimmerConfigurationList) {
        Log.d("Shimmer", "net Connection");

        mTempShimmerConfigurationList = new ArrayList<ShimmerConfiguration>(shimmerConfigurationList);
        if (mTempShimmerConfigurationList.size() != 0) {
            int pos = 0;
            List<Integer> connectedLocations = new ArrayList<Integer>();
            for (ShimmerConfiguration sc : mTempShimmerConfigurationList) {
                if (isShimmerConnected(sc.getBluetoothAddress())) {
                    connectedLocations.add(pos);
                }
                pos++;
            }

            ListIterator<Integer> li = connectedLocations.listIterator(connectedLocations.size());

            // Iterate in reverse.
            while (li.hasPrevious()) {
                mTempShimmerConfigurationList.remove(li.previous().intValue());
            }

            //dont connect if it is already in the hashmap
            if (mTempShimmerConfigurationList.size() != 0) {
                connectandConfigureShimmer(mTempShimmerConfigurationList.get(0));
            }
        }


    }

    public boolean shimmerIsConnected() {
        return (shimmerDevice != null && shimmerDevice.getShimmerState() == Shimmer.STATE_CONNECTED);
    }

    public void toggleAllLEDS() {

        if (shimmerIsConnected()) {
            shimmerDevice.toggleLed();
        }
    }

    public static final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) { // handlers have a what identifier which is used to identify the type of msg
                case Shimmer.MESSAGE_READ:
                    if ((msg.obj instanceof ObjectCluster)) {    // within each msg an object can be include, objectclusters are used to represent the data structure of the shimmer device
                        ObjectCluster objectCluster = (ObjectCluster) msg.obj;

                        if (mGraphing == true && (objectCluster.mBluetoothAddress.equals(mGraphBluetoothAddress) || mGraphBluetoothAddress.equals(""))) {
                            mHandlerGraph.obtainMessage(Shimmer.MESSAGE_READ, objectCluster).sendToTarget();

                        }
                        if (mWriting == true) {
                            shimmerLog1 = (Logging) mLogShimmer.get(objectCluster.mBluetoothAddress);
                            if (shimmerLog1 != null) {
                                shimmerLog1.logData(objectCluster);

                            } else {
                                char[] bA = objectCluster.mBluetoothAddress.toCharArray();
                                String device = "Device " + bA[12] + bA[13] + bA[15] + bA[16];
                                Logging shimmerLog;
                                shimmerLog = new Logging(fromMilisecToDate(System.currentTimeMillis()) + mLogFileName + device, "\t", "MultiShimmerTemplate");
                                mLogShimmer.remove(objectCluster.mBluetoothAddress);
                                if (mLogShimmer.get(objectCluster.mBluetoothAddress) == null) {
                                    mLogShimmer.put(objectCluster.mBluetoothAddress, shimmerLog);
                                }
                            }
                        }

                    }
                    break;
                case Shimmer.MESSAGE_TOAST:
                    Log.d("toast", msg.getData().getString(Shimmer.TOAST));

                    Message message = mHandlerGraph.obtainMessage(Shimmer.MESSAGE_TOAST);
                    Bundle bundle = new Bundle();
                    bundle.putString(Shimmer.TOAST, msg.getData().getString(Shimmer.TOAST));
                    message.setData(bundle);
                    mHandlerGraph.sendMessage(message);
                    break;
                case Shimmer.MESSAGE_STATE_CHANGE:
                    if (mHandlerGraph != null) {
                        mHandlerGraph.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, msg.arg1, UNKNOWN_VALUE, msg.obj).sendToTarget();
                    }
                    switch (msg.arg1) {
                        case Shimmer.STATE_CONNECTED:
                            Log.d("Shimmer", "Connected Broadcast");
                            break;
                        case Shimmer.STATE_CONNECTING:

                            break;
                        case Shimmer.STATE_NONE:
                            Log.d("Shimmer", "NO_State" + ((ObjectCluster) msg.obj).mBluetoothAddress);
                            //TODO do we have to disconnect??
                            shimmerDevice = null;
                            if (mBluetoothAddresstoConnect.size() != 0) {
                                mBluetoothAddresstoConnect.remove(0);
                                mDeviceNametoConnect.remove(0);
                                if (mBluetoothAddresstoConnect.size() != 0) {
                                    ShimmerDeviceService service = mService.get();
                                    service.connectShimmer(mBluetoothAddresstoConnect.get(0), mDeviceNametoConnect.get(0));
                                }
                            }

                            if (mTempShimmerConfigurationList.size() != 0) {
                                mTempShimmerConfigurationList.remove(0);
                                if (mTempShimmerConfigurationList.size() != 0) {
                                    ShimmerDeviceService service = mService.get();
                                    service.connectandConfigureShimmer(mTempShimmerConfigurationList.get(0));
                                }
                            }
                            break;

                        case Shimmer.MSG_STATE_FULLY_INITIALIZED:
                            Log.d("Shimmer", "Fully Initialized");
                            if (mBluetoothAddresstoConnect.size() != 0) {
                                mBluetoothAddresstoConnect.remove(0);
                                mDeviceNametoConnect.remove(0);
                                if (mBluetoothAddresstoConnect.size() != 0) {
                                    ShimmerDeviceService service = mService.get();
                                    service.connectShimmer(mBluetoothAddresstoConnect.get(0), mDeviceNametoConnect.get(0));
                                }
                            }

                            if (mTempShimmerConfigurationList.size() != 0) {
                                mTempShimmerConfigurationList.remove(0);
                                if (mTempShimmerConfigurationList.size() != 0) {
                                    ShimmerDeviceService service = mService.get();
                                    service.connectandConfigureShimmer(mTempShimmerConfigurationList.get(0));
                                }
                            }

                            ShimmerDeviceService service = mService.get();
                            for (int i = 0; i < service.mShimmerConfigurationList.size(); i++) {
                                ShimmerConfiguration sc = service.mShimmerConfigurationList.get(i);
                                if (sc.getBluetoothAddress().equals(((ObjectCluster) msg.obj).mBluetoothAddress)) {
                                    sc.setShimmerVersion(service.getShimmerVersion(((ObjectCluster) msg.obj).mBluetoothAddress));
                                    Shimmer shimmer = service.getShimmer(((ObjectCluster) msg.obj).mBluetoothAddress);
                                    if (shimmer.getShimmerVersion() == ShimmerVerDetails.HW_ID.SHIMMER_3) {
                                        sc.setEnabledSensors(shimmer.getEnabledSensors());
                                        sc.setShimmerVersion(shimmer.getShimmerVersion());
                                        sc.setAccelRange(shimmer.getAccelRange());
                                        sc.setMagRange(shimmer.getMagRange());
                                        sc.setGyroRange(shimmer.getGyroRange());
                                        sc.setPressureResolution(shimmer.getPressureResolution());
                                        sc.setLowPowerAccelEnabled(shimmer.getLowPowerAccelEnabled());
                                        sc.setLowPowerMagEnabled(shimmer.getLowPowerMagEnabled());
                                        sc.setLowPowerGyroEnabled(shimmer.getLowPowerGyroEnabled());
                                        sc.setGSRRange(shimmer.getGSRRange());
                                        sc.setIntExpPower(shimmer.getInternalExpPower());
                                    } else {
                                        sc.setEnabledSensors(shimmer.getEnabledSensors());
                                        sc.setShimmerVersion(shimmer.getShimmerVersion());
                                        sc.setAccelRange(shimmer.getAccelRange());
                                        sc.setMagRange(shimmer.getMagRange());
                                        sc.setGSRRange(shimmer.getGSRRange());
                                        sc.setLowPowerMagEnabled(shimmer.getLowPowerMagEnabled());
                                    }
                                    service.mShimmerConfigurationList.set(i, sc);
                                    service.mDataBase.saveShimmerConfigurations("Temp", service.mShimmerConfigurationList);
                                }
                            }
                            mHandlerGraph.obtainMessage(MESSAGE_CONFIGURATION_CHANGE, 1, 1, 1).sendToTarget();
                            break;
                    }
                    break;
                case Shimmer.MESSAGE_PACKET_LOSS_DETECTED:
                    Log.d("SHIMMERPACKETRR2", "Detected");
                    mHandlerGraph.obtainMessage(Shimmer.MESSAGE_PACKET_LOSS_DETECTED, msg.obj).sendToTarget();
                    break;
            }
        }
    };

    public boolean shimmerIsStreaming() {
        return (shimmerIsConnected() && shimmerDevice.getStreamingStatus() == true);
    }


    public void stopStreamingAllDevices() {

        if (shimmerIsStreaming()) {
            shimmerDevice.stopStreaming();
            if (mWriting == true) {
                mWriting = false;
                closeAndRemoveFiles();
                mHandlerWrite.obtainMessage(MESSAGE_WRITING_STOPED).sendToTarget();
            }
        }
    }

    public void startStreamingAllDevices() {
        if (shimmerIsConnected() && !shimmerIsStreaming()) {
            shimmerDevice.startStreaming();
        }
    }

    public void setEnabledSensors(long enabledSensors, String bluetoothAddress) {
        if (shimmerIsConnected()) {
            if (shimmerIsStreaming()) {
                Toast.makeText(this, "In order to configure, please stop streaming", Toast.LENGTH_LONG).show();
            } else {
                shimmerDevice.writeEnabledSensors(enabledSensors);
            }
            resetPlotActivity();
        }
    }

    public void toggleLED(String bluetoothAddress) {

        if (shimmerIsConnected()) {
            shimmerDevice.toggleLed();
        }
    }

    public boolean isShimmerConnected(String bluetoothAddress) {

        return (shimmerIsConnected());
    }

    public long getEnabledSensors(String bluetoothAddress) {
        long enabledSensors = 0;

        if (shimmerIsConnected()) {
            enabledSensors = shimmerDevice.getEnabledSensors();
        }
        return enabledSensors;
    }


    public void writeSamplingRate(String bluetoothAddress, double samplingRate) {

        if (shimmerIsConnected()) {
            shimmerDevice.writeSamplingRate(samplingRate);
        }
    }

    public void writeGSRRange(String bluetoothAddress, int gsrRange) {

        if (shimmerIsConnected()) {
            shimmerDevice.writeGSRRange(gsrRange);
        }
    }


    public double getSamplingRate(String bluetoothAddress) {
        double SRate = UNKNOWN_VALUE;
        if (shimmerIsConnected()) {
            SRate = shimmerDevice.getSamplingRate();
        }
        return SRate;
    }

    public int getShimmerState(String bluetoothAddress) {
        int status = UNKNOWN_VALUE;
        if (shimmerDevice != null) {
            status = shimmerDevice.getShimmerState();
            Log.d("ShimmerState", Integer.toString(status));
        }
        return status;
    }

    public int getGSRRange(String bluetoothAddress) {
        int GSRRange = UNKNOWN_VALUE;
        if (shimmerIsConnected()) {
            GSRRange = shimmerDevice.getGSRRange();
            Log.d("ShimmerState", Integer.toString(GSRRange));
        }
        return GSRRange;
    }

    public void startStreaming(String bluetoothAddress) {

        if (shimmerIsConnected() && !shimmerIsStreaming())
            shimmerDevice.startStreaming();
    }

    public void stopStreaming(String bluetoothAddress) {

        if (shimmerIsConnected() && shimmerIsStreaming()) {
            shimmerDevice.stopStreaming();
            if (mWriting == true) {
                String btAddress = shimmerDevice.getBluetoothAddress();
                mLogShimmer.get(btAddress).closeFile();
                MediaScannerConnection.scanFile(this, new String[]{mLogShimmer.get(btAddress).getAbsoluteName()}, null, null);
                mLogShimmer.remove(btAddress);
                if (mLogShimmer.size() == 0) {
                    mWriting = false;
                    mHandlerWrite.obtainMessage(MESSAGE_WRITING_STOPED).sendToTarget();
                }
            }
        }
    }

    public void disconnectShimmerNew(String bluetoothAddress) {

        if (shimmerIsConnected()) {
            String btAddress = shimmerDevice.getBluetoothAddress();
            shimmerDevice.stop();
            if (mWriting == true) {
                //wait until all the messages from the device are processed
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mLogShimmer.get(btAddress).closeFile();
                MediaScannerConnection.scanFile(this, new String[]{mLogShimmer.get(btAddress).getAbsoluteName()}, null, null);
                mLogShimmer.remove(btAddress);
                if (mLogShimmer.size() == 0) {
                    mWriting = false;
                    mHandlerWrite.obtainMessage(MESSAGE_WRITING_STOPED).sendToTarget();
                }
            }
        }
        shimmerDevice = null;
    }


    public void setGraphHandler(Handler handler, String bluetoothAddress) {
        mHandlerGraph = handler;
        mGraphBluetoothAddress = bluetoothAddress;
    }

    public void setWriteHandler(Handler handler) {
        mHandlerWrite = handler;
    }


    public void setHandler(Handler handler) {
        mHandlerGraph = handler;
    }

    public void enableGraphingHandler(boolean setting) {
        mGraphing = setting;
    }

    public void enableWritingHandler(boolean setting) {
        mWriting = setting;
    }

    public boolean allShimmersDisconnected() {
        return (shimmerDevice == null || shimmerDevice.getShimmerState() == Shimmer.STATE_NONE);
    }

    public List<ShimmerConfiguration> getStreamingDevices() {

        List<ShimmerConfiguration> mShimmerConfigurationList = new ArrayList<ShimmerConfiguration>();
        if (shimmerIsConnected() && shimmerIsStreaming())
            mShimmerConfigurationList.add(new ShimmerConfiguration(shimmerDevice.getDeviceName(), shimmerDevice.getBluetoothAddress(), UNKNOWN_VALUE, shimmerDevice.getEnabledSensors(), shimmerDevice.getSamplingRate(), shimmerDevice.getAccelRange(), shimmerDevice.getGSRRange(), shimmerDevice.getShimmerVersion(), shimmerDevice.getLowPowerAccelEnabled(), shimmerDevice.getLowPowerGyroEnabled(), shimmerDevice.getLowPowerMagEnabled(), shimmerDevice.getGyroRange(), shimmerDevice.getMagRange(), shimmerDevice.getPressureResolution(), shimmerDevice.getInternalExpPower()));
        return mShimmerConfigurationList;
    }

    public boolean noDevicesStreaming() {
        return !allDevicesStreaming();
    }

    public boolean deviceStreaming(String address) {
        return shimmerIsConnected() && shimmerIsStreaming();
    }

    public boolean allDevicesStreaming() {
        return deviceStreaming(null);
    }


    public void removeExapandableStates(String activityName) {
        //a true in States means the listview is in an expanded state
        mExpandableStates.remove(activityName);
    }

    public void storePlotSelectedSignals(boolean[][] selectedSignals) {
        //a true in States means the listview is in an expanded state
        mPlotSelectedSignals = selectedSignals;
    }

    public boolean[][] getPlotSelectedSignals() {
        //a true in States means the listview is in an expanded state
        return mPlotSelectedSignals;
    }

    public void removePlotSelectedSignals() {
        mPlotSelectedSignals = null;
    }

    public void removePlotSelectedSignalsFormat() {
        mPlotSelectedSignalsFormat = null;
    }

    public Shimmer getShimmer(String bluetoothAddress) {
        if (shimmerIsConnected()) {
            return shimmerDevice;
        }
        return null;
    }

    public int getShimmerVersion(String bluetoothAddress) {
        int version = UNKNOWN_VALUE;
        if (shimmerIsConnected()) {
            version = shimmerDevice.getShimmerVersion();
        }
        return version;
    }

    /**
     * @param enabledSensors This takes in the current list of enabled sensors
     * @param sensorToCheck  This takes in a single sensor which is to be enabled
     * @return enabledSensors This returns the new set of enabled sensors, where any sensors which conflicts with sensorToCheck is disabled on the bitmap, so sensorToCheck can be accomodated (e.g. for Shimmer2 using ECG will disable EMG,GSR,..basically any daughter board)
     */
    public long sensorConflictCheckandCorrection(long enabledSensors, long sensorToCheck, int shimmerVersion) {

        if (shimmerVersion == ShimmerVerDetails.HW_ID.SHIMMER_2 || shimmerVersion == ShimmerVerDetails.HW_ID.SHIMMER_2R) {
            if ((sensorToCheck & Shimmer.SENSOR_GYRO) > 0 || (sensorToCheck & Shimmer.SENSOR_MAG) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_ECG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EMG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GSR);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_BRIDGE_AMP);
            } else if ((sensorToCheck & Shimmer.SENSOR_BRIDGE_AMP) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_ECG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EMG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GSR);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GYRO);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_MAG);
            } else if ((sensorToCheck & Shimmer.SENSOR_GSR) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_ECG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EMG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_BRIDGE_AMP);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GYRO);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_MAG);
            } else if ((sensorToCheck & Shimmer.SENSOR_ECG) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GSR);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EMG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_BRIDGE_AMP);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GYRO);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_MAG);
            } else if ((sensorToCheck & Shimmer.SENSOR_EMG) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GSR);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_ECG);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_BRIDGE_AMP);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_GYRO);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_MAG);
            } else if ((sensorToCheck & Shimmer.SENSOR_HEART) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXP_BOARD_A0);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXP_BOARD_A7);
            } else if ((sensorToCheck & Shimmer.SENSOR_EXP_BOARD_A0) > 0 || (sensorToCheck & Shimmer.SENSOR_EXP_BOARD_A7) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_HEART);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_BATT);
            } else if ((sensorToCheck & Shimmer.SENSOR_BATT) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXP_BOARD_A0);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXP_BOARD_A7);
            }
        } else {
            if ((sensorToCheck & Shimmer.SENSOR_EXG1_24BIT) > 0 || (sensorToCheck & Shimmer.SENSOR_EXG2_24BIT) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXG1_16BIT);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXG2_16BIT);
            }
            if ((sensorToCheck & Shimmer.SENSOR_EXG1_16BIT) > 0 || (sensorToCheck & Shimmer.SENSOR_EXG2_16BIT) > 0) {
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXG1_24BIT);
                enabledSensors = disableBit(enabledSensors, Shimmer.SENSOR_EXG2_24BIT);
            }
        }
        enabledSensors = enabledSensors ^ sensorToCheck;
        return enabledSensors;
    }

    private long disableBit(long number, long disablebitvalue) {
        if ((number & disablebitvalue) > 0) {
            number = number ^ disablebitvalue;
        }
        return number;
    }


    public void resetPlotActivity() {
        removePlotGroupChildColor();
        removeExapandableStates("PlotActivity");
        removePlotSelectedSignals();
        removePlotSelectedSignalsFormat();

    }

    public void removePlotGroupChildColor() {
        mGroupChildColor = null;
    }


    //convert the system time in miliseconds to a "readable" date format with the next format: YYYY MM DD HH MM SS
    private static String fromMilisecToDate(long miliseconds) {

        String date = "";
        Date dateToParse = new Date(miliseconds);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        date = dateFormat.format(dateToParse);

        return date;
    }

    public void closeAndRemoveFiles() {
        Iterator<Entry<String, Logging>> it = mLogShimmer.entrySet().iterator();
        while (it.hasNext()) {
            HashMap.Entry pairs = (HashMap.Entry) it.next();
            Logging mLog = (Logging) pairs.getValue();
            mLog.closeFile();
            MediaScannerConnection.scanFile(this, new String[]{mLog.getAbsoluteName()}, null, null);
            System.out.println(pairs.getKey() + " = " + pairs.getValue());
            it.remove(); // avoids a ConcurrentModificationException
        }
    }

    /* Out of scope methods */

    public boolean isEXGUsingECG16Configuration(String deviceBluetoothAddress) {
        return true;
    }

    public boolean isEXGUsingECG24Configuration(String deviceBluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.isEXGUsingECG24Configuration();
        return false;    }

    public boolean isEXGUsingEMG16Configuration(String deviceBluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.isEXGUsingEMG24Configuration();
        return false;
    }

    public boolean isEXGUsingEMG24Configuration(String deviceBluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.isEXGUsingEMG24Configuration();
        return false;
    }

    public boolean isEXGUsingTestSignal16Configuration(String deviceBluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.isEXGUsingTestSignal16Configuration();
        return false;
    }

    public boolean isEXGUsingTestSignal24Configuration(String deviceBluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.isEXGUsingTestSignal24Configuration();
        return false;
    }

    public void writeEXGSetting(String deviceBluetoothAddress, int i) {
        return;
    }

    public boolean is3DOrientationEnabled(String bluetoothAddress) {
        if (shimmerIsConnected())
            return shimmerDevice.is3DOrientatioEnabled();
        return false;
    }

    public boolean isHeartRateEnabled() {
        return false;
    }

    public boolean isHeartRateEnabledECG() {
        return false;
    }

    public int getEXGGain(String mBluetoothAddress) {
        return UNKNOWN_VALUE;
    }

    public int getEXGResolution(String mBluetoothAddress) {
        return UNKNOWN_VALUE;
    }

    public int get5VReg(String mBluetoothAddress) {
        return UNKNOWN_VALUE;
    }

    public double getBattLimitWarning(String mBluetoothAddress) {
        return UNKNOWN_VALUE;
    }

    public void setBattLimitWarning(String mBluetoothAddress, double newLimit) {
        return;
    }

    public void writeAccelRange(String mBluetoothAddress, int accelRange) {
        return;
    }

    public void writeGyroRange(String mBluetoothAddress, int gyroRange) {
        return;
    }

    public void writeMagRange(String mBluetoothAddress, int magRange) {
        return;
    }

    public void writePressureResolution(String mBluetoothAddress, int pressureRes) {
        return;
    }

    public void writeEXGGainSetting(String mBluetoothAddress, int exgGainNew) {
        return;
    }

    public void resetHearRateConfiguration(String mSensorToHeartRate) {
        return;
    }

    public void write5VReg(String mBluetoothAddress, int i) {
        return;
    }

    public void setAccelLowPower(String mBluetoothAddress, int i) {
        return;
    }

    public void setGyroLowPower(String mBluetoothAddress, int i) {
        return;
    }

    public void writeIntExpPower(String mBluetoothAddress, int i) {
        return;
    }

    public void setMagLowPower(String mBluetoothAddress, int i) {
        return;
    }

    public String getNumberOfBeatsToAverage() {
        return "";
    }

    public void setNumberOfBeatsToAverage(int numberOfBeatsToAverage) {
        return;
    }

    public void enableHeartRateECG(String deviceBluetoothAddress, boolean b, String mSensorToHeartRate) {
        return;
    }

    public void enableHeartRate(String deviceBluetoothAddress, boolean b, String mSensorToHeartRate) {
        return;
    }
}
