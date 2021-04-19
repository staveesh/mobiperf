package com.mobiperf;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v7.preference.PreferenceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.mobiperf.util.MeasurementJsonConvertor;
import com.mobiperf.util.PhoneUtils;
import com.mobiperf.util.Util;
import com.mobiperf.util.model.Package;
import com.mobiperf.util.utils.NetworkStatsHelper;
import com.mobiperf.util.utils.PackageManagerHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;

public class NetworkSummaryCollector implements Runnable {
    public static final int READ_PHONE_STATE_REQUEST = 1;
    Context context;

    NetworkSummaryCollector() {
        context = SpeedometerApp.getCurrentApp().getApplicationContext();
    }

    @Override
    public void run() {
        Logger.d("Collector Thread has started");
        long endTime = System.currentTimeMillis();
        String timestamp = summaryCheckin();
        long startTime = endTime - (24 * 3600 * 1000); //minus 24 hrs;
        if (timestamp != null) {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                startTime = df.parse(timestamp).getTime();
            } catch (ParseException e) {
                Log.e("NetworkSummaryCollector", "Invalid time returned from server");
            }
        }
        List<Package> packageList = getPackagesData();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String institution = prefs.getString(Config.PREF_KEY_USER_INSTITUTION, null);
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceInfo().deviceId;
        JSONArray wifiSummary = new JSONArray();
        JSONArray mobileSummary = new JSONArray();
        try {
            for (Package pckg : packageList) {
                String packageName = pckg.getPackageName();
                DataPayload wifiPayload = getWifiBytes(packageName, startTime, endTime);
                SubscriptionManager subscriptionManager = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                    subscriptionManager = SubscriptionManager.from(context);
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        List<SubscriptionInfo> activeSubscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
                        TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                        for (SubscriptionInfo subscriptionInfo : activeSubscriptionInfoList) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                TelephonyManager manager1 = manager.createForSubscriptionId(subscriptionInfo.getSubscriptionId());
                                String operatorName = manager1.getNetworkOperatorName();
                                String subscriberId = manager1.getSubscriberId(); //use this subscriberId to do NetworkStatsManager stuff
                                DataPayload mobileSimPayload = getMobileBytes(packageName, startTime, endTime,subscriberId);
                                if(!mobileSimPayload.isEmptyPayload()){
                                    JSONObject appData = new JSONObject();
                                    appData.put("operatorName", operatorName);
                                    appData.put("name", packageName);
                                    appData.put("rx", mobileSimPayload.getRx());
                                    appData.put("tx", mobileSimPayload.getTx());
                                    mobileSummary.put(appData);
                                }
                            }
                        }
                    }
                }
                if (!wifiPayload.isEmptyPayload()) {
                    JSONObject appData = new JSONObject();
                    appData.put("operatorName", "WIFI");
                    appData.put("name", packageName);
                    appData.put("rx", wifiPayload.getRx());
                    appData.put("tx", wifiPayload.getTx());
                    wifiSummary.put(appData);
                }
            }
            JSONObject blob = new JSONObject();
            blob.put("requestType", "summary");
            blob.put("institution", institution);
            blob.put("deviceId", deviceId);
            blob.put("startTime", startTime);
            blob.put("endTime", endTime);
            blob.put("wifiSummary", wifiSummary);
            blob.put("mobileSummary", mobileSummary);
            Logger.d(blob.toString());
            Util.sendResult(blob.toString(), "Network Summary");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<Package> getPackagesData() {
        PackageManager packageManager = SpeedometerApp.getCurrentApp().getPackageManager();
        List<PackageInfo> packageInfoList = packageManager.getInstalledPackages(PackageManager.GET_META_DATA);
        Collections.sort(packageInfoList, new Comparator<PackageInfo>() {
            @Override
            public int compare(PackageInfo o1, PackageInfo o2) {
                return (int) ((o2.lastUpdateTime - o1.lastUpdateTime) / 10);
            }
        });
        List<Package> packageList = new ArrayList<>(packageInfoList.size());
        for (PackageInfo packageInfo : packageInfoList) {
            if (packageManager.checkPermission(Manifest.permission.INTERNET,
                    packageInfo.packageName) == PackageManager.PERMISSION_DENIED) {
                continue;
            }
            Package packageItem = new Package();
            packageItem.setVersion(packageInfo.versionName);
            packageItem.setPackageName(packageInfo.packageName);
            packageList.add(packageItem);
            ApplicationInfo ai = null;
            try {
                ai = packageManager.getApplicationInfo(packageInfo.packageName, PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            if (ai == null) {
                continue;
            }
            CharSequence appName = packageManager.getApplicationLabel(ai);
            if (appName != null) {
                packageItem.setName(appName.toString());
            }
        }
        return packageList;
    }

    private DataPayload getWifiBytes(String packageName, long start, long end) {
        int uid = PackageManagerHelper.getPackageUid(context, packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkStatsManager networkStatsManager = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStatsHelper networkStatsHelper = new NetworkStatsHelper(networkStatsManager, uid);
            return fillNetworkStatsPackageWifi(networkStatsHelper, start, end);
        }
        return null;
    }

    private DataPayload getMobileBytes(String packageName, long start, long end, String subscriberId) {
        int uid = PackageManagerHelper.getPackageUid(context, packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkStatsManager networkStatsManager = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStatsHelper networkStatsHelper = new NetworkStatsHelper(networkStatsManager, uid);
            return fillNetworkStatsPackageMobile(networkStatsHelper, start, end, subscriberId);
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private DataPayload fillNetworkStatsPackageWifi(NetworkStatsHelper networkStatsHelper, long start, long end) {
        long mobileWifiRx = networkStatsHelper.getPackageRxBytesWifi(start, end);
        long mobileWifiTx = networkStatsHelper.getPackageTxBytesWifi(start, end);
        return new DataPayload(mobileWifiRx, mobileWifiTx);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private DataPayload fillNetworkStatsPackageMobile(NetworkStatsHelper networkStatsHelper, long start, long end, String subscriberId) {
        long mobileWifiRx = networkStatsHelper.getPackageRxBytesMobile(start, end, subscriberId);
        long mobileWifiTx = networkStatsHelper.getPackageTxBytesMobile(start, end, subscriberId);
        return new DataPayload(mobileWifiRx, mobileWifiTx);
    }

    private String summaryCheckin() {
        try {
            Socket serverSocket = new Socket(Util.resolveServer(), Config.SERVER_PORT);
            Logger.d("Server Socket Connection Established");
            PrintWriter out = new PrintWriter(serverSocket.getOutputStream());
            JSONObject summaryCheckInRequest = new JSONObject();
            try {
                summaryCheckInRequest.put("requestType", "SUMMARY-CHECKIN");
                summaryCheckInRequest.put("deviceId", PhoneUtils.getPhoneUtils().getDeviceId());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            out.println(summaryCheckInRequest.toString());
            out.flush();
            Logger.d(summaryCheckInRequest.toString());
            Scanner in = new Scanner(serverSocket.getInputStream());
            String result = "";
            while (true) {
                if (in.hasNextLine()) {
                    result = in.nextLine();
                    break;
                }
            }
            Logger.d("the result of summary checkin is \n" + result);
            serverSocket.close();
            return result;
        } catch (IOException e) {
            Log.e("NetworkSummaryCollector", "summaryCheckin: ", e);
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    @SuppressLint("MissingPermission")
    public List<String> subscriberIdsOfMultipleSim(){
        SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
        List<String> result = new ArrayList<>();
        List<SubscriptionInfo> activeSubscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        for (SubscriptionInfo subscriptionInfo : activeSubscriptionInfoList) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                TelephonyManager manager1=manager.createForSubscriptionId(subscriptionInfo.getSubscriptionId());
                String subscriberId=manager1.getSubscriberId();
                result.add(subscriberId);
            }
        }
        return result;
    }

}
