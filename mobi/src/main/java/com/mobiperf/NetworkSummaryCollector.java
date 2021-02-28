package com.mobiperf;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.support.v7.preference.PreferenceManager;

import com.mobiperf.util.PhoneUtils;
import com.mobiperf.util.Util;
import com.mobiperf.util.model.Package;
import com.mobiperf.util.utils.NetworkStatsHelper;
import com.mobiperf.util.utils.PackageManagerHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class NetworkSummaryCollector implements Runnable {
    public static final int READ_PHONE_STATE_REQUEST = 1;
    Context context ;

    NetworkSummaryCollector(){
        context  = SpeedometerApp.getCurrentApp().getApplicationContext();
    }

    @Override
    public void run() {
        Logger.d("Collector Thread has started");
        long endTime = System.currentTimeMillis();
        long startTime = endTime-(24*3600*1000); //minus 24 hrs
        List<Package> packageList=getPackagesData();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String university = prefs.getString(Config.PREF_KEY_USER_UNIVERSITY, null);
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceInfo().deviceId;
        JSONArray wifiSummary = new JSONArray();
        JSONArray mobileSummary = new JSONArray();
        try {
            for (Package pckg : packageList) {
                String packageName = pckg.getPackageName();
                DataPayload wifiPayload = getBytes(packageName,startTime,endTime, ConnectivityManager.TYPE_WIFI);
                DataPayload mobilePayload = getBytes(packageName,startTime,endTime, ConnectivityManager.TYPE_MOBILE);
                //build json here
                if(!wifiPayload.isEmptyPayload()) {
                    JSONObject appData = new JSONObject();
                    appData.put("name", packageName);
                    appData.put("Rx",wifiPayload.getRx());
                    appData.put("Tx",wifiPayload.getTx());
                    wifiSummary.put(appData);
                }
                if(!mobilePayload.isEmptyPayload()) {
                    JSONObject appData = new JSONObject();
                    appData.put("name", packageName);
                    appData.put("Rx",mobilePayload.getRx());
                    appData.put("Tx",mobilePayload.getTx());
                    mobileSummary.put(appData);
                }
            }
            JSONObject blob = new JSONObject();
            blob.put("requestType","summary");
            blob.put("institution", university);
            blob.put("deviceId", deviceId);
            blob.put("userName", SpeedometerApp.getCurrentApp().getSelectedAccount());
            blob.put("Date",endTime);
            blob.put("wifiSummary",wifiSummary);
            blob.put("mobileSummary",mobileSummary);
            Logger.d(blob.toString());
            Util.sendResult(blob.toString(),"Network Summary");
        }
        catch (Exception e){
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

    private DataPayload getBytes(String packageName,long start,long end, int type) {
        int uid = PackageManagerHelper.getPackageUid(context, packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkStatsManager networkStatsManager = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStatsHelper networkStatsHelper = new NetworkStatsHelper(networkStatsManager, uid);
            return fillNetworkStatsPackage(networkStatsHelper,start,end, type);
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private DataPayload fillNetworkStatsPackage(NetworkStatsHelper networkStatsHelper,long start,long end, int type) {
        long mobileWifiRx = networkStatsHelper.getPackageRxBytesWifi(start,end, type);
        long mobileWifiTx = networkStatsHelper.getPackageTxBytesWifi(start,end, type);
        return new DataPayload(mobileWifiRx,mobileWifiTx);
    }

}
