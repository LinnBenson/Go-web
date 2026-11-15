package io.todu.goweb;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.*;
import android.widget.ImageView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Small bounded icon bitmaps; disk access is never on the UI thread. */
final class BookmarkIcons {
    private final File directory;
    private final ExecutorService disk=BackgroundWorkers.create(1);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<String,Bitmap> memory=new LruCache<>(256*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private final Map<String,List<WeakReference<ImageView>>> views=new HashMap<>();
    private final Set<String> failed=new HashSet<>();
    private final Set<String> requested=new HashSet<>();
    private final BiConsumer<String,String> request;
    private volatile int generation;
    private volatile boolean closed;
    BookmarkIcons(Context context,BiConsumer<String,String> request){directory=new File(context.getCacheDir(),"bookmark-icons");this.request=request;}
    private File file(String url)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format(Locale.ROOT,"%02x",b&255));return new File(directory,name+".png");
    }
    void load(String url,String icon,ImageView view){
        if(closed)return;
        views.computeIfAbsent(url,key->new ArrayList<>()).add(new WeakReference<>(view));
        Bitmap cached=memory.get(url);if(cached!=null){view.setImageBitmap(cached);return;}
        if(failed.contains(url)||!requested.add(url))return;
        int version=generation;
        disk.execute(()->{
            if(closed||generation!=version)return;
            Bitmap bitmap=null;try{File f=file(url);if(f.length()>0&&f.length()<=65536){
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(f.toString(),bounds);
                if(bounds.outWidth==64&&bounds.outHeight==64)bitmap=BitmapFactory.decodeFile(f.toString());
            }}catch(Exception ignored){}
            Bitmap result=bitmap;
            main.post(()->{if(closed||generation!=version)return;if(result!=null)show(url,result);else request.accept(url,icon);});
        });
    }
    void accept(String url,String data){
        if(closed||!requested.contains(url)||!data.startsWith("data:image/png;base64,")||data.length()>90000)return;
        int version=generation;
        disk.execute(()->{
            if(closed||generation!=version)return;
            try{
                byte[] bytes=android.util.Base64.decode(data.substring(data.indexOf(',')+1),android.util.Base64.DEFAULT);
                BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);
                if(size.outWidth!=64||size.outHeight!=64)return;
                Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)return;
                directory.mkdirs();try(FileOutputStream out=new FileOutputStream(file(url))){out.write(bytes);}
                File[] saved=directory.listFiles();if(saved!=null&&saved.length>256){Arrays.sort(saved,Comparator.comparingLong(File::lastModified));for(int i=0;i<saved.length-256;i++)saved[i].delete();}
                main.post(()->{if(!closed&&generation==version)show(url,bitmap);});
            }catch(Exception ignored){}
        });
    }
    private void show(String url,Bitmap bitmap){
        requested.remove(url);
        memory.put(url,bitmap);
        List<WeakReference<ImageView>> targets=views.get(url);if(targets==null)return;
        targets.removeIf(ref->{ImageView view=ref.get();if(view==null)return true;view.setImageBitmap(bitmap);return false;});
    }
    int epoch(){return generation;}
    void failed(String url){requested.remove(url);failed.add(url);}
    void releaseView(ImageView view){
        for(List<WeakReference<ImageView>> refs:views.values())refs.removeIf(ref->ref.get()==null||ref.get()==view);
        views.entrySet().removeIf(entry->entry.getValue().isEmpty());
        view.setImageDrawable(null);
    }
    void trimMemory(){memory.evictAll();}
    void releaseViews(){generation++;views.clear();requested.clear();failed.clear();}
    void clear(){
        generation++;memory.evictAll();requested.clear();failed.clear();
        for(List<WeakReference<ImageView>> targets:views.values())for(WeakReference<ImageView> ref:targets){ImageView v=ref.get();if(v!=null)v.setImageDrawable(null);}
        disk.execute(()->{File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();});
    }
    void close(){closed=true;generation++;views.clear();memory.evictAll();main.removeCallbacksAndMessages(null);disk.shutdown();}
}
