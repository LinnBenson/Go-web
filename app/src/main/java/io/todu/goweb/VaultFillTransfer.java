package io.todu.goweb;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;

/** One-shot in-process transfer; never place passwords in Intent extras or saved state. */
final class VaultFillTransfer {
    static final class Entry implements AutoCloseable {
        final String token;
        final char[] account,password;
        final long expires=SystemClock.elapsedRealtime()+30000;
        Entry(String token,String account,String password){this.token=token;this.account=account.toCharArray();this.password=password.toCharArray();}
        @Override public void close(){Arrays.fill(account,'\0');Arrays.fill(password,'\0');}
    }
    private static Entry pending;
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static final Runnable expiry=VaultFillTransfer::clear;
    static synchronized void put(String token,String account,String password){
        clear();pending=new Entry(token,account,password);handler.postDelayed(expiry,30000);
    }
    static synchronized Entry take(String token){
        Entry result=pending;pending=null;handler.removeCallbacks(expiry);
        if(result!=null && (!result.token.equals(token)||SystemClock.elapsedRealtime()>result.expires)){result.close();return null;}
        return result;
    }
    static synchronized void clear(){handler.removeCallbacks(expiry);if(pending!=null)pending.close();pending=null;}
}
