package io.todu.goweb;

/** Monotonic grace period restarts after foreground or protected interaction. */
final class BackgroundIdle {
    private long since=-1;
    void reset(){since=-1;}
    boolean expired(long now,boolean protectedInteraction){
        if(protectedInteraction){reset();return false;}
        if(since<0)since=now;
        return now-since>=30000;
    }
}
