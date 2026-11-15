package io.todu.goweb;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Decode only the public goweb://open contract; never execute an incoming scheme. */
final class ExternalLink {
    static String parse(String value){
        if(value==null||value.length()>32768)return null;
        try{
            URI link=new URI(value);
            if(!"goweb".equals(link.getScheme())||!"open".equals(link.getRawAuthority())||
               !(link.getRawPath().isEmpty()||"/".equals(link.getRawPath()))||link.getRawFragment()!=null)return null;
            String query=link.getRawQuery(),target=null;
            if(query==null)return null;
            for(String part:query.split("&")){
                int split=part.indexOf('=');
                if(split<0||!"url".equals(part.substring(0,split))||target!=null)return null;
                target=URLDecoder.decode(part.substring(split+1),StandardCharsets.UTF_8.name());
            }
            if(target==null||target.isEmpty())return null;
            for(int i=0;i<target.length();i++)if(Character.isISOControl(target.charAt(i)))return null;
            URI url=new URI(target);
            if(!"https".equalsIgnoreCase(url.getScheme())&&!"http".equalsIgnoreCase(url.getScheme()))return null;
            if(url.getHost()==null||url.getHost().isEmpty()||url.getRawUserInfo()!=null)return null;
            return target;
        }catch(Exception invalid){return null;}
    }
    private ExternalLink(){}
}
