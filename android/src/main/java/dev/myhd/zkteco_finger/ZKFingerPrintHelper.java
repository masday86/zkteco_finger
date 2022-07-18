package dev.myhd.zkteco_finger;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import com.zkteco.android.biometric.core.device.ParameterHelper;
import com.zkteco.android.biometric.core.device.TransportType;
import com.zkteco.android.biometric.core.utils.LogHelper;
import com.zkteco.android.biometric.core.utils.ToolUtils;
import com.zkteco.android.biometric.module.fingerprint.FingerprintCaptureListener;
import com.zkteco.android.biometric.module.fingerprint.FingerprintFactory;
import com.zkteco.android.biometric.module.fingerprint.FingerprintSensor;
import com.zkteco.android.biometric.module.fingerprint.exception.FingerprintSensorException;
import dev.myhd.zkteco_finger.service.BroadContanst;
import dev.myhd.zkteco_finger.service.FingerprintIdentity;
import dev.myhd.zkteco_finger.service.FingerprintTemplateReveiver;
import dev.myhd.zkteco_finger.util.CompressUtil;
import dev.myhd.zkteco_finger.util.DeviceUitl;
import dev.myhd.zkteco_finger.util.FingerListener;
import dev.myhd.zkteco_finger.util.FingerStatusType;
import dev.myhd.zkteco_finger.util.SharedPreferencesHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.content.ContentValues.TAG;

public class ZKFingerPrintHelper {
    private static final int VID = 6997; // Silkid VID always 6997
    private static final int PID = 2561; // Silkid PID always 289
    private static final String ACTION_USB_PERMISSION = "com.zkteco.android.biometric.core.device.usbscsi.USB_PERMISSION";// 可自定义//Customizable
    private static UsbManager mUsbManager;
    FingerprintTemplateReveiver fingerprintTemplateReveiver;
    IntentFilter intentFilter;
    SharedPreferencesHelper sharedPreferencesHelper;
    UsbDevice zktecoFingerprintUsbDevice;
    private FingerprintSensor fingerprintSensor = null;
    private boolean isRegister = false;
    private int uid = 1;
    private String userId = "";
    private byte[][] regtemparray = new byte[3][2048]; // register template buffer array
    private int enrollidx = 0;
    private FingerListener mFingerListener;
    private Activity mActivity;
    final FingerprintCaptureListener listener = new FingerprintCaptureListener() {
        @Override
        public void captureOK(int captureMode, byte[] imageBuffer, int[] imageAttributes, byte[] templateBuffer) {
            final int[] attributes = imageAttributes;
            final byte[] imgBuffer = imageBuffer;
            final byte[] tmpBuffer = templateBuffer;
            final int capMode = captureMode;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (capMode == FingerprintCaptureListener.MODE_CAPTURE_TEMPLATEANDIMAGE) {
                        Bitmap mBitMap = ToolUtils.renderCroppedGreyScaleBitmap(imgBuffer, attributes[0],
                                attributes[1]);
                        mFingerListener.onCaptureFinger(mBitMap);
                    }
                    if (isRegister) {
                        byte[] bufids = new byte[256];
                        int ret = FingerprintService.identify(tmpBuffer, bufids, 55, 1);
                        if (ret > 0) {
                            String strRes[] = new String(bufids).split("\t");
                            mFingerListener.onStatusChange(
                                    "the finger already enroll by " + strRes[0] + ",cancel enroll",
                                    FingerStatusType.ENROLL_ALREADY_EXIST, strRes[0], "");
                            isRegister = false;
                            enrollidx = 0;
                            return;
                        }
                        if (enrollidx > 0 && FingerprintService.verify(regtemparray[enrollidx - 1], tmpBuffer) <= 0) {
                            mFingerListener.onStatusChange("please press the same finger 3 times for the enrollment",
                                    FingerStatusType.ENROLL_STARTED, "", "");
                            return;
                        }
                        System.arraycopy(tmpBuffer, 0, regtemparray[enrollidx], 0, 2048);
                        enrollidx++;
                        if (enrollidx == 3) {
                            byte[] regTemp = new byte[2048];
                            if (0 < FingerprintService.merge(regtemparray[0], regtemparray[1], regtemparray[2],
                                    regTemp)) {
                                String id = "wiot-" + uid++;
                                if (ZKFingerPrintHelper.this.userId == null
                                        || ZKFingerPrintHelper.this.userId.isEmpty())
                                    ZKFingerPrintHelper.this.userId = id;
                                FingerprintService.save(regTemp, ZKFingerPrintHelper.this.userId);
                                // sharedPreferencesHelper.put("uid",
                                // Base64Util.getBinaryStrFromByteArr(regTemp));
                                String base64FingerprintData = "";
                                try {
                                    base64FingerprintData = CompressUtil.compressString(regTemp);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    Log.d("compress finger error", e.toString());
                                }
                                mFingerListener.onStatusChange("Enroll Succ", FingerStatusType.ENROLL_SUCCESS,
                                        ZKFingerPrintHelper.this.userId, base64FingerprintData);
                            } else {
                                mFingerListener.onStatusChange("Enroll Fail", FingerStatusType.ENROLL_FAILED, "", "");
                            }
                            isRegister = false;
                        } else {
                            String enrollIndex = String.valueOf(3 - enrollidx);
                            mFingerListener.onStatusChange("You need to press the " + enrollIndex + " time fingerprint",
                                    FingerStatusType.ENROLL_CONFIRM, "", enrollIndex);
                        }
                    } else {
                        byte[] bufids = new byte[256];
                        int ret = FingerprintService.identify(tmpBuffer, bufids, 55, 1);
                        if (ret > 0) {
                            String strRes[] = new String(bufids).trim().split("\t");
                            String userId = strRes[0];
                            String score;
                            try {
                                score = strRes[1];
                            } catch (NumberFormatException exception) {
                                score = "";
                            }
                            mFingerListener.onStatusChange("Identify Succ, userid:" + userId + ", score:" + strRes[1],
                                    FingerStatusType.VERIFIED_SUCCESS, userId, score);
                            FingerprintIdentity.sendIdentityResult(mActivity, 0, userId);
                        } else {
                            mFingerListener.onStatusChange("Identify Fail", FingerStatusType.VERIFIED_FAILED, "", "");
                            FingerprintIdentity.sendIdentityResult(mActivity, 1, "");
                        }
                    }
                }
            });
        }

        @Override
        public void captureError(FingerprintSensorException e) {
            final FingerprintSensorException exp = e;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mFingerListener.onStatusChange(
                            "captureError  errno=" + exp.getErrorCode() + ",Internal error code: "
                                    + exp.getInternalErrorCode() + ",message=" + exp.getMessage(),
                            FingerStatusType.CAPTURE_ERROR, "", "");
                }
            });
        }
    };
    private boolean bstart = false;
    private BroadcastReceiver mUsbPermissionActionReceiver;

