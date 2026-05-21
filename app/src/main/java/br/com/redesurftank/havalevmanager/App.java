package br.com.redesurftank.havalevmanager;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalevmanager.services.EvManagerService;

public class App extends Application {

    private static Application sApplication;
    private static Context deviceProtectedContext;

    public static Application getApplication() {
        return sApplication;
    }

    public static Context getContext() {
        return getApplication().getApplicationContext();
    }

    public synchronized static Context getDeviceProtectedContext() {
        if (deviceProtectedContext == null) {
            deviceProtectedContext = getApplication().createDeviceProtectedStorageContext();
        }
        return deviceProtectedContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApplication = this;
        Intent serviceIntent = new Intent(getContext(), EvManagerService.class);
        getContext().startForegroundService(serviceIntent);
    }
}
