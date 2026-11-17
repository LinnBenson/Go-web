package io.todu.goweb;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.util.*;

/** Browser work exists only for a user-visible session, never for a boot receiver/job. */
public final class BrowserApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<Activity> visible=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Activity> activities=Collections.newSetFromMap(new IdentityHashMap<>());
    private final BackgroundIdle idle=new BackgroundIdle();
    private boolean used,backgroundCancelled,explicitExit;
    static boolean visible(android.content.Context context){return !((BrowserApplication)context.getApplicationContext()).visible.isEmpty();}
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    private boolean interaction(){for(Activity a:activities)if(a instanceof MainActivity&&((MainActivity)a).protectedInteraction())return true;return false;}
    private void updateImportance(boolean keepAlive){
        for(Activity a:activities)if(a instanceof MainActivity)((MainActivity)a).updateBrowserImportance(keepAlive);
    }
    private boolean foreground(){
        if(!visible.isEmpty())return true;
        for(Activity a:activities)if(!a.isFinishing()&&!a.isDestroyed()&&a.hasWindowFocus())return true;
        return false;
    }
    void exitBrowser(){
        explicitExit=true;
        handler.removeCallbacks(background);
        DownloadService.cancelPending();DownloadFiles.cancelIfCreated();
        stopService(new Intent(this,DownloadService.class));
        for(Activity a:new ArrayList<>(activities))if(!a.isFinishing())a.finishAndRemoveTask();
        handler.removeCallbacks(releaseProcess);handler.postDelayed(releaseProcess,1000);
    }
    private void foregroundEntered(){
        explicitExit=false;
        backgroundCancelled=false;idle.reset();handler.removeCallbacks(background);handler.removeCallbacks(releaseProcess);
        updateImportance(true);
    }
    private final Runnable background=new Runnable(){public void run(){
        if(foreground()||!used){foregroundEntered();return;}
        boolean protectedWork=interaction();
        updateImportance(protectedWork);
        if(!backgroundCancelled){backgroundCancelled=true;DownloadService.cancelPending();DownloadFiles.cancelIfCreated();stopService(new Intent(BrowserApplication.this,DownloadService.class));}
        if(!idle.expired(SystemClock.elapsedRealtime(),protectedWork)){handler.postDelayed(this,1000);return;}
        // Finish first so sessions, encrypted records and temporary uploads release normally.
        for(Activity a:new ArrayList<>(activities))if(!a.isFinishing())a.finishAndRemoveTask();
        handler.postDelayed(releaseProcess,1000);
    }};
    private final Runnable releaseProcess=()->{
        if(foreground()||interaction())return;
        // GeckoRuntime cannot be recreated after shutdown in the same process.
        // End this user session; START_NOT_STICKY prevents a download restart.
        android.os.Process.killProcess(android.os.Process.myPid());
    };
    // The icon picker is not a browser session; never schedule browser cleanup in :shortcut.
    @Override public void onActivityCreated(Activity a,Bundle state){if(a instanceof ComplicationConfigActivity)return;activities.add(a);used=true;}
    @Override public void onActivityStarted(Activity a){if(a instanceof ComplicationConfigActivity)return;visible.add(a);foregroundEntered();}
    @Override public void onActivityStopped(Activity a){if(a instanceof ComplicationConfigActivity)return;visible.remove(a);if(visible.isEmpty()){handler.removeCallbacks(background);if(explicitExit){handler.removeCallbacks(releaseProcess);handler.postDelayed(releaseProcess,1000);}else handler.postDelayed(background,700);}}
    @Override public void onActivityDestroyed(Activity a){activities.remove(a);visible.remove(a);}
    @Override public void onActivityResumed(Activity a){if(a instanceof ComplicationConfigActivity)return;visible.add(a);foregroundEntered();}
    @Override public void onActivityPaused(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle out){}
}
