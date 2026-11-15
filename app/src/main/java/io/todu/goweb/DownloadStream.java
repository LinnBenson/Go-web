package io.todu.goweb;

import java.io.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

final class DownloadStream {
    static void copy(InputStream input,OutputStream output,BooleanSupplier cancelled,LongConsumer progress)throws IOException{
        byte[] buffer=new byte[32768];long bytes=0;
        while(true){
            if(cancelled.getAsBoolean())throw new InterruptedIOException();
            int count=input.read(buffer);if(count<0)break;
            if(cancelled.getAsBoolean())throw new InterruptedIOException();
            if(count==0)continue;
            output.write(buffer,0,count);bytes+=count;progress.accept(bytes);
        }
        output.flush();
    }
}
