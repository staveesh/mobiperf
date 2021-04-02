/* Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobiperf;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.RequiresApi;
import android.support.design.widget.TabLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TabHost;
import android.widget.TextView;


import com.mobiperf.MeasurementScheduler.SchedulerBinder;
import com.mobiperf.util.MeasurementJsonConvertor;
import com.mobiperf.util.PhoneUtils;
import com.mobiperf.util.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.Security;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Vector;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.android.schedulers.AndroidSchedulers;
import ua.naiksoftware.stomp.Stomp;
import ua.naiksoftware.stomp.StompClient;
import ua.naiksoftware.stomp.dto.StompHeader;

/**
 * The main UI thread that manages different tabs
 */
public class SpeedometerApp extends AppCompatActivity implements TabLayout.OnTabSelectedListener {

    public static final String TAG = "MobiPerf";

    public static final int PERMISSIONS_REQUEST_CODE = 6789;
    public static EnumMap<Config.PERMISSION_IDS, Boolean> PERMISSION_SETTINGS;

    private String institution = null;

    private MeasurementScheduler scheduler;
    private TabHost tabHost;
    private boolean isBound = false;
    private boolean isBindingToService = false;
    private BroadcastReceiver receiver;
    TextView statusBar, statsBar, instTxt;
    ImageView helpImage;

    private static SpeedometerApp speedometerApp;
    //This is our tablayout
    private TabLayout tabLayout;

    //This is our viewPager
    private ViewPager viewPager;

