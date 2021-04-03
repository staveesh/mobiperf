package com.mobiperf;

import com.mobiperf.util.PhoneUtils;

public class NetworkSummaryThread implements Runnable{

    @Override
    public void run() {
        String deviceId = PhoneUtils.getPhoneUtils().getDeviceId();
        WebSocketConnector.getInstance().sendMessage(Config.STOMP_SERVER_LAST_SUMMARY_CHECKIN_ENDPOINT, deviceId);
    }
}
