package com.mobiperf;

import com.mobiperf.util.PhoneUtils;

public class NetworkSummaryThread implements Runnable{

    @Override
    public void run() {
        WebSocketConnector connector = SpeedometerApp.getCurrentApp().getWebSocketConnector();
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceId();
        // If successful, a message will be received from /user/<id>/summary/checkin with latest timestamp
        connector.sendMessage(Config.STOMP_SERVER_LAST_SUMMARY_CHECKIN_ENDPOINT, deviceId);
    }
}
