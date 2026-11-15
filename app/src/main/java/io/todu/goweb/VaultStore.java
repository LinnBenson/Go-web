package io.todu.goweb;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;

/** Worker-thread-only vault, protected by both the PIN and this installation's Keystore key. */
final class VaultStore {
    private static final String ALIAS="goapp.passwordbook.v1";
    private final AtomicFile file;
    private byte[] pinKey,salt;
    VaultStore(Context context){file=new AtomicFile(new File(context.getNoBackupFilesDir(),"password-book.v1"));}
    boolean exists(){return file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();}
    private SecretKey deviceKey(boolean create)throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        if(!create)throw new GeneralSecurityException("Device key unavailable");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    JSONArray create(char[] pin)throws Exception{
        if(exists())throw new IllegalStateException("Already initialized");
        salt=new byte[16];new SecureRandom().nextBytes(salt);pinKey=VaultCrypto.derive(pin,salt);
        JSONArray empty=new JSONArray();write(empty,deviceKey(true));return empty;
    }
    JSONArray unlock(char[] pin)throws Exception{
        close();byte[] outer=null,inner=null,plain=null;
        try{
            try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Vault too large");out.write(buffer,0,n);}outer=out.toByteArray();
            }
            inner=VaultCrypto.open(deviceKey(false),outer);
            if(inner.length<44)throw new GeneralSecurityException("Invalid vault");
            salt=Arrays.copyOfRange(inner,0,16);pinKey=VaultCrypto.derive(pin,salt);
            plain=VaultCrypto.open(new SecretKeySpec(pinKey,"AES"),Arrays.copyOfRange(inner,16,inner.length));
            return new JSONArray(new String(plain,java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception error){close();throw error;}
        finally{VaultCrypto.wipe(outer);VaultCrypto.wipe(inner);VaultCrypto.wipe(plain);}
    }
    void save(JSONArray records)throws Exception{
        if(pinKey==null||salt==null)throw new IllegalStateException("Locked");write(records,deviceKey(false));
    }
    private void write(JSONArray records,SecretKey device)throws Exception{
        byte[] plain=null,encrypted=null,inner=null,outer=null;FileOutputStream stream=null;
        try{
            plain=records.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(plain.length>1024*1024)throw new IOException("Vault full");
            encrypted=VaultCrypto.seal(new SecretKeySpec(pinKey,"AES"),plain);
            inner=new byte[16+encrypted.length];System.arraycopy(salt,0,inner,0,16);System.arraycopy(encrypted,0,inner,16,encrypted.length);
            outer=VaultCrypto.seal(device,inner);stream=file.startWrite();stream.write(outer);file.finishWrite(stream);stream=null;
        }finally{if(stream!=null)file.failWrite(stream);VaultCrypto.wipe(plain);VaultCrypto.wipe(encrypted);VaultCrypto.wipe(inner);VaultCrypto.wipe(outer);}
    }
    void close(){VaultCrypto.wipe(pinKey);VaultCrypto.wipe(salt);pinKey=null;salt=null;}
}
