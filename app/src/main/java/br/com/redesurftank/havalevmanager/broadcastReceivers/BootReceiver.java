package br.com.redesurftank.havalevmanager.broadcastReceivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import br.com.redesurftank.havalevmanager.services.EvManagerService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "Boot completed received, starting EvManagerService...");
        Intent serviceIntent = new Intent(context, EvManagerService.class);
        context.startForegroundService(serviceIntent);
    }
}
