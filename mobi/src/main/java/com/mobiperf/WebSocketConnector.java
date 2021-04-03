package com.mobiperf;

import android.content.Context;
import android.util.Log;

import com.mobiperf.util.MeasurementJsonConvertor;
import com.mobiperf.util.PhoneUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.Vector;

import io.reactivex.CompletableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import ua.naiksoftware.stomp.Stomp;
import ua.naiksoftware.stomp.StompClient;
import ua.naiksoftware.stomp.dto.LifecycleEvent;
import ua.naiksoftware.stomp.dto.StompHeader;

public class WebSocketConnector {

    private static final String TAG = "WebSocketConnector";

    private static MeasurementScheduler scheduler;
    private static Context context;
    private StompClient mStompClient;
    private CompositeDisposable compositeDisposable;
    private static WebSocketConnector instance;

    private WebSocketConnector() {
    }

    public static WebSocketConnector getInstance() {
        if (instance == null) {
            instance = new WebSocketConnector();
        }
        return instance;
    }

    public static synchronized void setContext(Context newContext){
        assert newContext != null;
        assert context == null || context == newContext;
        context = newContext;
    }

    public static synchronized void setScheduler(MeasurementScheduler schedulerInstance){
        assert scheduler == null;
        scheduler = schedulerInstance;
    }

    private List<Disposable> getSubscriptions(){
        return new ArrayList<Disposable>(){{
            add(subscribeToNewJobs());
            add(subscribeToMostRecentSummaryTimestamp());
        }};
    }

    private Disposable subscribeToNewJobs(){
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceId();
        return subscribeToTopic(String.format(com.mobiperf.Config.STOMP_SERVER_TASKS_ENDPOINT, deviceId), result -> {
            Vector<MeasurementTask> tasksFromServer = new Vector<>();
            JSONArray jsonArray = null;
            try {
                jsonArray = new JSONArray(result.getPayload());
                for (int i = 0; i < jsonArray.length(); i++) {
                    Logger.d("Parsing index " + i);
                    JSONObject json = jsonArray.optJSONObject(i);
                    Logger.d("Value is " + json);
                    if (json != null && MeasurementTask.getMeasurementTypes().contains(json.get("type"))) {
                        try {
                            MeasurementTask task = MeasurementJsonConvertor.makeMeasurementTaskFromJson(json, context);
                            Logger.i(MeasurementJsonConvertor
                                    .toJsonString(task.measurementDesc));
                            tasksFromServer.add(task);
                        } catch (IllegalArgumentException e) {
                            Logger.w("Could not create task from JSON: " + e);
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Invalid JSON received from server");
            }
            scheduler.updateSchedule(tasksFromServer, false);
            scheduler.handleMeasurement();
        });
    }

    private Disposable subscribeToMostRecentSummaryTimestamp(){
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceId();
        return subscribeToTopic(String.format(com.mobiperf.Config.STOMP_SERVER_SUMMARY_CHECKIN_ENDPOINT, deviceId), result -> {
            long endTime = System.currentTimeMillis();
            long startTime = endTime-(24*3600*1000); //minus 24 hrs;
            String timestamp = result.getPayload();
            if(timestamp != null){
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                try {
                    startTime = df.parse(timestamp).getTime();
                } catch (ParseException e) {
                    Log.e(TAG, "Invalid time returned from server");
                }
            }
            NetworkSummaryCollector collector = new NetworkSummaryCollector();
            String summary = collector.collectSummary(deviceId, startTime, endTime);
            if(summary != null){
                sendMessage(com.mobiperf.Config.STOMP_SERVER_SUMMARY_REPORT_ENDPOINT, summary);
            }
        });
    }

    public void connectWebSocket(String target) {
        if(target == null)
            return;
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceId();
        mStompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, target);
        List<StompHeader> headers = new ArrayList<StompHeader>() {{
            add(new StompHeader("deviceId", deviceId));
        }};
        resetSubscriptions();

        Disposable dispLifecycle = mStompClient.lifecycle()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(lifecycleEvent -> {
                    switch (lifecycleEvent.getType()) {
                        case OPENED:
                            Log.d(TAG, "Stomp connection opened");
                            break;
                        case ERROR:
                            Log.e(TAG, "Stomp connection error", lifecycleEvent.getException());
                            Log.d(TAG, "Stomp connection error");
                            break;
                        case CLOSED:
                            Log.d(TAG, "Stomp connection closed");
                            resetSubscriptions();
                            break;
                        case FAILED_SERVER_HEARTBEAT:
                            Log.d(TAG, "Stomp failed server heartbeat");
                            break;
                    }
                });

        compositeDisposable.add(dispLifecycle);
        List<Disposable> subscriptions = getSubscriptions();
        for(Disposable subscription : subscriptions)
            compositeDisposable.add(subscription);
        mStompClient.connect(headers);
    }

    public void sendMessage(String endpoint, String content) {
        compositeDisposable.add(mStompClient.send(endpoint, content)
                .compose(applySchedulers())
                .subscribe(
                        () -> Log.d(TAG, String.format("Message sent successfully to %s", endpoint)),
                        (throwable) -> Log.d(TAG, String.format("Error sending message to %s", endpoint, throwable))
                ));
    }

    protected CompletableTransformer applySchedulers() {
        return upstream -> upstream
                .unsubscribeOn(Schedulers.newThread())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void resetSubscriptions() {
        if (compositeDisposable != null) {
            compositeDisposable.dispose();
        }
        compositeDisposable = new CompositeDisposable();
    }

    public void disconnect() {
        mStompClient.disconnect();
    }

    public boolean isConnected() {
        return mStompClient.isConnected();
    }

    private Disposable subscribeToTopic(String endpoint, SubscriptionCallbackInterface callback){
        return mStompClient.topic(endpoint)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(callback::onSubscriptionResult, throwable -> {
                    Log.e(TAG, "Error on subscribe topic", throwable);
                });
    }

}