//    public boolean isDeviceSupported() {
//        boolean isSupported = true;
//        try {
//            FingerprintService.count();
//        } catch (UnsatisfiedLinkError error) {
//            Log.d("Zkteco FingerPrint", error.toString());
//            isSupported = false;
//        }
//        return isSupported;
//    }

    public ZKFingerPrintHelper(Activity mActivity, FingerListener mFingerListener) {
        Log.d("Zkteco FingerPrint", "ZKFingerPrintHelper");

        this.mActivity = mActivity;
        this.mFingerListener = mFingerListener;
        // this.mFingerListener.onStatusChange("ZK Finger Print Helper created",
        // FingerStatusType.INITIALIZED,"",0);
        List<String> devieList = DeviceUitl.getAllExterSdcardPath();
        for (String s : devieList) {
            mFingerListener.onStatusChange(s, FingerStatusType.MOUNTED, "", "");
        }
    }

    public void openConnection(boolean isLogEnabled) {
        Log.d("Zkteco FingerPrint", "openConnection");

        tryGetUsbPermission();
        // sharedPreferencesHelper = new SharedPreferencesHelper(this,"template");
        // Start fingerprint sensor
        initFingerprintSensor(isLogEnabled);
        // bind broadcast
        bindBroadcast();
        // this.mFingerListener.onStatusChange("ZK Finger open connection",
        // FingerStatusType.INITIALIZED,"",0);

    }

    private void requestPermission() {

    }

    public void initFingerprintSensor(boolean isLogEnabled) {
        Log.d("Zkteco FingerPrint", "initFingerprintSensor");

        // Define output log level
        if (isLogEnabled)
            LogHelper.setLevel(Log.VERBOSE);
        else LogHelper.setLevel(10);
        // Start fingerprint sensor
        Map fingerprintParams = new HashMap();
        // set vid
        fingerprintParams.put(ParameterHelper.PARAM_KEY_VID, VID);
        // set pid
        fingerprintParams.put(ParameterHelper.PARAM_KEY_PID, PID);
        fingerprintSensor = FingerprintFactory.createFingerprintSensor(mActivity, TransportType.USB, fingerprintParams);

        // String temps =
        // sharedPreferencesHelper.getSharedPreference("uid","").toString();
        //
        // if(!temps.equals("")){
        // byte[] bytes = Base64Util.decode(temps);
        // int res = FingerprintService.save(bytes,"wiot-1");
        // mFingerListener.onStatusChange("save template res " +res);
        // }else{
        // mFingerListener.onStatusChange( "template is null ");
        // }

    }

    public void bindBroadcast() {
        Log.d("Zkteco FingerPrint", "bindBroadcast");

        fingerprintTemplateReveiver = new FingerprintTemplateReveiver();
        intentFilter = new IntentFilter();
        intentFilter.addAction(BroadContanst.FINGERPRINT_TEMPLATE_ACTION);
        mActivity.registerReceiver(fingerprintTemplateReveiver, intentFilter);
    }

    public void closeConnection() {
        Log.d("Zkteco FingerPrint", "closeConnection");

        // Destroy fingerprint sensor when it's not used
        FingerprintFactory.destroy(fingerprintSensor);
        if (fingerprintTemplateReveiver != null) {
            mActivity.unregisterReceiver(fingerprintTemplateReveiver);
            fingerprintTemplateReveiver = null;
        }

        try {
            fingerprintSensor.close(0);
        } catch (FingerprintSensorException e) {
            e.printStackTrace();
        }
    }

    public void register(String userId, String fingerData) {
        Log.d("Zkteco FingerPrint", "register");

        byte[] bytes = new byte[0];
        try {
            bytes = CompressUtil.uncompressString(fingerData);
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("uncompress error", e.toString());
        }
        FingerprintService.save(bytes, userId);
        mFingerListener.onStatusChange("finger save to finger database", FingerStatusType.FINGER_REGISTERED, userId,
                fingerData);

    }

    public void clear() {
        Log.d("Zkteco FingerPrint", "clear");

        FingerprintService.clear();
        mFingerListener.onStatusChange("finger database cleared", FingerStatusType.FINGER_CLEARED, "", "");

    }
    boolean hasPermission;
    public void startFingerSensor(String userId) {
        Log.d("Zkteco FingerPrint", "startFingerSensor");

        this.userId = userId;

        //boolean hasPermission = mUsbManager.hasPermission(getFingerprintUsbDevice());
        if (!hasPermission) {
            mFingerListener.onStatusChange("No permission to start finger sensor", FingerStatusType.FINGER_USB_PERMISSION_ERROR, userId, "");
            return;
        }

        try {
            if (bstart) {
                mFingerListener.onStatusChange("already started", FingerStatusType.STARTED_ALREADY, "", "");
                return;
            }
            isRegister = false;
            enrollidx = 0;
            int limit[] = new int[1];
            // init algorithm share library
            if (0 != FingerprintService.init(limit)) {
                mFingerListener.onStatusChange("init fingerprint fail", FingerStatusType.STARTED_FAILED, "", "");
                return;
            }
            // open sensor
            fingerprintSensor.open(0);
            fingerprintSensor.setFingerprintCaptureListener(0, listener);
            fingerprintSensor.startCapture(0);
            fingerprintSensor.setFingerprintCaptureMode(0, FingerprintCaptureListener.MODE_CAPTURE_TEMPLATEANDIMAGE);
            bstart = true;
            // App.setFingerprintSensor(fingerprintSensor);
            mFingerListener.onStatusChange("start capture succ", FingerStatusType.STARTED_SUCCESS, "", "");
        } catch (FingerprintSensorException e) {
            LogHelper.e(e.getMessage());
            mFingerListener.onStatusChange("begin capture fail errno=" + e.getErrorCode() + ",Internal error code: "
                    + e.getInternalErrorCode() + ",message=" + e.getMessage(), FingerStatusType.STARTED_ERROR, "", "");
        }
    }

    public void stopFingerSensor() {
        Log.d("Zkteco FingerPrint", "stopFingerSensor");

        try {
            if (bstart) {
                // stop capture
                fingerprintSensor.stopCapture(0);
                bstart = false;
                fingerprintSensor.close(0);
                isRegister = false;
                enrollidx = 0;
                mFingerListener.onStatusChange("stop capture succ", FingerStatusType.STOPPED_SUCCESS, "", "");
            } else {
                mFingerListener.onStatusChange("already stop", FingerStatusType.STOPPED_ALREADY, "", "");
            }
        } catch (FingerprintSensorException e) {
            mFingerListener.onStatusChange("stop fail, errno=" + e.getErrorCode() + "\nmessage=" + e.getMessage(),
                    FingerStatusType.STOPPED_ERROR, "", "");
        }
    }

    public void enrollFinger(String userId) {
        Log.d("Zkteco FingerPrint", "enrollFinger");

        this.userId = userId;
        if (bstart) {
            byte[] temp = new byte[2048];
            try {
                // clear last template
                fingerprintSensor.acquireTemplate(0, temp);
            } catch (FingerprintSensorException e) {
                e.printStackTrace();
            }
            isRegister = true;
            enrollidx = 0;
            mFingerListener.onStatusChange("You need to press the 3 time fingerprint", FingerStatusType.ENROLL_STARTED,
                    "", enrollidx + "");
        } else {
            mFingerListener.onStatusChange("please begin capture first", FingerStatusType.VERIFIED_START_FIRST, "", "");
        }
    }

