package io.todu.goweb;

/** Reuse only a successfully loaded home document in this live session. */
final class HomePageReuse {
    private String configuredHome="", completedUrl="";
    private boolean loadingHome;
    void start(String home,String url){
        loadingHome=home.equals(url);
        configuredHome=home;
        completedUrl="";
    }
    void stop(String home,String current,boolean success){
        if(loadingHome&&success&&configuredHome.equals(home))completedUrl=current;
        loadingHome=false;
    }
    boolean canReuse(String home,String current){
        return !completedUrl.isEmpty()&&configuredHome.equals(home)&&completedUrl.equals(current);
    }
    void invalidate(){loadingHome=false;completedUrl="";}
}
