package io.todu.goweb;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** SAF grants authorize streams, not the public filesystem paths Gecko resolves. */
final class UploadFiles {
    private static final String PROCESS=UUID.randomUUID().toString();
    private static boolean cleaned;
    private final Context context;
    private final File root;
    UploadFiles(Context context){this.context=context.getApplicationContext();root=new File(context.getCacheDir(),"uploads/"+PROCESS+"/"+UUID.randomUUID());}
    private synchronized void prepare()throws IOException {
        synchronized(UploadFiles.class){
            if(!cleaned){File[] old=root.getParentFile().getParentFile().listFiles();if(old!=null)for(File f:old)if(!f.getName().equals(PROCESS))remove(f);cleaned=true;}
        }
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Upload storage unavailable");
    }
    Uri[] stage(Uri[] input,boolean folder,BooleanSupplier cancelled)throws IOException {
        prepare();File batch=new File(root,UUID.randomUUID().toString());if(!batch.mkdirs())throw new IOException("Upload storage unavailable");
        try{
            List<Uri> result=new ArrayList<>();
            for(Uri uri:input){
                check(cancelled);
                if(folder){
                    String id=DocumentsContract.getTreeDocumentId(uri);
                    Uri document=DocumentsContract.buildDocumentUriUsingTree(uri,id);
                    File target=new File(batch,name(document));if(!target.mkdir())throw new IOException("Folder unavailable");
                    copyTree(uri,id,target,cancelled,0);result.add(Uri.fromFile(target));
                }else{
                    File slot=new File(batch,UUID.randomUUID().toString());if(!slot.mkdir())throw new IOException("Upload storage unavailable");
                    File target=new File(slot,name(uri));copy(uri,target,cancelled);result.add(Uri.fromFile(target));
                }
            }
            return result.toArray(new Uri[0]);
        }catch(Exception error){remove(batch);if(error instanceof IOException)throw (IOException)error;throw new IOException("Unable to read selected file",error);}
    }
    private String name(Uri uri){
        try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){String name=c.getString(0);if(name!=null&&!name.isEmpty())return FileNames.safe(name);}
        }catch(RuntimeException ignored){}
        String type=context.getContentResolver().getType(uri);
        String extension=type==null?null:android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        return "upload"+(extension==null?"":"."+extension);
    }
    private void copy(Uri uri,File target,BooleanSupplier cancelled)throws IOException {
        try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){
            if(in==null)throw new IOException("File unavailable");
            DownloadStream.copy(in,out,cancelled,n->{});
        }
    }
    private void copyTree(Uri tree,String id,File target,BooleanSupplier cancelled,int depth)throws IOException {
        check(cancelled);if(depth>64)throw new IOException("Folder nesting limit");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        String[] fields={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
        try(Cursor c=context.getContentResolver().query(children,fields,null,null,null)){
            if(c==null)throw new IOException("Folder unavailable");
            while(c.moveToNext()){
                check(cancelled);String childId=c.getString(0);File child=new File(target,FileNames.safe(c.getString(1)));
                if(child.exists())throw new IOException("Duplicate filename");
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))){if(!child.mkdir())throw new IOException("Folder unavailable");copyTree(tree,childId,child,cancelled,depth+1);}
                else copy(DocumentsContract.buildDocumentUriUsingTree(tree,childId),child,cancelled);
            }
        }
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException {if(cancelled.getAsBoolean())throw new InterruptedIOException();}
    void close(){remove(root);}
    boolean clear(){return remove(root.getParentFile().getParentFile());}
    private static boolean remove(File file){boolean success=true;File[] children=file.listFiles();if(children!=null)for(File child:children)success=remove(child)&&success;return (!file.exists()||file.delete())&&success;}
}
