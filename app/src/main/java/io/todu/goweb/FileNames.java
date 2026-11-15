package io.todu.goweb;

import java.net.*;
import java.util.regex.*;

final class FileNames {
    static String choose(String url,String disposition){
        String name="";
        Matcher extended=Pattern.compile("(?i)(?:^|;)\\s*filename\\*=UTF-8''([^;]+)").matcher(disposition);
        if(extended.find())try{name=URLDecoder.decode(extended.group(1).trim().replace("+","%2B"),"UTF-8");}catch(Exception ignored){}
        if(name.isEmpty()){
            Matcher simple=Pattern.compile("(?i)(?:^|;)\\s*filename=\\s*(?:\"([^\"]*)\"|([^;]*))").matcher(disposition);
            if(simple.find())name=simple.group(1)!=null?simple.group(1):simple.group(2).trim();
        }
        if(name.isEmpty())try{String path=new URI(url).getPath();if(path!=null)name=path.substring(path.lastIndexOf('/')+1);}catch(Exception ignored){}
        return safe(name);
    }
    static String safe(String name){
        name=name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_").replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]","").trim();
        if(name.isEmpty()||name.equals(".")||name.equals(".."))name="下载文件";
        while(name.length()>100||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180)name=name.substring(0,name.offsetByCodePoints(name.length(),-1));
        return name;
    }
}
