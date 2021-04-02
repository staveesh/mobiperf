package com.mobiperf;

import ua.naiksoftware.stomp.dto.StompMessage;

public interface SubscriptionCallbackInterface {
    void onSubscriptionResult(StompMessage result);
}
