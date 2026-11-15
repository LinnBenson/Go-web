package io.todu.goweb;

import android.content.Context;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import androidx.core.content.FileProvider;
import org.json.*;
import org.mozilla.geckoview.WebResponse;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-owned transfers into public Download, independent of the browser Activity. */
final class DownloadFiles {
    static final class Item {
        final String id,name,mime;
        volatile String state="等待中",destination="";
        volatile long bytes,total=-1;
        volatile boolean cancelled;
        volatile InputStream stream;
        Item(String id,String name,String mime){this.id=id;this.name=name;this.mime=mime;}
        boolean active(){return state.equals("等待中")||state.equals("下载中");}
    }
    private static DownloadFiles instance;
    static synchronized DownloadFiles get(Context context){if(instance==null)instance=new DownloadFiles(context.getApplicationContext());return instance;}
    static synchronized void cancelIfCreated(){if(instance!=null)instance.cancelAll();}
    private final File directory;
    private final Context context;
    private final AtomicFile index;
    private final ExecutorService metadata=BackgroundWorkers.create(1),workers=BackgroundWorkers.create(2),closer=BackgroundWorkers.create(1);
    private final List<Item> items=new CopyOnWriteArrayList<>();
    final AtomicInteger active=new AtomicInteger();
    volatile boolean ready;
    private volatile boolean clearing;
    private final AtomicInteger cancellationGeneration=new AtomicInteger();
    private final Set<Runnable> listeners=new CopyOnWriteArraySet<>();
    private final Handler ui=new Handler(Looper.getMainLooper());
    void observe(Runnable listener){listeners.add(listener);}
    void unobserve(Runnable listener){listeners.remove(listener);}
    private void changed(){ui.post(()->{for(Runnable listener:listeners)listener.run();});}
    private DownloadFiles(Context context){
        this.context=context;directory=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);index=new AtomicFile(new File(context.getFilesDir(),"downloads.json"));
        metadata.execute(()->{
            try{
                JSONArray saved=new JSONArray(new String(index.readFully(),java.nio.charset.StandardCharsets.UTF_8));
                for(int i=0;i<saved.length();i++){
                    JSONObject j=saved.getJSONObject(i);String id=j.getString("id");if(!id.matches("[a-f0-9-]{36}"))continue;
                    Item item=new Item(id,FileNames.safe(j.getString("name")),j.optString("mime","application/octet-stream"));
                    item.destination=j.optString("destination","");item.bytes=j.optLong("bytes");item.total=j.optLong("total",-1);item.state=j.optString("state","已中断");
                    if(item.active()){
                        boolean published=false;
                        if(Build.VERSION.SDK_INT>=29&&!item.destination.isEmpty())try(android.database.Cursor cursor=context.getContentResolver().query(Uri.parse(item.destination),new String[]{MediaStore.Downloads.IS_PENDING,MediaStore.Downloads.SIZE},null,null,null)){
                            if(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(0)==0){published=true;item.bytes=cursor.getLong(1);}
                        }catch(Exception ignored){}
                        item.state=published?"已完成":"已中断";if(!published)removeFile(item);
                    }
                    items.add(item);
                }
            }catch(FileNotFoundException ignored){}catch(Exception error){android.util.Log.w("GoApp","Download index unavailable",error);}
            ready=true;changed();
        });
    }
    List<Item> snapshot(){return new ArrayList<>(items);}
    Uri uri(Item item){
        Uri uri=Uri.parse(item.destination);
        if("file".equals(uri.getScheme()))return FileProvider.getUriForFile(context,context.getPackageName()+".files",new File(uri.getPath()));
        return uri;
    }
    private Uri createDestination(Item item)throws IOException{
        if(Build.VERSION.SDK_INT>=29){
            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,item.name);
            values.put(MediaStore.Downloads.MIME_TYPE,item.mime);values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING,1);
            Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new IOException("Storage unavailable");return uri;
        }
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Storage unavailable");
        for(int i=0;i<10000;i++){
            File target=new File(directory,i==0?item.name:i+"-"+item.name);
            if(target.createNewFile())return Uri.fromFile(target);
        }
        throw new IOException("Filename unavailable");
    }
    private boolean removeFile(Item item){
        if(item.destination.isEmpty())return true;
        try{Uri uri=Uri.parse(item.destination);
            if("file".equals(uri.getScheme())){File file=new File(uri.getPath());return !file.exists()||file.delete();}
            context.getContentResolver().delete(uri,null,null);return true;
        }catch(Exception ignored){return false;}
    }
    static String header(WebResponse response,String key){for(Map.Entry<String,String> h:response.headers.entrySet())if(h.getKey().equalsIgnoreCase(key))return h.getValue();return "";}
    static String filename(WebResponse response){return FileNames.choose(response.uri,header(response,"content-disposition"));}
    void enqueue(WebResponse response){
        if(clearing){DownloadService.close(response);return;}
        int generation=cancellationGeneration.get();
        active.incrementAndGet();
        metadata.execute(()->{
            String mime=header(response,"content-type").split(";")[0].trim();if(mime.isEmpty())mime="application/octet-stream";
            Item item=new Item(UUID.randomUUID().toString(),filename(response),mime);item.stream=response.body;
            try{if(header(response,"content-encoding").isEmpty())item.total=Long.parseLong(header(response,"content-length"));}catch(Exception ignored){}
            items.add(0,item);
            if(generation!=cancellationGeneration.get())item.cancelled=true;
            save();changed();
            workers.execute(()->transfer(item));
        });
    }
    private void transfer(Item item){
        try(InputStream input=item.stream){
            if(item.cancelled)throw new InterruptedIOException();
            if(input==null)throw new IOException("No response stream");
            Uri destination=createDestination(item);item.destination=destination.toString();
            metadata.submit(this::save).get();
            item.state="下载中";
            try(OutputStream output="file".equals(destination.getScheme())?new FileOutputStream(new File(destination.getPath())):context.getContentResolver().openOutputStream(destination,"w")){
                if(output==null)throw new IOException("Storage unavailable");
                DownloadStream.copy(input,output,()->item.cancelled,bytes->item.bytes=bytes);
            }
            if(item.cancelled)throw new InterruptedIOException();
            if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.Downloads.IS_PENDING,0);if(context.getContentResolver().update(destination,values,null,null)==0)throw new IOException("Save failed");}
            item.state="已完成";
        }catch(Exception error){item.state=item.cancelled?"已取消":"下载失败";removeFile(item);}
        finally{item.stream=null;active.decrementAndGet();metadata.execute(this::save);changed();}
    }
    void cancel(Item item){item.cancelled=true;InputStream stream=item.stream;if(stream!=null)closer.execute(()->{try{stream.close();}catch(Exception ignored){}});}
    void cancelAll(){cancellationGeneration.incrementAndGet();for(Item item:items)if(item.active())cancel(item);}
    void delete(Item item,java.util.function.Consumer<Boolean> callback){if(item.active())return;metadata.execute(()->{boolean removed=removeFile(item);if(removed){items.remove(item);save();changed();}new Handler(Looper.getMainLooper()).post(()->callback.accept(removed));});}
    void clearAll(java.util.function.Consumer<Boolean> callback){
        if(clearing){callback.accept(false);return;}
        clearing=true;cancelAll();long deadline=android.os.SystemClock.elapsedRealtime()+65000;
        Runnable wait=new Runnable(){public void run(){
            if(active.get()>0){
                if(android.os.SystemClock.elapsedRealtime()<deadline){ui.postDelayed(this,100);return;}
                clearing=false;callback.accept(false);return;
            }
            metadata.execute(()->{
                boolean success=true;
                for(Item item:new ArrayList<>(items)){if(removeFile(item))items.remove(item);else success=false;}
                boolean result=save()&&success;
                clearing=false;changed();ui.post(()->callback.accept(result));
            });
        }};ui.post(wait);
    }
    private boolean save(){
        FileOutputStream output=null;
        try{
            JSONArray array=new JSONArray();for(Item item:items){JSONObject j=new JSONObject();j.put("id",item.id);j.put("name",item.name);j.put("mime",item.mime);j.put("destination",item.destination);j.put("state",item.state);j.put("bytes",item.bytes);j.put("total",item.total);array.put(j);}
            output=index.startWrite();output.write(array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));index.finishWrite(output);return true;
        }catch(Exception error){if(output!=null)index.failWrite(output);android.util.Log.w("GoApp","Download index save failed",error);return false;}
    }
}
