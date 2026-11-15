package io.todu.goweb;

import android.app.Activity;
import android.os.*;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Local password book. Filling requires a fresh PIN unlock and explicit record selection. */
public final class VaultActivity extends Activity {
    private static final int BG=0xff11181b,FG=0xffeff6f4,TILE=0xff213034;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private VaultStore store;
    private SharedPreferences gate;
    private JSONArray records;
    private char[] confirmation;
    private final char[] digits=new char[8];
    private int entered;
    private boolean setup,busy,closed,unlocked;
    private String screen="pin";
    private boolean fillMode;
    private boolean replaceWithoutAnimation;
    private String fillToken,fillSite;
    private FrameLayout pageHost;
    private ScrollView pageScroll;
    private TextView prompt;
    private View dots;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        fillToken=getIntent().getStringExtra("fillToken");fillSite=getIntent().getStringExtra("fillSite");fillMode=fillToken!=null&&!fillToken.isEmpty();
        gate=getSharedPreferences("vault-gate",MODE_PRIVATE);store=new VaultStore(this);setup=!store.exists();showPin();
    }
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(FG);v.setTextSize(size);v.setGravity(Gravity.CENTER);return v;}
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextColor(FG);b.setTextSize(13);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(16),0,dp(16),0);
        GradientDrawable fill=new GradientDrawable();fill.setColor(TILE);fill.setCornerRadius(dp(22));
        b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x407de8ca),fill,null));
        b.setOnClickListener(v->{if(!busy&&!closed)action.run();});b.setSaveEnabled(false);return b;
    }
    private void install(View view){
        view.setBackgroundColor(BG);view.setSaveEnabled(false);view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if(pageHost==null){pageHost=new FrameLayout(this);pageHost.setBackgroundColor(BG);pageHost.addView(view);setContentView(pageHost);return;}
        if(replaceWithoutAnimation){replaceWithoutAnimation=false;pageHost.removeAllViews();pageHost.addView(view);return;}
        View previous=pageHost.getChildCount()==0?null:pageHost.getChildAt(pageHost.getChildCount()-1);
        if(previous!=null){pageHost.removeAllViews();pageHost.addView(previous);previous.clearAnimation();previous.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this,R.anim.page_exit));}
        pageHost.addView(view);view.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this,R.anim.page_enter));
        if(previous!=null)ui.postDelayed(()->{if(pageHost!=null)pageHost.removeView(previous);},180);
    }
    private void showPin(){
        screen="pin";prompt=text(setup?"设置":"解锁",16);
        prompt.setSingleLine(true);prompt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dots=new View(this){
            private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas){
                super.onDraw(canvas);
                float spacing=getWidth()*.105f,radius=Math.min(dp(5),spacing*.36f);
                paint.setColor(FG);paint.setStrokeWidth(dp(1));
                for(int i=0;i<8;i++){
                    paint.setStyle(i<entered?android.graphics.Paint.Style.FILL:android.graphics.Paint.Style.STROKE);
                    canvas.drawCircle(getWidth()/2f+(i-3.5f)*spacing,getHeight()/2f,radius,paint);
                }
            }
        };
        dots.setContentDescription("已输入 0 位");
        LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.VERTICAL);center.setGravity(Gravity.CENTER);
        center.addView(prompt,new LinearLayout.LayoutParams(-1,0,1));center.addView(dots,new LinearLayout.LayoutParams(-1,0,1));
        ImageButton exit=plainIcon(R.drawable.ic_close,"关闭密码本",this::finish);
        FrameLayout exitArea=new FrameLayout(this);
        exitArea.addView(exit,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        center.addView(exitArea,new LinearLayout.LayoutParams(-1,0,1));
        ViewGroup ring=new ViewGroup(this){
            @Override protected void onMeasure(int ws,int hs){
                int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs),side=Math.min(w,h);setMeasuredDimension(w,h);
                center.measure(MeasureSpec.makeMeasureSpec(Math.round(side*.52f),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(side*.52f),MeasureSpec.EXACTLY));
                int key=Math.min(dp(44),Math.round(side*.20f));
                for(int i=1;i<getChildCount();i++)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(key,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(key,MeasureSpec.EXACTLY));
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
                float cx=getWidth()/2f,cy=getHeight()/2f,radius=Math.min(getWidth(),getHeight())*.38f;
                center.layout(Math.round(cx-center.getMeasuredWidth()/2f),Math.round(cy-center.getMeasuredHeight()/2f),Math.round(cx+center.getMeasuredWidth()/2f),Math.round(cy+center.getMeasuredHeight()/2f));
                for(int i=1;i<getChildCount();i++){
                    View key=getChildAt(i);double angle=-Math.PI/2+(i-1)*Math.PI*2/11;
                    int x=Math.round(cx+(float)Math.cos(angle)*radius-key.getMeasuredWidth()/2f),y=Math.round(cy+(float)Math.sin(angle)*radius-key.getMeasuredHeight()/2f);
                    key.layout(x,y,x+key.getMeasuredWidth(),y+key.getMeasuredHeight());
                }
            }
        };
        ring.addView(center);
        for(int i=0;i<11;i++){
            final int digit=(i+1)%10;
            Button key=button(i==10?"⌫":String.valueOf(digit),()->{});
            GradientDrawable keyFill=new GradientDrawable();keyFill.setColor(TILE);keyFill.setCornerRadius(dp(11));
            key.setBackground(new InsetDrawable(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x407de8ca),keyFill,null),dp(6)));
            final boolean back=i==10;
            key.setOnClickListener(v->{if(busy||closed)return;if(back){if(entered>0)digits[--entered]=0;updateDots();}else inputDigit(digit);});
            key.setPadding(0,0,0,0);key.setTextSize(16);key.setContentDescription(back?"退格":String.valueOf(digit));ring.addView(key);
        }
        install(ring);
    }
    private void updateDots(){dots.invalidate();dots.setContentDescription("已输入 "+entered+" 位");}
    private void inputDigit(int digit){
        long remaining=gate.getLong("until",0)-System.currentTimeMillis();
        if(remaining>0){prompt.setText("稍后重试");prompt.setContentDescription("请在 "+((remaining+999)/1000)+" 秒后重试");return;}
        if(entered>=8)return;prompt.setText(setup?(confirmation==null?"设置":"确认"):"解锁");prompt.setContentDescription(null);digits[entered++]=(char)('0'+digit);updateDots();if(entered<8)return;
        char[] attempt=Arrays.copyOf(digits,8);Arrays.fill(digits,'\0');entered=0;
        if(setup&&confirmation==null){confirmation=attempt;prompt.setText("确认");updateDots();return;}
        if(setup&&!Arrays.equals(attempt,confirmation)){
            Arrays.fill(attempt,'\0');Arrays.fill(confirmation,'\0');confirmation=null;prompt.setText("错误");updateDots();return;
        }
        if(confirmation!=null){Arrays.fill(confirmation,'\0');confirmation=null;}
        busy=true;prompt.setText(setup?"设置中":"解锁中");
        worker.execute(()->{
            try{
                JSONArray loaded=setup?store.create(attempt):store.unlock(attempt);
                ui.post(()->{if(closed)return;busy=false;records=loaded;unlocked=true;gate.edit().clear().apply();showList();});
            }catch(Exception error){
                store.close();ui.post(()->{
                    if(closed)return;busy=false;
                    if(setup){setup=!store.exists();prompt.setText("错误");}
                    else{
                        int failures=gate.getInt("failures",0)+1;
                        long wait=failures<5?0:Math.min(900000L,30000L*(1L<<Math.min(5,failures-5)));
                        gate.edit().putInt("failures",failures).putLong("until",System.currentTimeMillis()+wait).apply();
                        prompt.setText(wait>0?"稍后重试":"错误");
                    }
                    updateDots();
                });
            }finally{Arrays.fill(attempt,'\0');}
        });
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void addRow(LinearLayout list,View v,int height){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(height));lp.topMargin=dp(8);list.addView(v,lp);}
    private ImageButton plainIcon(int icon,String label,Runnable action){
        ImageButton button=new ImageButton(this);button.setImageResource(icon);button.setColorFilter(FG);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);button.setPadding(dp(11),dp(11),dp(11),dp(11));
        button.setContentDescription(label);button.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x407de8ca),null,null));
        button.setOnClickListener(v->{if(!busy&&!closed)action.run();});return button;
    }
    private void page(String heading,LinearLayout content,Runnable back,Runnable save){
        page(heading,content,back,save,R.drawable.ic_check,"保存");
    }
    private void page(String heading,LinearLayout content,Runnable back,Runnable save,int actionIcon,String actionLabel){
        boolean centered=save!=null;
        LinearLayout actions=new LinearLayout(this);
        actions.addView(plainIcon(R.drawable.ic_close,"关闭或返回",back));
        if(save!=null)actions.addView(plainIcon(actionIcon,actionLabel,save));
        TextView title=text(heading,16);title.setGravity(Gravity.CENTER);
        ScrollView scroll=new ScrollView(this);pageScroll=scroll;scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(centered?Gravity.CENTER:Gravity.TOP);
        body.addView(content,new LinearLayout.LayoutParams(-1,-2));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        android.view.ViewGroup original=(android.view.ViewGroup)actions;
        android.view.ViewGroup footer=new android.view.ViewGroup(this){
            private int gap(){return getChildCount()>1?dp(4):0;}
            @Override protected void onMeasure(int ws,int hs){
                int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);
                int count=getChildCount(),cell=(w-gap()*(count-1))/count;
                for(int i=0;i<count;i++)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
                int count=getChildCount(),w=(getWidth()-gap()*(count-1))/count,h=getHeight();
                for(int i=0;i<count;i++){
                    View child=getChildAt(i);
                    int icon=dp(18),cx=count>1?Math.round(w*(i==0?.74f:.26f)):w/2,cy=count>1?Math.round(h*.40f):h/2;
                    child.setPadding(Math.max(0,cx-icon/2),Math.max(0,cy-icon/2),Math.max(0,w-cx-icon/2),Math.max(0,h-cy-icon/2));
                    int x=i*(w+gap());child.layout(x,0,x+w,h);
                }
            }
            @Override protected void dispatchDraw(android.graphics.Canvas canvas){
                int save=canvas.save();
                if(getChildCount()>1){
                    float radius=getWidth()/2f;
                    android.graphics.Path circle=new android.graphics.Path();
                    circle.addCircle(radius,getHeight()-radius,radius,android.graphics.Path.Direction.CW);
                    canvas.clipPath(circle);
                }
                super.dispatchDraw(canvas);canvas.restoreToCount(save);
            }
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                if(getChildCount()>1&&event.getActionMasked()==MotionEvent.ACTION_DOWN){
                    float radius=getWidth()/2f,dx=event.getX()-radius,dy=event.getY()-(getHeight()-radius);
                    if(dx*dx+dy*dy>radius*radius)return false;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        boolean paired=original.getChildCount()>1;
        while(original.getChildCount()>0){
            View child=original.getChildAt(0);original.removeView(child);
            if(paired){
                float corner=dp(5);
                float[] radii=footer.getChildCount()==0?new float[]{0,0,corner,corner,0,0,0,0}:new float[]{corner,corner,0,0,0,0,0,0};
                GradientDrawable fill=new GradientDrawable();fill.setColor(Color.rgb(33,48,52));fill.setCornerRadii(radii);
                GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadii(radii);
                child.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x407DE8CA),fill,mask));
            }
            footer.addView(child);
        }
        android.view.ViewGroup page=new android.view.ViewGroup(this){
            @Override protected void onMeasure(int ws,int hs){
                int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);
                int side=Math.min(w,h);
                title.measure(MeasureSpec.makeMeasureSpec(Math.round(side*.62f),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(28),MeasureSpec.EXACTLY));
                scroll.measure(MeasureSpec.makeMeasureSpec(Math.round(side*.66f),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(side*.64f),MeasureSpec.EXACTLY));
                footer.measure(MeasureSpec.makeMeasureSpec(Math.round(side*(paired?1f:.48f)),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(side*(paired?.18f:.14f)),MeasureSpec.EXACTLY));
            }
            private void place(View v,float y){
                int side=Math.min(getWidth(),getHeight()),x=(getWidth()-v.getMeasuredWidth())/2;
                int top=Math.round((getHeight()-side)/2f+side*y-v.getMeasuredHeight()/2f);
                v.layout(x,top,x+v.getMeasuredWidth(),top+v.getMeasuredHeight());
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){place(title,.10f);place(scroll,.48f);place(footer,paired?.91f:.90f);}
        };
        page.setBackgroundColor(BG);page.addView(title);page.addView(scroll);page.addView(footer);
        install(page);
    }
    private void showList(){
        screen="list";LinearLayout list=column();
        if(fillMode){TextView site=text(fillSite==null?"":fillSite,11);site.setSingleLine(true);site.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);addRow(list,site,24);}
        addRow(list,button("＋ 添加账号",()->edit(-1)),40);
        if(records.length()==0)addRow(list,text("暂无账号",13),40);
        java.util.List<View> sortable=new java.util.ArrayList<>();
        for(int i=0;i<records.length();i++){
            final int index=i;JSONObject item=records.optJSONObject(i);if(item==null)continue;
            Button row=button(item.optString("title"),()->{if(fillMode)selectForFill(index);else detail(index);});row.setSingleLine(true);row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setTag(index);sortable.add(row);addRow(list,row,42);
        }
        DragReorder reorder=new DragReorder(list,sortable,()->!busy&&!closed,ordered->{
            JSONArray next=new JSONArray();for(View row:ordered)next.put(records.optJSONObject((Integer)row.getTag()));
            persistOrder(next);
        });
        for(View row:sortable)reorder.bind(row,()->options((Integer)row.getTag()));
        page(fillMode?"选择账号":"密码本",list,this::finish,null);
    }
    private void selectForFill(int index){
        JSONObject item=records.optJSONObject(index);if(item==null||busy||!unlocked)return;
        VaultFillTransfer.put(fillToken,item.optString("account"),item.optString("password"));
        setResult(RESULT_OK);finish();
    }
    private void detail(int index){
        screen="detail";JSONObject item=records.optJSONObject(index);if(item==null){showList();return;}
        LinearLayout list=column();addRow(list,text(item.optString("account"),13),44);
        TextView password=text("••••••••",13);password.setSingleLine(true);password.setMinWidth(dp(100));
        HorizontalScrollView passwordScroll=new HorizontalScrollView(this);passwordScroll.setFillViewport(true);passwordScroll.addView(password,new HorizontalScrollView.LayoutParams(-2,-1));addRow(list,passwordScroll,44);
        Button reveal=button("显示密码",()->{});reveal.setOnClickListener(v->{boolean shown="隐藏密码".contentEquals(reveal.getText());password.setText(shown?"••••••••":item.optString("password"));reveal.setText(shown?"显示密码":"隐藏密码");});addRow(list,reveal,38);
        page(item.optString("title"),list,this::showList,()->edit(index),R.drawable.ic_edit,"编辑");
    }
    private void options(int index){
        screen="options";LinearLayout list=column();addRow(list,button("编辑",()->edit(index)),40);
        addRow(list,button("删除",()->confirmDelete(index)),40);page("账号选项",list,this::showList,null);
    }
    private void confirmDelete(int index){
        screen="delete";LinearLayout list=column();addRow(list,text("删除这条账号记录？",14),60);
        page("删除账号",list,this::showList,()->{try{JSONArray next=new JSONArray(records.toString());next.remove(index);persist(next);}catch(Exception error){toast("删除失败");}});
    }
    // This field stores a local password-book record, not an online account sign-in.
    @android.annotation.SuppressLint("WearPasswordInput")
    private EditText field(String label,String value,boolean secret,int limit){
        EditText e=new EditText(this);e.setTextColor(FG);e.setHintTextColor(0xff899d9e);e.setTextSize(13);e.setSingleLine(true);e.setHint(label);e.setContentDescription(label);
        e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});e.setText(value);e.setSaveEnabled(false);e.setPadding(dp(10),0,dp(10),0);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(24,37,40));bg.setCornerRadius(dp(14));bg.setStroke(dp(1),Color.rgb(52,73,76));e.setBackground(bg);return e;
    }
    private void edit(int index){
        if(index<0&&records.length()>=200){toast("最多保存 200 条账号");return;}
        screen="edit";JSONObject item=index<0?new JSONObject():records.optJSONObject(index);if(item==null)return;
        LinearLayout list=column();EditText title=field("名称",item.optString("title"),false,100),account=field("账号",item.optString("account"),false,256),password=field("密码",item.optString("password"),true,4096);
        for(EditText input:new EditText[]{title,account,password})addRow(list,input,42);
        page(index<0?"添加账号":"编辑账号",list,this::showList,()->{
            if(title.getText().toString().trim().isEmpty()){title.setError("请输入名称");return;}
            if(password.getText().length()==0){password.setError("请输入密码");return;}
            try{
                JSONObject entry=new JSONObject(item.toString());entry.put("title",title.getText().toString().trim());entry.put("account",account.getText().toString());entry.put("password",password.getText().toString());
                JSONArray next=new JSONArray(records.toString());if(index<0)next.put(entry);else next.put(index,entry);persist(next);
            }catch(Exception error){toast("保存失败");}
        });
    }
    private void persistOrder(JSONArray next){
        int scrollPosition=pageScroll==null?0:pageScroll.getScrollY();
        busy=true;
        worker.execute(()->{
            boolean saved;
            try{store.save(next);saved=true;}catch(Exception error){saved=false;}
            final boolean success=saved;
            ui.post(()->{if(closed)return;busy=false;if(success)records=next;else toast("排序未保存，请重试");
                replaceWithoutAnimation=true;showList();
                pageScroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
                    public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                        v.removeOnLayoutChangeListener(this);((ScrollView)v).scrollTo(0,scrollPosition);
                    }
                });});
        });
    }
    private void persist(JSONArray next){
        busy=true;((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);
        worker.execute(()->{
            try{store.save(next);ui.post(()->{if(closed)return;busy=false;records=next;showList();});}
            catch(Exception error){ui.post(()->{if(closed)return;busy=false;toast("保存失败，请重试");});}
        });
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    @Override public void finish(){super.finish();overridePendingTransition(R.anim.page_hold,R.anim.page_exit);}
    @Override public void onBackPressed(){if(busy)return;if(unlocked&&!"list".equals(screen))showList();else finish();}
    @Override protected void onStop(){super.onStop();lock();finish();}
    private void lock(){
        if(closed)return;closed=true;unlocked=false;records=null;Arrays.fill(digits,'\0');
        if(confirmation!=null){Arrays.fill(confirmation,'\0');confirmation=null;}
        ui.removeCallbacksAndMessages(null);if(pageHost!=null){pageHost.removeAllViews();pageHost=null;}setContentView(new View(this));worker.execute(store::close);worker.shutdown();
    }
    @Override protected void onDestroy(){lock();super.onDestroy();}
}
