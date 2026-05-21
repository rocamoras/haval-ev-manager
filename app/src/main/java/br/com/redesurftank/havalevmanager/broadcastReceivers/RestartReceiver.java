package br.com.redesurftank.havalevmanager.broadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalevmanager.services.EvManagerService;

public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, EvManagerService.class);
        context.startForegroundService(serviceIntent);
    }
}
