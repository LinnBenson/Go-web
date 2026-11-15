package io.todu.goweb;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.TextUtils;
import org.mozilla.geckoview.*;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*;
import java.util.*;

/** Uses the same round-screen page shell as bookmarks and settings. */
final class FileUi {
    static final int PICK_FILE=54, STORAGE_PERMISSION=55;
    private WebResponse pendingDownload;
    private Dialog downloadConfirm;
    private final MainActivity activity;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Dialog downloads,details;
    private FilePrompt filePrompt;
    private GeckoResult<PromptResponse> fileResult;
    private boolean systemOutstanding,paused;
    private Runnable downloadRefresh;
    private final java.util.concurrent.ExecutorService uploadWorker=BackgroundWorkers.create(1);
    private final java.util.concurrent.atomic.AtomicInteger uploadGeneration=new java.util.concurrent.atomic.AtomicInteger();
    private final UploadFiles uploadFiles;
    private boolean closed;
    void pause(){paused=true;if(downloadRefresh!=null)ui.removeCallbacks(downloadRefresh);}
    void resume(){paused=false;if(downloadRefresh!=null)downloadRefresh.run();}
    FileUi(MainActivity activity){this.activity=activity;uploadFiles=new UploadFiles(activity);}
    private DownloadFiles files(){return DownloadFiles.get(activity);}
    private TextView centeredText(String value,int size){TextView view=activity.text(value,size);view.setGravity(Gravity.CENTER);return view;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void row(LinearLayout parent,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=activity.dp(7);parent.addView(v,p);}
    private Button action(String name,Runnable click){Button b=activity.button(name,click);b.setTextSize(13);b.setSingleLine(true);b.setEllipsize(TextUtils.TruncateAt.END);b.setMinimumHeight(activity.dp(42));return b;}
    private String size(long bytes){return android.text.format.Formatter.formatShortFileSize(activity,bytes);}
    private String status(DownloadFiles.Item item){return item.state+" · "+size(item.bytes)+(item.total>0?" / "+size(item.total):"");}
    void showDownloads(){
        if(downloads!=null&&downloads.isShowing())return;
        DownloadFiles store=files();Dialog dialog=new Dialog(activity);downloads=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout list=column();Map<String,Button> buttons=new LinkedHashMap<>();
        Runnable refresh=new Runnable(){public void run(){
            ui.removeCallbacks(this);
            if(paused||downloads!=dialog||!dialog.isShowing())return;
            List<DownloadFiles.Item> snapshot=store.snapshot();
            Set<String> ids=new LinkedHashSet<>();for(DownloadFiles.Item item:snapshot)ids.add(item.id);
            if(!buttons.keySet().equals(ids)||list.getChildCount()==0){
                buttons.clear();list.removeAllViews();
                if(snapshot.isEmpty())row(list,centeredText(store.ready?"暂无下载":"加载中",13));
                for(DownloadFiles.Item item:snapshot){
                    Button b=action(item.name,()->showItem(item));b.setSingleLine(false);b.setMaxLines(2);row(list,b);buttons.put(item.id,b);
                }
            }
            for(DownloadFiles.Item item:snapshot){Button b=buttons.get(item.id);String label=item.name+"\n"+status(item);if(!label.contentEquals(b.getText()))b.setText(label);}
            if(snapshot.isEmpty()&&store.ready&&list.getChildCount()>0)((TextView)list.getChildAt(0)).setText("暂无下载");
            if(store.active.get()>0||!store.ready)ui.postDelayed(this,1000);
        }};
        downloadRefresh=refresh;store.observe(refresh);
        dialog.setOnDismissListener(d->{store.unobserve(refresh);ui.removeCallbacks(refresh);if(downloads==dialog){downloads=null;downloadRefresh=null;}});
        activity.showPinnedDialog(dialog,"下载",list,activity.singleClose(dialog),false);refresh.run();
    }
    private void showItem(DownloadFiles.Item item){
        if(details!=null)details.dismiss();Dialog dialog=new Dialog(activity);details=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOnDismissListener(d->{if(details==dialog)details=null;});
        LinearLayout list=column();TextView name=centeredText(item.name,13);name.setMaxLines(3);name.setEllipsize(TextUtils.TruncateAt.END);row(list,name);
        row(list,centeredText(status(item),12));
        if(item.active())row(list,action("取消下载",()->{files().cancel(item);dialog.dismiss();}));
        else{
            if(item.state.equals("已完成")){
                row(list,action("打开",()->open(item)));
            }
            row(list,action("删除",()->{dialog.dismiss();confirmDelete(item);}));
        }
        activity.showPinnedDialog(dialog,"下载项目",list,activity.singleClose(dialog),false);
    }
    private void confirmDelete(DownloadFiles.Item item){
        Dialog dialog=new Dialog(activity);details=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOnDismissListener(d->{if(details==dialog)details=null;});
        LinearLayout body=column();row(body,centeredText("删除此文件及下载记录？",13));
        LinearLayout actions=activity.singleClose(dialog);actions.addView(activity.plainIcon(R.drawable.ic_check,"删除",()->{files().delete(item,ok->{if(!ok&&!activity.isDestroyed())activity.toast("文件未能删除");});dialog.dismiss();}));
        activity.showPinnedDialog(dialog,"删除下载",body,actions,true);
    }
    private void open(DownloadFiles.Item item){
        Uri uri=files().uri(item);Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri,item.mime);
        intent.setClipData(ClipData.newRawUri(item.name,uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{activity.startActivity(intent);}catch(RuntimeException error){activity.toast("没有可打开此文件的应用");}
    }
    GeckoResult<PromptResponse> choose(FilePrompt prompt){
        if(systemOutstanding)return GeckoResult.fromValue(prompt.dismiss());
        cancelUpload();filePrompt=prompt;fileResult=new GeckoResult<>();GeckoResult<PromptResponse> result=fileResult;
        prompt.setDelegate(new PromptInstanceDelegate(){@Override public void onPromptDismiss(BasePrompt p){if(filePrompt==p)cancelUpload();}});
        launchSystem();return result;
    }
    void offerDownload(WebResponse response){
        if(pendingDownload!=null){DownloadService.close(response);activity.toast("请先处理当前下载");return;}
        pendingDownload=response;Dialog dialog=new Dialog(activity);downloadConfirm=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=column();TextView filename=centeredText(DownloadFiles.filename(response),13);filename.setMaxLines(3);filename.setEllipsize(TextUtils.TruncateAt.END);row(body,filename);row(body,centeredText("保存到 Download",12));
        LinearLayout actions=activity.singleClose(dialog);
        actions.addView(activity.plainIcon(R.drawable.ic_check,"下载",()->{
            downloadConfirm=null;dialog.dismiss();
            if(Build.VERSION.SDK_INT<=28&&activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_PERMISSION);
            else beginDownload();
        }));
        dialog.setOnDismissListener(d->{if(downloadConfirm==dialog){downloadConfirm=null;discardDownload();}});
        activity.showPinnedDialog(dialog,"下载文件",body,actions,true);
    }
    void storageResult(boolean granted){if(granted)beginDownload();else{discardDownload();activity.toast("未获得存储权限");}}
    private void beginDownload(){WebResponse response=pendingDownload;pendingDownload=null;if(response!=null)activity.toast(DownloadService.start(activity,response)?"已开始下载":"暂时无法下载");}
    private void discardDownload(){WebResponse response=pendingDownload;pendingDownload=null;if(response!=null)DownloadService.close(response);}
    private void launchSystem(){
        if(filePrompt==null)return;
        Intent intent=new Intent(filePrompt.type==FilePrompt.Type.FOLDER?Intent.ACTION_OPEN_DOCUMENT_TREE:Intent.ACTION_OPEN_DOCUMENT);
        if(filePrompt.type!=FilePrompt.Type.FOLDER){
            intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,filePrompt.type==FilePrompt.Type.MULTIPLE);
            List<String> types=new ArrayList<>();if(filePrompt.mimeTypes!=null)for(String type:filePrompt.mimeTypes)if(type.contains("/"))types.add(type);
            if(!types.isEmpty())intent.putExtra(Intent.EXTRA_MIME_TYPES,types.toArray(new String[0]));
        }
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{systemOutstanding=true;activity.startActivityForResult(intent,PICK_FILE);}
        catch(RuntimeException error){systemOutstanding=false;activity.toast("此设备没有系统文件选择器");cancelUpload();}
    }
    void result(int result,Intent data){
        systemOutstanding=false;if(filePrompt==null)return;
        if(result!=Activity.RESULT_OK||data==null){cancelUpload();return;}
        LinkedHashSet<Uri> uris=new LinkedHashSet<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        uris.removeIf(uri->uri==null||!"content".equals(uri.getScheme()));
        if(filePrompt.type!=FilePrompt.Type.MULTIPLE&&uris.size()>1){Uri first=uris.iterator().next();uris.clear();uris.add(first);}
        if(uris.isEmpty())cancelUpload();else prepareUpload(uris.toArray(new Uri[0]));
    }
    private void prepareUpload(Uri[] uris){
        final int generation=uploadGeneration.get();final boolean folder=filePrompt.type==FilePrompt.Type.FOLDER;
        activity.toast("正在读取文件");
        uploadWorker.execute(()->{
            try{
                Uri[] local=uploadFiles.stage(uris,folder,()->generation!=uploadGeneration.get());
                ui.post(()->{if(!closed&&generation==uploadGeneration.get())finishUpload(local);});
            }catch(Exception error){
                ui.post(()->{if(!closed&&generation==uploadGeneration.get()){cancelUpload();activity.toast("无法读取所选文件");}});
            }
        });
    }
    private void finishUpload(Uri[] uris){
        FilePrompt prompt=filePrompt;GeckoResult<PromptResponse> result=fileResult;filePrompt=null;fileResult=null;
        if(prompt==null||prompt.isComplete())return;
        try{result.complete(prompt.confirm(activity.getApplicationContext(),uris));}
        catch(Exception error){if(!prompt.isComplete())result.complete(prompt.dismiss());activity.toast("无法读取所选文件");}
    }
    private void cancelUpload(){
        uploadGeneration.incrementAndGet();
        FilePrompt prompt=filePrompt;GeckoResult<PromptResponse> result=fileResult;filePrompt=null;fileResult=null;
        if(prompt!=null&&!prompt.isComplete())result.complete(prompt.dismiss());
    }
    void clearTemporary(java.util.function.Consumer<Boolean> callback){
        discardDownload();cancelUpload();
        if(downloadConfirm!=null){downloadConfirm.dismiss();downloadConfirm=null;}
        uploadWorker.execute(()->{boolean ok=uploadFiles.clear();ui.post(()->callback.accept(ok));});
    }
    void close(){closed=true;discardDownload();if(downloadConfirm!=null)downloadConfirm.dismiss();cancelUpload();if(downloads!=null)downloads.dismiss();if(details!=null)details.dismiss();ui.removeCallbacksAndMessages(null);uploadWorker.execute(uploadFiles::close);uploadWorker.shutdown();}
}
