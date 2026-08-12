package com.openbubbles.findmy.bridge;

import com.openbubbles.findmy.bridge.IFindMyBridgeCallback;

interface IFindMyBridge {
    void getStatus(String token, IFindMyBridgeCallback callback);
    void refresh(String token, IFindMyBridgeCallback callback);
    void revoke(String token);
}
