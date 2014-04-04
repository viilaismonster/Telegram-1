/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package org.telegram.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.view.ViewConfiguration;

import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NativeLoader;
import org.telegram.messenger.ScreenReceiver;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Views.BaseFragment;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ApplicationLoader extends Application {
    private GoogleCloudMessaging gcm;
    private AtomicInteger msgId = new AtomicInteger();
    private String regid;
    public static final String EXTRA_MESSAGE = "message";
    public static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static long lastPauseTime;
    public static Bitmap cachedWallpaper = null;

    public static volatile Context applicationContext = null;
    public static volatile Handler applicationHandler = null;
    private static volatile boolean applicationInited = false;
    public static volatile boolean isScreenOn = false;

    public static ArrayList<BaseFragment> fragmentsStack = new ArrayList<BaseFragment>();

    public static void postInitApplication() {
        if (applicationInited) {
            return;
        }

        applicationInited = true;

        NativeLoader.initNativeLibs(applicationContext);

        try {
            LocaleController.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager)ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            FileLog.e("tmessages", "screen state = " + isScreenOn);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }

        UserConfig.loadConfig();
        if (UserConfig.currentUser != null) {
            boolean changed = false;
            SharedPreferences preferences = applicationContext.getSharedPreferences("Notifications", MODE_PRIVATE);
            int v = preferences.getInt("v", 0);
            if (v != 1) {
                SharedPreferences preferences2 = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences2.edit();
                if (preferences.contains("view_animations")) {
                    editor.putBoolean("view_animations", preferences.getBoolean("view_animations", false));
                }
                if (preferences.contains("selectedBackground")) {
                    editor.putInt("selectedBackground", preferences.getInt("selectedBackground", 1000001));
                }
                if (preferences.contains("selectedColor")) {
                    editor.putInt("selectedColor", preferences.getInt("selectedColor", 0));
                }
                if (preferences.contains("fons_size")) {
                    editor.putInt("fons_size", preferences.getInt("fons_size", 16));
                }
                editor.commit();
                editor = preferences.edit();
                editor.putInt("v", 1);
                editor.remove("view_animations");
                editor.remove("selectedBackground");
                editor.remove("selectedColor");
                editor.remove("fons_size");
                editor.commit();
            }

            MessagesController.getInstance().users.put(UserConfig.clientUserId, UserConfig.currentUser);
            ConnectionsManager.getInstance().applyCountryPortNumber(UserConfig.currentUser.phone);
        }

        ApplicationLoader app = (ApplicationLoader)ApplicationLoader.applicationContext;
        app.initPlayServices();
    }

    public static void deleteDir (File dir)
    {
        if (dir.isDirectory())
        {
            File[] files = dir.listFiles();
            for (File f:files)
            {
                deleteDir(f);
            }
            dir.delete();
        }
        else
            dir.delete();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // customized configure
        try {
            final String configPath = Environment.getExternalStorageDirectory().getPath()+"/";
            FileInputStream fi = new FileInputStream(configPath + "teleopen.conf");
            BufferedReader reader = new BufferedReader(new InputStreamReader(fi));
            StringBuilder log = new StringBuilder();
            String line = null;
            while((line = reader.readLine()) != null) {
                String key = line.indexOf("=")>0?
                        line.substring(0, line.indexOf("=")).trim().toLowerCase()
                        : line.trim();
                String val = line.substring(line.indexOf("=")+1).trim();
                if(key.equals("dc")) {
                    if(val.indexOf(":")>0) {
                        ConnectionsManager.ADDRESS = val.substring(0, val.indexOf(":")).trim();
                        ConnectionsManager.PORT = Integer.parseInt(val.substring(val.indexOf(":")+1).trim());
                    } else {
                        ConnectionsManager.ADDRESS = val.trim();
                    }
                    log.append("dc->"+ConnectionsManager.ADDRESS+":"+ConnectionsManager.PORT+"\n");
                } else if(key.equals("clean")
                        || (key.equals("cleanonce") && !new File(configPath+"teleopen_clean_lock").exists())) {
                    deleteDir(getApplicationContext().getFilesDir());
                    deleteDir(getApplicationContext().getCacheDir());
                    PreferenceManager.getDefaultSharedPreferences(this).edit().clear().commit();
                    log.append("do cleanup\n");
                    FileOutputStream lock = new FileOutputStream(new File(configPath+"teleopen_clean_lock"));
                    lock.close();
                }
            }
            reader.close();
            fi.close();
            Toast.makeText(this.getApplicationContext(), "conf\n" + log.toString().trim(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this.getApplicationContext(),
                    "read sdcard://teleopen.conf failed: "+e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        lastPauseTime = System.currentTimeMillis();
        applicationContext = getApplicationContext();

        applicationHandler = new Handler(applicationContext.getMainLooper());

        java.lang.System.setProperty("java.net.preferIPv4Stack", "true");
        java.lang.System.setProperty("java.net.preferIPv6Addresses", "false");

        try {
            ViewConfiguration config = ViewConfiguration.get(this);
            Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if(menuKeyField != null) {
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            Utilities.checkDisplaySize();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void resetLastPauseTime() {
        lastPauseTime = 0;
        ConnectionsManager.getInstance().applicationMovedToForeground();
    }

    private void initPlayServices() {
        if (checkPlayServices()) {
            gcm = GoogleCloudMessaging.getInstance(this);
            regid = getRegistrationId();

            if (regid.length() == 0) {
                registerInBackground();
            } else {
                sendRegistrationIdToBackend(false);
            }
        } else {
            FileLog.d("tmessages", "No valid Google Play Services APK found.");
        }
    }

    private boolean checkPlayServices() {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        return resultCode == ConnectionResult.SUCCESS;
        /*if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, this, PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                Log.i("tmessages", "This device is not supported.");
            }
            return false;
        }
        return true;*/
    }

    private String getRegistrationId() {
        final SharedPreferences prefs = getGCMPreferences(applicationContext);
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId.length() == 0) {
            FileLog.d("tmessages", "Registration not found.");
            return "";
        }
        int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion();
        if (registeredVersion != currentVersion) {
            FileLog.d("tmessages", "App version changed.");
            return "";
        }
        return registrationId;
    }

    private SharedPreferences getGCMPreferences(Context context) {
        return getSharedPreferences(ApplicationLoader.class.getSimpleName(), Context.MODE_PRIVATE);
    }

    public static int getAppVersion() {
        try {
            PackageInfo packageInfo = applicationContext.getPackageManager().getPackageInfo(applicationContext.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    private void registerInBackground() {
        AsyncTask<String, String, Boolean> task = new AsyncTask<String, String, Boolean>() {
            @Override
            protected Boolean doInBackground(String... objects) {
                if (gcm == null) {
                    gcm = GoogleCloudMessaging.getInstance(applicationContext);
                }
                int count = 0;
                while (count < 1000) {
                    try {
                        count++;
                        regid = gcm.register(BuildVars.GCM_SENDER_ID);
                        sendRegistrationIdToBackend(true);
                        storeRegistrationId(applicationContext, regid);
                        return true;
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                    try {
                        if (count % 20 == 0) {
                            Thread.sleep(60000 * 30);
                        } else {
                            Thread.sleep(5000);
                        }
                    } catch (InterruptedException e) {
                        FileLog.e("tmessages", e);
                    }
                }
                return false;
            }
        }.execute(null, null, null);
    }

    private void sendRegistrationIdToBackend(final boolean isNew) {
        Utilities.stageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                UserConfig.pushString = regid;
                UserConfig.registeredForPush = !isNew;
                UserConfig.saveConfig(false);
                Utilities.RunOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        MessagesController.getInstance().registerForPush(regid);
                    }
                });
            }
        });
    }

    private void storeRegistrationId(Context context, String regId) {
        final SharedPreferences prefs = getGCMPreferences(context);
        int appVersion = getAppVersion();
        FileLog.e("tmessages", "Saving regId on app version " + appVersion);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PROPERTY_REG_ID, regId);
        editor.putInt(PROPERTY_APP_VERSION, appVersion);
        editor.commit();
    }
}