    private WebSocketConnector webSocketConnector;

    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Logger.d("onServiceConnected called");
            Logger.d("Institution : "+institution);
            // We've bound to LocalService, cast the IBinder and get LocalService
            // instance
            SchedulerBinder binder = (SchedulerBinder) service;
            scheduler = binder.getService();
            isBound = true;
            isBindingToService = false;
            if(isMeasurementEnabled()) {
                initializeStatusBar();
                SpeedometerApp.this.sendBroadcast(new UpdateIntent("",
                        UpdateIntent.SCHEDULER_CONNECTED_ACTION));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Logger.d("onServiceDisconnected called");
            isBound = false;
        }
    };

    /**
     * Returns the scheduler singleton instance. Should only be called from the UI thread.
     */
    public MeasurementScheduler getScheduler() {
        if (isBound) {
            return this.scheduler;
        } else {
            bindToService();
            return null;
        }
    }

    /**
     * Returns the tab host. Allows child tabs to request focus changes, etc...
     */
    public TabHost getSpeedomterTabHost() {
        return tabHost;
    }

    private void setPauseIconBasedOnSchedulerState(MenuItem item) {
        if (this.scheduler != null && item != null) {
            if (this.scheduler.isPauseRequested()) {
                item.setIcon(android.R.drawable.ic_media_play);
                item.setTitle(R.string.menuResume);
            } else {
                item.setIcon(android.R.drawable.ic_media_pause);
                item.setTitle(R.string.menumPause);
            }
        }
    }

    /**
     * Populate the application menu. Only called once per onCreate()
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    /**
     * Adjust menu items depending on system state. Called every time the
     * menu pops up
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        setPauseIconBasedOnSchedulerState(menu.findItem(R.id.menuPauseResume));
        return true;
    }

    /**
     * React to menu item selections
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menuPauseResume:
                if (this.scheduler != null) {
                    if (this.scheduler.isPauseRequested()) {
                        this.scheduler.resume();
                    } else {
                        this.scheduler.pause();
                    }
                }
                return true;
            case R.id.menuQuit: {
                Logger.i("User requests exit. Quitting the app");
                quitApp();
                return true;
            }
            case R.id.menuSettings: {
                Intent settingsActivity = new Intent(getBaseContext(), MobiPerfSettings.class);
                startActivity(settingsActivity);
                return true;
            }
            case R.id.aboutPage: {
                Intent intent = new Intent(getBaseContext(), About.class);
                startActivity(intent);
                return true;
            }
            case R.id.menuLog: {
                Intent intent = new Intent(getBaseContext(), SystemConsoleActivity.class);
                startActivity(intent);
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Logger.d("onCreate called");
        super.onCreate(savedInstanceState);
        speedometerApp = this;
        setContentView(R.layout.main);
        if(institution == null){
            institutionDialogWrapper();
        }
        /* Set the DNS cache TTL to 0 such that measurements can be more accurate.
         * However, it is known that the current Android OS does not take actions
         * on these properties but may enforce them in future versions.
         */
        System.setProperty("networkaddress.cache.ttl", "0");
        System.setProperty("networkaddress.cache.negative.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        prepareUI();
        if(institution != null)
            initServiceAndReceiver();
    }

    private void initServiceAndReceiver(){
        // We only need one instance of the scheduler thread
        Intent intent = new Intent(this, MeasurementScheduler.class);
        this.startService(intent);

        this.receiver = new BroadcastReceiver() {
            @Override
            // All onXyz() callbacks are single threaded
            public void onReceive(Context context, Intent intent) {
                // Update the status bar on SYSTEM_STATUS_UPDATE_ACTION intents
                String statusMsg = intent.getStringExtra(UpdateIntent.STATUS_MSG_PAYLOAD);
                if (statusMsg != null) {
                    Log.d("Broadcast information", statusMsg);
                    updateStatusBar(statusMsg);
                } else if (scheduler != null && isMeasurementEnabled()) {
                    initializeStatusBar();
                }

                String statsMsg = intent.getStringExtra(UpdateIntent.STATS_MSG_PAYLOAD);
                if (statsMsg != null && isMeasurementEnabled()) {
                    updateStatsBar(statsMsg);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UpdateIntent.SYSTEM_STATUS_UPDATE_ACTION);
        this.registerReceiver(this.receiver, filter);
    }

    private void prepareUI(){
        if(isMeasurementEnabled()) {
            Resources res = getResources(); // Resource object to get Drawables

            statusBar = findViewById(R.id.systemStatusBar);
            statsBar = findViewById(R.id.systemStatsBar);

            //Adding toolbar to the activity
            Toolbar toolbar = findViewById(R.id.toolbar);
            toolbar.setVisibility(View.VISIBLE);
            setSupportActionBar(toolbar);

            //Initializing the tablayout
            tabHost = findViewById(R.id.tabhost);
            tabHost.setVisibility(View.VISIBLE);
            tabLayout = (TabLayout) findViewById(R.id.tabLayout);

            //Adding the tabs using addTab() method
            tabLayout.addTab(tabLayout.newTab().setText(MeasurementCreationFragment.TAB_TAG).setIcon(R.drawable.ic_tab_user_measurement));
            tabLayout.addTab(tabLayout.newTab().setText(ResultsConsoleFragment.TAB_TAG).setIcon(R.drawable.ic_tab_results_icon));
            tabLayout.addTab(tabLayout.newTab().setText(MeasurementScheduleConsoleFragment.TAB_TAG).setIcon(R.drawable.ic_tab_schedules));
            tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);

            //Initializing viewPager
            viewPager = (ViewPager) findViewById(R.id.pager);

            //Creating our pager adapter
            Pager adapter = new Pager(getSupportFragmentManager(), tabLayout.getTabCount());

            //Adding adapter to pager
            viewPager.setAdapter(adapter);
            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int i, float v, int i1) {

                }

                @Override
                public void onPageSelected(int i) {
                    TabLayout.Tab tab = tabLayout.getTabAt(i);
                    if (tab != null) {
                        tab.select();
                    }
                }

                @Override
                public void onPageScrollStateChanged(int i) {

                }
            });
            //Adding onTabSelectedListener to swipe views
            tabLayout.addOnTabSelectedListener(this);
        }
        else{
            instTxt = findViewById(R.id.instTxt);
            helpImage = findViewById(R.id.helpImage);
            if(institution != null) {
                instTxt.setText(R.string.app_description);
                instTxt.setVisibility(View.VISIBLE);
                helpImage.setVisibility(View.VISIBLE);
            }
        }
    }

    private void requestReadNetworkHistoryAccess() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }

    private void requestPermissions() {
        if ((ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                + ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                + ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION))
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(SpeedometerApp.this,
                    Manifest.permission.READ_PHONE_STATE) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(SpeedometerApp.this,
                            Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(SpeedometerApp.this,
                            Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                AlertDialog.Builder builder = new AlertDialog.Builder(SpeedometerApp.this);
                builder.setTitle("Please grant the following permissions");
                builder.setMessage("Read phone state, Access location");
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        ActivityCompat.requestPermissions(SpeedometerApp.this,
                                new String[]{
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                },
                                PERMISSIONS_REQUEST_CODE
                        );
                    }
                });
                builder.setNegativeButton("Cancel", null);
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            } else{
                ActivityCompat.requestPermissions(SpeedometerApp.this,
                        new String[]{
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        PERMISSIONS_REQUEST_CODE
                );
            }
        }

        if (!hasPermissionToReadNetworkHistory()) {
            return;
        }
    }

    private boolean hasPermissionToReadNetworkHistory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        final AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
                getPackageName(),
                new AppOpsManager.OnOpChangedListener() {
                    @Override
                    @TargetApi(Build.VERSION_CODES.M)
                    public void onOpChanged(String op, String packageName) {
                        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                                android.os.Process.myUid(), getPackageName());
                        if (mode != AppOpsManager.MODE_ALLOWED) {
                            return;
                        }
                        appOps.stopWatchingMode(this);
                    }
                });
        requestReadNetworkHistoryAccess();
        return false;
    }

    private boolean hasPermissionToReadPhoneStats() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_DENIED;
    }


    private void initializeStatusBar() {
        if (this.scheduler.isPauseRequested()) {
            updateStatusBar(SpeedometerApp.this.getString(R.string.pauseMessage));
        } else if (!scheduler.hasBatteryToScheduleExperiment()) {
            updateStatusBar(SpeedometerApp.this.getString(R.string.powerThreasholdReachedMsg));
        } else {
            MeasurementTask currentTask = scheduler.getCurrentTask();
            if (currentTask != null) {
                if (currentTask.getDescription().priority == MeasurementTask.USER_PRIORITY) {
                    updateStatusBar("User task " + currentTask.getDescriptor() + " is running");
                } else {
                    updateStatusBar("System task " + currentTask.getDescriptor() + " is running");
                }
            } else {
                updateStatusBar(SpeedometerApp.this.getString(R.string.resumeMessage));
            }
        }
    }

    private void updateStatusBar(String statusMsg) {
        if (statusMsg != null) {
            statusBar.setText(statusMsg);
        }
    }

    private void updateStatsBar(String statsMsg) {
        if (statsMsg != null) {
            statsBar.setText(statsMsg);
        }
    }

    private void bindToService() {
        if (!isBindingToService && !isBound) {
            // Bind to the scheduler service if it is not bounded
            Intent intent = new Intent(this, MeasurementScheduler.class);
            bindService(intent, serviceConn, Context.BIND_AUTO_CREATE);
            isBindingToService = true;
        }
    }

    private void initPermMap(){
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        PERMISSION_SETTINGS = new EnumMap<>(Config.PERMISSION_IDS.class);
        for(Config.PERMISSION_IDS permission_id: Config.PERMISSION_IDS.values()) {
            /* Assume false when starting*/
            if(!sharedPref.contains(permission_id.name()))
                PERMISSION_SETTINGS.put(permission_id, false);
            else
                PERMISSION_SETTINGS.put(permission_id, sharedPref.getBoolean(permission_id.name(), false));
        }
    }

    @Override
    protected void onStart() {
        Logger.d("onStart called");
        // Bind to the scheduler service for only once during the lifetime of the activity
        bindToService();
        super.onStart();
        initPermMap();
        requestPermissions();
        NetworkSummaryExec.startThread();
    }

    @Override
    protected void onStop() {
        Logger.d("onStop called");
        super.onStop();
        if (isBound) {
            unbindService(serviceConn);
            isBound = false;
        }
        // Save permissions enum map to shared prefs
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        for(Config.PERMISSION_IDS permission_id: Config.PERMISSION_IDS.values()) {
            /* Assume false when starting*/
            PERMISSION_SETTINGS.put(permission_id, false);
            editor.putBoolean(permission_id.name(), PERMISSION_SETTINGS.get(permission_id));
        }
        editor.commit();
    }

    @Override
    protected void onDestroy() {
        Logger.d("onDestroy called");
        super.onDestroy();
        this.unregisterReceiver(this.receiver);
    }

    private void quitApp() {
        Logger.d("quitApp called");
        if (isBound) {
            unbindService(serviceConn);
            isBound = false;
        }
        if (this.scheduler != null) {
            Logger.d("requesting Scheduler stop");
            scheduler.requestStop();
        }
        // Disable auto start on boot.
        setStartOnBoot(false);

        this.finish();
        System.exit(0);
    }

    private void doCheckin() {
        if (scheduler != null) {
            scheduler.handleCheckin(true);
        }
    }

    /**
     * Set preference to indicate whether start on boot is enabled.
     */
    private void setStartOnBoot(boolean startOnBoot) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(getString(R.string.startOnBootPrefKey), startOnBoot);
        editor.commit();
    }

    private void restoreUserInstitution(){
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());
        institution = prefs.getString(Config.PREF_KEY_USER_INSTITUTION, null);
    }

    private void institutionDialogWrapper(){
        restoreUserInstitution();
        if(institution == null){
            showInstitutionDialog();
        }
    }

    @Override
    public void onTabSelected(TabLayout.Tab tab) {
        tab.setIcon(getSelectedTabIcon(tab));
        viewPager.setCurrentItem(tab.getPosition());
    }

    private int getSelectedTabIcon(TabLayout.Tab tab) {
        switch ((tab.getPosition())) {
            case 0:
                return R.drawable.ic_tab_user_measurement_selected;
            case 1:
                return R.drawable.ic_tab_results_icon_selected;
            case 2:
                return R.drawable.ic_tab_schedules_selected;
        }
        return -1;
    }

    @Override
    public void onTabUnselected(TabLayout.Tab tab) {
        tab.setIcon(getUnselectedIcon(tab));
    }

    private int getUnselectedIcon(TabLayout.Tab tab) {
        switch ((tab.getPosition())) {
            case 0:
                return R.drawable.ic_tab_user_measurement_unselected;
            case 1:
                return R.drawable.ic_tab_results_icon_unselected;
            case 2:
                return R.drawable.ic_tab_schedules_unselected;
        }
        return -1;
    }

    @Override
    public void onTabReselected(TabLayout.Tab tab) {
        tab.setIcon(getSelectedTabIcon(tab));
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {

    }

    public static SpeedometerApp getCurrentApp() {
        return speedometerApp;
    }

    void showInstitutionDialog(){
        DialogFragment selectUni = InstitutionDialog.newInstance();
        selectUni.show(getSupportFragmentManager(), "institution");
    }

    public void userCancelled(){
        Log.i("Institution", "No institution selected!");
        quitApp();
    }

    public void institutionSelected(String selection){
        institution = selection;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(Config.PREF_KEY_USER_INSTITUTION, selection);
        editor.apply();
        prepareUI();
        initServiceAndReceiver();
        webSocketConnector = new WebSocketConnector(getBaseContext());
        webSocketConnector.connectWebSocket();
    }

    String getSelectedAccount() {
        return "Anonymous";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        Logger.i("onRequestPermissionsResult called");
        for (int i = 0, permissionsLength = permissions.length; i < permissionsLength; i++) {
            String s = permissions[i];
            s=s.substring(s.lastIndexOf('.') + 1);
            PERMISSION_SETTINGS.put(Config.PERMISSION_IDS.valueOf(s),grantResults[i] == PackageManager.PERMISSION_GRANTED);
        }
    }

    private boolean isMeasurementEnabled(){
        return institution != null && (institution.equalsIgnoreCase("DRC")
                || institution.equalsIgnoreCase("iNethi")
                || institution.equalsIgnoreCase("Others"));
    }

    public WebSocketConnector getWebSocketConnector() {
        return webSocketConnector;
    }
}
