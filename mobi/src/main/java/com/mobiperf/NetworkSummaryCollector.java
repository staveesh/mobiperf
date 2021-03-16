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
import java.util.List;
import java.util.Scanner;
import java.util.TimeZone;

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
        String timestamp = summaryCheckin();
        long startTime = endTime-(24*3600*1000); //minus 24 hrs;
        if(timestamp != null){
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                startTime = df.parse(timestamp).getTime();
            } catch (ParseException e) {
                Log.e("NetworkSummaryCollector", "Invalid time returned from server");
            }
        }
        List<Package> packageList=getPackagesData();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String institution = prefs.getString(Config.PREF_KEY_USER_INSTITUTION, null);
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
                    appData.put("rx",wifiPayload.getRx());
                    appData.put("tx",wifiPayload.getTx());
                    wifiSummary.put(appData);
                }
                if(!mobilePayload.isEmptyPayload()) {
                    JSONObject appData = new JSONObject();
                    appData.put("name", packageName);
                    appData.put("rx",mobilePayload.getRx());
                    appData.put("tx",mobilePayload.getTx());
                    mobileSummary.put(appData);
                }
            }
            JSONObject blob = new JSONObject();
            blob.put("requestType","summary");
            blob.put("institution", institution);
            blob.put("deviceId", deviceId);
            blob.put("startTime", startTime);
            blob.put("endTime",endTime);
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
        } catch (IOException e){
            Log.e("NetworkSummaryCollector", "summaryCheckin: ", e);
        }
        return null;
    }

}
