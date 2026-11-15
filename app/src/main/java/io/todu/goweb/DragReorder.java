package io.todu.goweb;

import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** Stable layout slots during a drag; only translations animate until release. */
final class DragReorder {
    private final LinearLayout list;
    private final List<View> original,order;
    private final Consumer<List<View>> commit;
    private final BooleanSupplier enabled;
    private View active;
    private ScrollView scroll;
    private float rawY,grab,initialY;
    private boolean moved,settling;
    private int[] slots;
    private final int[] location=new int[2];
    private final Runnable tick=new Runnable(){public void run(){
        if(active==null||settling)return;
        if(scroll!=null&&moved){
            scroll.getLocationOnScreen(location);
            float edge=48*list.getResources().getDisplayMetrics().density;
            float delta=rawY<location[1]+edge?rawY-location[1]-edge:rawY>location[1]+scroll.getHeight()-edge?rawY-location[1]-scroll.getHeight()+edge:0;
            if(delta!=0){scroll.scrollBy(0,Math.round(Math.max(-10,Math.min(10,delta*.12f))));update();}
        }
        list.postOnAnimation(this);
    }};
    DragReorder(LinearLayout list,List<View> rows,BooleanSupplier enabled,Consumer<List<View>> commit){
        this.list=list;original=new ArrayList<>(rows);order=new ArrayList<>(rows);this.enabled=enabled;this.commit=commit;

    }
    private final View.OnAttachStateChangeListener lifecycle=new View.OnAttachStateChangeListener(){
        public void onViewAttachedToWindow(View v){}
        public void onViewDetachedFromWindow(View v){cancel();}
    };
    void bind(View row,Runnable options){
        row.setOnLongClickListener(v->{if(!enabled.getAsBoolean()||active!=null||settling)return true;start(row);return true;});
        row.setOnTouchListener((v,e)->{
            if(active==null){if(settling)return true;if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE)rawY=e.getRawY();return false;}
            if(v!=active)return true;
            if(e.getPointerCount()>1||e.getActionMasked()==MotionEvent.ACTION_CANCEL){cancel();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){rawY=e.getRawY();update();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){finish(options);return true;}
            return true;
        });
    }
    private void start(View row){
        list.addOnAttachStateChangeListener(lifecycle);
        active=row;slots=new int[original.size()];for(int i=0;i<slots.length;i++)slots[i]=original.get(i).getTop();
        row.getLocationOnScreen(location);grab=rawY-location[1];initialY=rawY;moved=false;
        ViewParent parent=list.getParent();while(parent!=null){if(parent instanceof ScrollView){scroll=(ScrollView)parent;break;}parent=parent.getParent();}
        list.getParent().requestDisallowInterceptTouchEvent(true);
        row.setPressed(false);row.animate().cancel();row.setTranslationZ(12*list.getResources().getDisplayMetrics().density);
        row.animate().scaleX(1f).scaleY(1f).alpha(.94f).setDuration(140).start();
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);list.postOnAnimation(tick);
    }
    private void update(){
        if(active==null)return;
        moved|=Math.abs(rawY-initialY)>ViewConfiguration.get(list.getContext()).getScaledTouchSlop();
        list.getLocationOnScreen(location);
        float top=Math.max(slots[0],Math.min(slots[slots.length-1],rawY-location[1]-grab));
        active.setTranslationY(top-active.getTop());
        int from=order.indexOf(active),to=targetSlot(slots,from,top);
        if(to!=from){order.remove(from);order.add(to,active);
            for(int i=0;i<order.size();i++){View v=order.get(i);if(v!=active)v.animate().translationY(slots[i]-v.getTop()).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
        }
    }
    static int targetSlot(int[] slots,int from,float top){
        int to=from;
        // Require movement past a dead band in both directions to avoid boundary jitter.
        while(to+1<slots.length&&top>slots[to]+(slots[to+1]-slots[to])*.62f)to++;
        while(to>0&&top<slots[to]-(slots[to]-slots[to-1])*.62f)to--;
        return to;
    }
    private void finish(Runnable options){
        list.removeCallbacks(tick);list.getParent().requestDisallowInterceptTouchEvent(false);settling=true;
        View row=active;boolean changed=!order.equals(original),showOptions=!moved;
        row.animate().cancel();row.animate().translationY(slots[order.indexOf(row)]-row.getTop()).scaleX(1).scaleY(1).alpha(1).setDuration(180)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(()->{
                for(View v:original){v.animate().cancel();v.setTranslationY(0);v.setTranslationZ(0);v.setScaleX(1);v.setScaleY(1);v.setAlpha(1);}
                if(changed){for(View v:original)list.removeView(v);for(View v:order)list.addView(v);}
                list.removeOnAttachStateChangeListener(lifecycle);active=null;settling=false;
                if(changed)commit.accept(new ArrayList<>(order));else if(showOptions&&enabled.getAsBoolean())options.run();
            }).start();
    }
    private void cancel(){
        list.removeCallbacks(tick);list.removeOnAttachStateChangeListener(lifecycle);
        for(View v:original){v.animate().cancel();v.animate().withEndAction(null);v.setTranslationY(0);v.setTranslationZ(0);v.setScaleX(1);v.setScaleY(1);v.setAlpha(1);v.setPressed(false);}
        order.clear();order.addAll(original);active=null;settling=false;
        if(list.getParent()!=null)list.getParent().requestDisallowInterceptTouchEvent(false);
    }
}
