package io.todu.goweb;

/** Avoid repeated automatic reloads when the content process keeps failing. */
final class RecoveryGate {
    private long last=-1;
    boolean allow(long now){
        if(last>=0&&now-last<60000)return false;
        last=now;return true;
    }
}
