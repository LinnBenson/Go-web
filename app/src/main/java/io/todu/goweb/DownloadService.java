package io.todu.goweb;

import android.app.*;
import android.content.*;
import android.os.*;
import org.mozilla.geckoview.WebResponse;
import java.util.*;
import java.util.concurrent.*;

public final class DownloadService extends Service {
    private static final Map<String,WebResponse> pending=new ConcurrentHashMap<>();
    private static final ExecutorService closer=BackgroundWorkers.create(1);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private DownloadFiles files;
    private int latestStart;
    static void close(WebResponse response){if(response.body!=null)closer.execute(()->{try{response.body.close();}catch(Exception ignored){}});}
    static void cancelPending(){for(String token:new ArrayList<>(pending.keySet())){WebResponse response=pending.remove(token);if(response!=null)close(response);}}
    static boolean start(Context context,WebResponse response){
        if(!BrowserApplication.visible(context)){close(response);return false;}
        if(response.body==null)return false;
        if(pending.size()+DownloadFiles.get(context).active.get()>=8){close(response);return false;}
        response.setReadTimeoutMillis(60000);
        String token=UUID.randomUUID().toString();pending.put(token,response);
        try{context.startForegroundService(new Intent(context,DownloadService.class).putExtra("token",token));return true;}
        catch(RuntimeException error){pending.remove(token);close(response);return false;}
    }
    private static WebResponse takePending(Intent intent){String token=intent.getStringExtra("token");return token==null?null:pending.remove(token);}
    @Override public void onCreate(){
        super.onCreate();files=DownloadFiles.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads","文件下载",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,54,new Intent(this,MainActivity.class).setAction("io.todu.goweb.DOWNLOADS"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"downloads").setSmallIcon(R.drawable.ic_download).setContentTitle("Go web")
            .setContentText("正在下载文件").setOngoing(true).setContentIntent(open).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStart=startId;
        try{startForeground(54,notification());}catch(RuntimeException error){WebResponse r=intent==null?null:takePending(intent);if(r!=null)close(r);stopSelf();return START_NOT_STICKY;}
        WebResponse response=intent==null?null:takePending(intent);
        if(response!=null){if(BrowserApplication.visible(this))files.enqueue(response);else close(response);}
        ui.removeCallbacks(check);ui.postDelayed(check,500);return START_NOT_STICKY;
    }
    private final Runnable check=new Runnable(){public void run(){
        if(files.active.get()==0&&pending.isEmpty()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStart);}
        else ui.postDelayed(this,1000);
    }};
    @Override public void onTimeout(int startId,int fgsType){files.cancelAll();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){ui.removeCallbacksAndMessages(null);if(files!=null)files.cancelAll();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
