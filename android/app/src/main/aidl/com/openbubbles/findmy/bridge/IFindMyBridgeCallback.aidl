package com.openbubbles.findmy.bridge;

import android.os.Bundle;

oneway interface IFindMyBridgeCallback {
    void onResult(in Bundle result);
}