//    public boolean isDeviceSupported() {
//        boolean isSupported = true;
//        try {
//            FingerprintService.count();
//        } catch (UnsatisfiedLinkError error) {
//            Log.d("Zkteco FingerPrint", error.toString());
//            isSupported = false;
//        }
//        return isSupported;
//    }

    public void verifyFinger(String userId) {
        Log.d("Zkteco FingerPrint", "verifyFinger");

        this.userId = userId;
        if (bstart) {
            isRegister = false;
            enrollidx = 0;
        } else {
            mFingerListener.onStatusChange("please begin capture first", FingerStatusType.VERIFIED_START_FIRST, "", "");
        }
    }

    // check if zkteco Fingerprint Usb Device is supported
    public boolean isDeviceSupported() {
        Log.d("Zkteco FingerPrint", "isDeviceSupported");

        return getFingerprintUsbDevice() != null;
    }

    public UsbDevice getFingerprintUsbDevice() {
        Log.d("Zkteco FingerPrint", "getFingerprintUsbDevice");
        if (zktecoFingerprintUsbDevice != null) {
            return zktecoFingerprintUsbDevice;
        }
        if (mUsbPermissionActionReceiver == null) {
            mUsbPermissionActionReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_USB_PERMISSION.equals(action)) {
                        context.unregisterReceiver(this);// 解注册//Unregister
                        synchronized (this) {
                            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (null != usbDevice) {
                                    Toast.makeText(mActivity, usbDevice.getDeviceName() + "get permission success",
                                            Toast.LENGTH_SHORT).show();
                                    Log.e(TAG, usbDevice.getDeviceName() + "已获取USB权限");// Has obtained USB permissions
                                    mFingerListener.onStatusChange(usbDevice.getDeviceName() + "get permission success",
                                            FingerStatusType.FINGER_USB_PERMISSION_GRANTED, "", "");
                                }
                            } else {
                                // user choose NO for your previously popup window asking for grant permission
                                // for this usb device
                                Toast.makeText(mActivity, "USB permission disagree，Permission denied for device",
                                        Toast.LENGTH_SHORT).show();
                                Log.e(TAG, "USB权限已被拒绝，Permission denied for device" + usbDevice);// USB permissions have
                                // been denied
                                mFingerListener.onStatusChange(
                                        "USB permission disagree，Permission denied for device " + usbDevice.getDeviceName(),
                                        FingerStatusType.FINGER_USB_PERMISSION_DENIED, "", "");

                            }
                        }

                    }
                }
            };

            mUsbManager = (UsbManager) mActivity.getSystemService(Context.USB_SERVICE);

            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

            if (mUsbPermissionActionReceiver != null) {
                mActivity.registerReceiver(mUsbPermissionActionReceiver, filter);
            }
        }


        for (final UsbDevice usbDevice : mUsbManager.getDeviceList().values()) {

            if (usbDevice.getVendorId() == 6997 && usbDevice.getProductId() == 289)// 身份证设备USB//ID card device USB
            {
                zktecoFingerprintUsbDevice = usbDevice;
                break;
            }

        }
        if (zktecoFingerprintUsbDevice == null) {
            Log.e(TAG, "ID card usb not found");// ID card USB not found
        }
        return zktecoFingerprintUsbDevice;


    }

    // 获取USB权限//Get USB permissions
    public void tryGetUsbPermission() {
        Log.d("Zkteco FingerPrint", "tryGetUsbPermission");

        UsbDevice fingerprintUsbDevice = getFingerprintUsbDevice();

        if (fingerprintUsbDevice == null)
            return;

        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(mActivity, 0, new Intent(ACTION_USB_PERMISSION),
                0);

        Log.e(TAG, fingerprintUsbDevice.getDeviceName() + "ID card USB found");// ID card USB has been found
        if (mUsbManager.hasPermission(fingerprintUsbDevice)) {
            hasPermission=true;
            Log.e(TAG, fingerprintUsbDevice.getDeviceName() + "USB permission has been obtained");// Has obtained USB permissions
            mFingerListener.onStatusChange(fingerprintUsbDevice.getDeviceName() + "permission already granted success",
                    FingerStatusType.FINGER_USB_PERMISSION_ALREADY_GRANTED, "", "");
        } else {
            Log.e(TAG, fingerprintUsbDevice.getDeviceName() + "Request for USB permission");// Request for USB access
            mUsbManager.requestPermission(fingerprintUsbDevice, mPermissionIntent);
        }

    }

}
