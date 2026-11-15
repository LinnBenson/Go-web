package io.todu.goweb;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/** Version-one authenticated envelope; no plaintext PIN or verifier is stored. */
final class VaultCrypto {
    private static final byte[] AAD="GoAPP/password-book/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static byte[] derive(char[] pin,byte[] salt)throws GeneralSecurityException{
        if(pin.length!=8)throw new IllegalArgumentException("PIN length");
        for(char digit:pin)if(digit<'0'||digit>'9')throw new IllegalArgumentException("PIN digits");
        PBEKeySpec spec=new PBEKeySpec(pin,salt,210000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    static byte[] seal(SecretKey key,byte[] plain)throws GeneralSecurityException{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(AAD);
        byte[] encrypted=cipher.doFinal(plain),iv=cipher.getIV(),result=new byte[iv.length+encrypted.length];
        System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);return result;
    }
    static byte[] open(SecretKey key,byte[] envelope)throws GeneralSecurityException{
        if(envelope.length<28)throw new GeneralSecurityException("Invalid envelope");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,envelope,0,12));cipher.updateAAD(AAD);
        return cipher.doFinal(envelope,12,envelope.length-12);
    }
    static void wipe(byte[] bytes){if(bytes!=null)Arrays.fill(bytes,(byte)0);}
}
