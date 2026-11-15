package io.todu.goweb;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static GeckoRuntime runtime;
    private boolean recording;
    private final java.util.Set<Integer> externalRequests=new java.util.HashSet<>();
    boolean protectedInteraction(){
        if(isFinishing()||isDestroyed())return false;
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        return recording||!externalRequests.isEmpty()||(ime!=null&&ime.isAcceptingText());
    }
    @Override public void startActivityForResult(Intent intent,int request,Bundle options){
        if(request>=0&&request!=PICK_VAULT)externalRequests.add(request);
        try{super.startActivityForResult(intent,request,options);}
        catch(RuntimeException error){externalRequests.remove(request);throw error;}
    }
    private GeckoSession session;
    private final HomePageReuse homeReuse=new HomePageReuse();
    private boolean firstFrameReady, engineConfigReady;
    private String preparedEngineConfig;
    private Exception engineConfigFailure;
    private GeckoView browser;
    private FrameLayout roundTop,roundBottom;
    private boolean roundSpace, pageAtBottom;
    private float roundOffset;

    private GestureFrame root;
    private ScrollView menu;
    private View menuTopCover;
    private LinearLayout error;
    private EdgeProgress progress;
    private PullCurtain pullCurtain;
    private FrameLayout startupOverlay;
    private android.graphics.drawable.Animatable startupAnimation;
    private String loadingUrl="";
    private android.content.SharedPreferences prefs;
    private WebExtension.Port port;
    private static WebExtension.Port sharedPort;
    private WebExtension attachedExtension;
    private BrowserPrompts browserPrompts;
    private FileUi fileUi;
    FileUi fileUi(){if(fileUi==null)fileUi=new FileUi(this);return fileUi;}
    // Process-lifetime delegates must not capture an Activity between sessions.
    private static final WebExtension.PortDelegate IDLE_PORT=new WebExtension.PortDelegate(){
        @Override public void onDisconnect(WebExtension.Port p){if(sharedPort==p)sharedPort=null;}
    };
    private static final WebExtension.MessageDelegate IDLE_EXTENSION=new WebExtension.MessageDelegate(){
        @Override public void onConnect(WebExtension.Port p){sharedPort=p;p.setDelegate(IDLE_PORT);}
    };
    private String home, current = "";
    private int zoom, scrollY, viewportHeight=100;
    private float viewportPan;
    private boolean adjustingRange;
    private String settingsDomain="";
    private JSONObject siteSettings=new JSONObject();
    private boolean siteSettingsSent;
    private boolean canBack, started, configured, startupConfigurationSent;
    private Dialog homeEditor, bookmarksEditor, bookmarkAction,clearCacheDialog,infoDialog;
    private String pageTitle="",pageIcon="";
    private BookmarkIcons bookmarkIcons;
    private final long activityStart = SystemClock.elapsedRealtime();
    private boolean firstPaintLogged;
    private ArcRangeControl zoomControl,heightControl;
    private ImageButton clearCacheButton;
    private boolean clearingCache;
    private static final int PICK_VAULT=41;
    private ImageButton vaultFillButton;
    private String vaultFocusToken="",vaultRequestToken="",vaultRequestTop="";
    private boolean vaultPicking;
    private final Runnable vaultArmTimeout=()->{if(!vaultPicking){vaultRequestToken="";toast("请重新点选密码框");}};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startupHandshakeRetry=new Runnable(){@Override public void run(){
        if(started||isFinishing()||isDestroyed())return;
        if(port!=null){startupConfigurationSent=false;configureStartup();}
        handler.postDelayed(this,1500);
    }};
    private final Runnable startupTimeout=()->{
        if(!started&&!isFinishing()&&!isDestroyed())showError("浏览器启动较慢",()->recreate());
    };
    private long loadStart;
    private static final int BG = Color.rgb(17,24,27), FG = Color.rgb(239,246,244), ACCENT = Color.rgb(125,232,202);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        zoom=70;viewportHeight=100;
        bookmarkIcons=new BookmarkIcons(this,(url,icon)->{
            try{JSONObject request=new JSONObject();request.put("type","faviconRequest");request.put("url",url);request.put("icon",icon);request.put("epoch",bookmarkIcons.epoch());sendVault(request);}catch(Exception ignored){}
        });
        root = new GestureFrame(); root.setBackgroundColor(BG);
        progress = new EdgeProgress();
        root.addView(progress, new FrameLayout.LayoutParams(-1,-1));
        pullCurtain = new PullCurtain();
        root.addView(pullCurtain, new FrameLayout.LayoutParams(-1,-1));
        showStartup();
        setContentView(root);
        final boolean needsEngineConfig=runtime==null;
        new Thread(()->{
            String path=null;Exception failure=null;
            String savedHome=prefs.getString("home","https://google.com/");
            JSONObject savedSettings;
            try{savedSettings=new JSONObject(prefs.getString("siteSettings","{}"));}catch(Exception e){savedSettings=new JSONObject();}
            final JSONObject profiles=savedSettings;
            try{if(needsEngineConfig)path=engineConfig();}catch(Exception e){failure=e;}
            final String result=path;final Exception problem=failure;
            handler.post(()->{
                if(isFinishing()||isDestroyed())return;
                home=savedHome;siteSettings=profiles;loadDomainSettings(home);
                preparedEngineConfig=result;engineConfigFailure=problem;engineConfigReady=true;
                startPreparedEngine();
            });
        },"GoApp-engine-config").start();
        if (Build.VERSION.SDK_INT >= 29) root.post(() -> {
            int width=root.getWidth(),edgeWidth=Math.round(width*.08f);
            root.setSystemGestureExclusionRects(Arrays.asList(
                new Rect(0,root.getHeight()/3,edgeWidth,root.getHeight()*2/3),
                new Rect(width-edgeWidth,root.getHeight()/3,width,root.getHeight()*2/3)));
        });
        // Let the lightweight loading surface draw before doing synchronous engine setup.
        root.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener(){
            private boolean scheduled;
            @Override public void onDraw(){
                if(scheduled)return;scheduled=true;
                root.post(()->{
                    if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnDrawListener(this);
                    firstFrameReady=true;startPreparedEngine();
                });
            }
        });
    }
    private void startPreparedEngine(){
        if(!firstFrameReady||!engineConfigReady||isFinishing()||isDestroyed())return;
        firstFrameReady=false;
        if("io.todu.goweb.DOWNLOADS".equals(getIntent().getAction()))fileUi().showDownloads();
        if(engineConfigFailure!=null){showError("浏览器未能启动",()->recreate());return;}
        initializeEngine();
    }
    private void showStartup(){
        startupOverlay=new FrameLayout(this);startupOverlay.setBackgroundColor(BG);startupOverlay.setClickable(true);
        ImageView spinner=new ImageView(this);spinner.setImageResource(R.drawable.startup_spinner);
        spinner.setContentDescription("正在加载");
        startupAnimation=(android.graphics.drawable.Animatable)spinner.getDrawable();
        startupOverlay.addView(spinner,new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.go_icon_art);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        startupOverlay.addView(logo,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));
        root.addView(startupOverlay,new FrameLayout.LayoutParams(-1,-1));startupAnimation.start();
    }
    private void finishStartup(){
        if(startupAnimation!=null){startupAnimation.stop();startupAnimation=null;}
        if(startupOverlay!=null){root.removeView(startupOverlay);startupOverlay=null;}
    }

    private boolean isBlueStacks(){
        // BlueStacks spoofs real Samsung build identifiers. Require its own property,
        // not a phone model or chipset name, before changing the renderer.
        java.lang.Process process=null;
        try{
            process=new ProcessBuilder("/system/bin/getprop","ro.bst.ime_listener_port").start();
            if(!process.waitFor(150,java.util.concurrent.TimeUnit.MILLISECONDS))return false;
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))){
                String value=reader.readLine();return value!=null&&!value.trim().isEmpty();
            }
        }catch(Exception ignored){return false;}finally{if(process!=null)process.destroy();}
    }
    private String engineConfig() throws java.io.IOException {
        boolean software=isBlueStacks();
        java.io.File config=new java.io.File(getFilesDir(), software?"engine-bluestacks-v2.yaml":"engine-v1.yaml");
        if(config.isFile())return config.getAbsolutePath();
        java.io.File cache=new java.io.File(getFilesDir(), "startup-cache");
        cache.mkdirs();
        String yaml="prefs:\n  browser.cache.disk.enable: true\n  browser.cache.disk.smart_size.enabled: false\n  browser.cache.disk.capacity: 262144\n  browser.cache.disk.parent_directory: "+cache.getAbsolutePath()+"\n";
        if(software)yaml+="  gfx.webrender.software: true\n";
        try(java.io.FileWriter writer=new java.io.FileWriter(config)){writer.write(yaml);}
        return config.getAbsolutePath();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if("io.todu.goweb.DOWNLOADS".equals(intent.getAction())){fileUi().showDownloads();return;}
        if(Intent.ACTION_MAIN.equals(intent.getAction()) && configured) {hideMenu();if(!homeReuse.canReuse(home,current))session.loadUri(home);}
    }
    private void initializeEngine() {
        try {
            boolean coldRuntime=runtime==null;
            if(coldRuntime) runtime = GeckoRuntime.create(getApplicationContext(), new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true).webFontsEnabled(true).forceUserScalableEnabled(true)
                .automaticFontSizeAdjustment(false).configFilePath(preparedEngineConfig).build());
            // Start a content process while session delegates and the extension are prepared.
            try{if(coldRuntime)runtime.warmUp();}catch(RuntimeException warmupFailure){android.util.Log.w("GoApp","Warm-up unavailable; opening normally",warmupFailure);}
            browser=new GeckoView(this);
            // Select once before attaching the session. Resizing or moving the viewport must
            // not replace the live rendering surface when returning to 100%.
            browser.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
            root.addView(browser,0,new FrameLayout.LayoutParams(-1,-1));
            roundTop=createRoundSpace();roundBottom=createRoundSpace();
            root.addView(roundTop,1,new FrameLayout.LayoutParams(-1,0));
            root.addView(roundBottom,2,new FrameLayout.LayoutParams(-1,0));
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)applyViewportHeight();});
            applyViewportHeight();
            session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN).build());
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public void onLocationChange(GeckoSession s, String url, List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) { current = url == null ? "" : url;hideVaultFill();loadDomainSettings(current); }
                @Override public void onCanGoBack(GeckoSession s, boolean value) { canBack = value; }
                @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest r) {
                    if(r.uri.startsWith("http://") || r.uri.startsWith("https://") || r.uri.equals("about:blank") || r.uri.startsWith("blob:")) {
                        if(r.target == TARGET_WINDOW_NEW) { s.loadUri(r.uri); return GeckoResult.fromValue(AllowOrDeny.DENY); }
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                    }
                    if(r.hasUserGesture && (r.uri.startsWith("mailto:") || r.uri.startsWith("tel:"))) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.uri))); } catch(Exception e) { toast("未找到可打开的应用"); }
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError failure) {
                    homeReuse.invalidate();showError("网页未能打开", () -> { hideError(); session.loadUri(uri); });
                    return GeckoResult.fromValue(null);
                }
            });
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onPageStart(GeckoSession s, String url) { recording=false;homeReuse.start(home,url);hideVaultFill();scrollY=0; viewportPan=0; pageTitle="";pageIcon=""; pageAtBottom=false; roundSpace=false; setRoundOffset(0); loadingUrl=url;loadDomainSettings(url); loadStart=SystemClock.elapsedRealtime(); hideError(); progress.begin(); }
                @Override public void onProgressChange(GeckoSession s, int value) { progress.advance(value); }
                @Override public void onPageStop(GeckoSession s, boolean success) {
                    homeReuse.stop(home,current,success);
                    progress.finish(success);
                    if(started&&success&&(loadingUrl.startsWith("https://")||loadingUrl.startsWith("http://")))finishStartup();
                    android.util.Log.i("GoApp", "pageStop success="+success+" elapsedMs="+(SystemClock.elapsedRealtime()-loadStart));
                }
            });
            session.setScrollDelegate(new GeckoSession.ScrollDelegate() {
                @Override public void onScrollChanged(GeckoSession s, int x, int y) { scrollY=y; }
            });
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override public void onExternalResponse(GeckoSession s,WebResponse response){
                    if(isFinishing()||isDestroyed()){DownloadService.close(response);return;}
                    fileUi().offerDownload(response);
                }
                @Override public void onTitleChange(GeckoSession s,String title){pageTitle=title==null?"":title;}
                @Override public void onFirstContentfulPaint(GeckoSession s) {
                    if(started&&(loadingUrl.startsWith("https://")||loadingUrl.startsWith("http://")))finishStartup();
                    android.util.Log.i("GoApp", "firstPaintMs="+(SystemClock.elapsedRealtime()-loadStart));
                    if(!firstPaintLogged){firstPaintLogged=true;android.util.Log.i("GoApp","activityFirstPaintMs="+(SystemClock.elapsedRealtime()-activityStart));} }
                @Override public void onCrash(GeckoSession s) { recording=false;homeReuse.invalidate();showError("页面已停止", () -> recreate()); }
            });
            browserPrompts=new BrowserPrompts(this);session.setPromptDelegate(browserPrompts);
            session.setMediaDelegate(new GeckoSession.MediaDelegate(){@Override public void onRecordingStatusChanged(GeckoSession s,GeckoSession.MediaDelegate.RecordingDevice[] devices){recording=false;for(GeckoSession.MediaDelegate.RecordingDevice device:devices)if(device.status==GeckoSession.MediaDelegate.RecordingDevice.Status.RECORDING)recording=true;}});
            session.open(runtime); browser.setSession(session);
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/browser/", "browser@goapp.todu.io")
                .accept(extension -> {
                    if(isFinishing()||isDestroyed())return;
                    attachedExtension=extension;
                    WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
                    @Override public void onConnect(WebExtension.Port connection) {
                        if(isFinishing()||isDestroyed()){IDLE_EXTENSION.onConnect(connection);return;}
                        if(port!=null&&port!=connection)port.setDelegate(IDLE_PORT);
                        startupConfigurationSent=false;siteSettingsSent=false;
                        port = connection; sharedPort=connection;
                        port.setDelegate(new WebExtension.PortDelegate() {
                            @Override public void onPortMessage(Object message, WebExtension.Port p) {
                                if(isFinishing()||isDestroyed()||port!=p||!(message instanceof JSONObject)) return;
                                JSONObject m=(JSONObject)message;
                                if("pageIcon".equals(m.optString("type"))&&m.optString("url").equals(current))pageIcon=m.optString("icon");
                                if("faviconFailed".equals(m.optString("type"))&&m.optInt("epoch",-1)==bookmarkIcons.epoch())bookmarkIcons.failed(m.optString("url"));
                                if("favicon".equals(m.optString("type"))&&m.optInt("epoch",-1)==bookmarkIcons.epoch())bookmarkIcons.accept(m.optString("url"),m.optString("data"));
                                if(m.optString("type").startsWith("vault")){handleVaultMessage(m);return;}
                                if("pageBounds".equals(m.optString("type")) && (m.optString("url").equals(current)||m.optString("url").equals(loadingUrl)))pageAtBottom=m.optBoolean("bottom");
                                if("ready".equals(m.optString("type"))&&!started){startupConfigurationSent=false;configureStartup();}
                                if("configured".equals(m.optString("type"))) {
                                    configured=true;
                                    String action=m.optString("action");
                                    if("start".equals(action) && !started) { started=true;handler.removeCallbacks(startupTimeout);handler.removeCallbacks(startupHandshakeRetry);hideError(); session.loadUri(home); }
                                    else if("home".equals(action)) { hideMenu(); session.loadUri(home); }
                                }
                                if("error".equals(m.optString("type"))) { android.util.Log.e("GoApp",m.optString("message")); showError("页面设置未能加载", () -> recreate()); }
                            }
                            @Override public void onDisconnect(WebExtension.Port p) { if(port==p) {hideVaultFill();port=null;configured=false;startupConfigurationSent=false;} if(sharedPort==p)sharedPort=null; }
                        });
                        configureStartup();
                    }
                }; extension.setMessageDelegate(delegate,"goapp"); if(sharedPort!=null&&port!=sharedPort)delegate.onConnect(sharedPort); }, exception -> { android.util.Log.e("GoApp","Extension failed",exception); showError("浏览器未能启动", () -> recreate()); });
            if(!started){handler.postDelayed(startupTimeout,20000);handler.postDelayed(startupHandshakeRetry,1500);}
        } catch(Exception e) { android.util.Log.e("GoApp","Engine failed",e); showError("浏览器未能启动", () -> recreate()); }
    }
    private void hideVaultFill(){
        vaultFocusToken="";if(vaultFillButton!=null)vaultFillButton.setVisibility(View.GONE);
    }
    private void positionVaultFill(){
        if(vaultFillButton==null||browser==null)return;
        int size=dp(36);
        Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);
        int[] location=new int[2];root.getLocationOnScreen(location);
        int keyboardInset=visible.height()>0?Math.max(0,location[1]+root.getHeight()-visible.bottom):0;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(size,size,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin=keyboardInset+Math.max(dp(8),Math.round(Math.min(root.getWidth(),root.getHeight())*.035f));
        ViewGroup.LayoutParams existing=vaultFillButton.getLayoutParams();
        if(!(existing instanceof FrameLayout.LayoutParams) || existing.width!=size || existing.height!=size ||
            ((FrameLayout.LayoutParams)existing).bottomMargin!=lp.bottomMargin || ((FrameLayout.LayoutParams)existing).gravity!=lp.gravity)
            vaultFillButton.setLayoutParams(lp);
    }
    private void sendVault(JSONObject message){if(port!=null)port.postMessage(message);}
    private void requestVaultFill(){
        if(port==null||vaultFocusToken.isEmpty()||vaultPicking||!vaultRequestToken.isEmpty())return;
        try{
            vaultRequestToken=vaultFocusToken;vaultRequestTop=current;
            JSONObject message=new JSONObject();message.put("type","vaultArm");message.put("token",vaultRequestToken);sendVault(message);
            handler.postDelayed(vaultArmTimeout,3000);
        }catch(Exception error){vaultRequestToken="";toast("请重新点选密码框");}
    }
    private void handleVaultMessage(JSONObject message){
        String type=message.optString("type");
        if("vaultFocus".equals(type)){
            if(!message.optBoolean("visible")){hideVaultFill();return;}
            if(vaultPicking||menu!=null||!message.optString("top").equals(current))return;
            vaultFocusToken=message.optString("token");
            if(vaultFillButton==null){
                vaultFillButton=menuIcon(R.drawable.ic_vault,"从密码本填充",false,this::requestVaultFill);
                vaultFillButton.setPadding(dp(8),dp(8),dp(8),dp(8));
                vaultFillButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
                GradientDrawable fill=new GradientDrawable();fill.setShape(GradientDrawable.OVAL);fill.setColor(0xB3213034);
                fill.setStroke(dp(1),0x507DE8CA);
                GradientDrawable mask=new GradientDrawable();mask.setShape(GradientDrawable.OVAL);mask.setColor(Color.WHITE);
                vaultFillButton.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x407DE8CA),fill,mask));
                vaultFillButton.setFocusable(false);root.addView(vaultFillButton);
                root.getViewTreeObserver().addOnGlobalLayoutListener(this::positionVaultFill);
                root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionVaultFill());
            }
            positionVaultFill();vaultFillButton.setVisibility(View.VISIBLE);vaultFillButton.bringToFront();
        }else if("vaultArmed".equals(type)){
            if(!message.optString("token").equals(vaultRequestToken)||!current.equals(vaultRequestTop))return;
            handler.removeCallbacks(vaultArmTimeout);vaultPicking=true;hideVaultFill();
            String site=Uri.parse(message.optString("url")).getHost();
            startActivityForResult(new Intent(this,VaultActivity.class).putExtra("fillToken",vaultRequestToken).putExtra("fillSite",site),PICK_VAULT);
            overridePendingTransition(R.anim.page_enter,R.anim.page_hold);
        }else if("vaultResult".equals(type)){
            handler.removeCallbacks(vaultArmTimeout);vaultRequestToken="";
            if(!message.optBoolean("ok"))toast("页面已变化，请重新点选密码框");
            hideVaultFill();
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==FileUi.STORAGE_PERMISSION&&fileUi!=null)fileUi.storageResult(grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        externalRequests.remove(request);
        super.onActivityResult(request,result,data);if(request==FileUi.PICK_FILE){if(fileUi!=null)fileUi.result(result,data);return;}if(request!=PICK_VAULT)return;
        vaultPicking=false;String token=vaultRequestToken;vaultRequestToken="";
        try(VaultFillTransfer.Entry entry=VaultFillTransfer.take(token)){
            JSONObject message=new JSONObject();
            if(result==RESULT_OK&&entry!=null&&current.equals(vaultRequestTop)&&port!=null){
                message.put("type","vaultFill");message.put("token",token);
                message.put("account",new String(entry.account));message.put("password",new String(entry.password));
            }else message.put("type","vaultCancel");
            sendVault(message);
        }catch(Exception error){toast("未能填充，请重试");}
    }
    private FrameLayout createRoundSpace(){
        FrameLayout block=new FrameLayout(this);block.setBackgroundColor(Color.BLACK);block.setClickable(true);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.go_icon_art);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        block.addView(logo,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));
        block.setVisibility(View.GONE);return block;
    }
    private void setRoundOffset(float offset){
        if(browser==null||roundTop==null)return;
        float half=root.getHeight()/2f;
        roundOffset=roundSpace?Math.max(-half,Math.min(half,offset)):0;
        browser.setTranslationY(roundOffset-viewportPan);
        for(int i=0;i<2;i++){
            FrameLayout block=i==0?roundTop:roundBottom;
            if(block.getLayoutParams().height!=Math.round(half)){
                block.getLayoutParams().height=Math.round(half);block.requestLayout();
            }
            block.setVisibility(roundSpace?View.VISIBLE:View.GONE);
        }
        roundTop.setTranslationY(roundOffset-viewportPan-half);
        roundBottom.setTranslationY(root.getHeight()+maxViewportPan()-viewportPan+roundOffset);
    }
    private void toggleRoundSpace(){
        roundSpace=!roundSpace;
        setRoundOffset(roundSpace?(scrollY<=1&&viewportPan<=1?root.getHeight()/2f:pageAtBottom&&viewportPan>=maxViewportPan()-1?-root.getHeight()/2f:0):0);
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private void configureStartup() {
        if(port==null||startupConfigurationSent)return;
        startupConfigurationSent=true;
        configure("start",false);
    }
    private void configure(String action, boolean clear) {
        if(port==null) return;
        try { JSONObject m=new JSONObject(); m.put("type","configure");m.put("home",home);m.put("zoom",zoom);if(siteSettingsSent&&"zoom".equals(action)&&!settingsDomain.isEmpty()){m.put("siteHost",settingsDomain);m.put("siteProfile",siteSettings.optJSONObject(settingsDomain));}else m.put("siteSettings",readSiteSettings());m.put("action",action);m.put("clearCache",clear);port.postMessage(m);siteSettingsSent=true; }
        catch(Exception e) { toast("设置未能保存"); }
    }
    private void showMenu() {
        hideVaultFill();
        if(menu != null) return;
        menuTopCover=new View(this);menuTopCover.setBackgroundColor(BG);menuTopCover.setClickable(true);
        menuTopCover.setPivotY(0);menuTopCover.setScaleY(.5f);
        root.addView(menuTopCover,new FrameLayout.LayoutParams(-1,-1));
        menu=new MenuScroll(); menu.setFillViewport(true);
        menu.addView(new RoundMenuLayout());
        root.addView(menu,new FrameLayout.LayoutParams(-1,-1));
    }

    private ImageButton menuIcon(int icon,String label,boolean primary,Runnable action){
        ImageButton button=new ImageButton(this);
        button.setImageResource(icon);button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(13),dp(13),dp(13),dp(13));
        button.setImageTintList(android.content.res.ColorStateList.valueOf(primary?BG:FG));
        button.setContentDescription(label);button.setTooltipText(label);
        GradientDrawable fill=new GradientDrawable();fill.setShape(GradientDrawable.OVAL);
        fill.setColor(primary?ACCENT:Color.rgb(33,48,52));
        if(!primary)fill.setStroke(dp(1),Color.rgb(46,65,68));
        GradientDrawable mask=new GradientDrawable();mask.setShape(GradientDrawable.OVAL);mask.setColor(Color.WHITE);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x407DE8CA),fill,mask));
        button.setOnClickListener(v->action.run());return button;
    }

    /** Native arc slider. Horizontal adjustment leaves vertical menu dismissal available. */
    private final class ArcRangeControl extends View {
        private final boolean heightMode;
        private final android.graphics.RectF oval=new android.graphics.RectF();
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int value,initial;
        private float downX,downY;
        private boolean horizontal,cancelled;
        private final int slop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        ArcRangeControl(boolean heightMode){
            super(MainActivity.this);this.heightMode=heightMode;value=heightMode?viewportHeight:zoom;
            setFocusable(true);setClickable(true);updateDescription();
        }
        private int min(){return heightMode?100:20;}
        private int max(){return heightMode?300:200;}
        private void updateDescription(){setContentDescription((heightMode?"视口高度 ":"网页缩放 ")+value+"%");}
        private void preview(int next){value=Math.max(min(),Math.min(max(),Math.round(next/10f)*10));updateDescription();invalidate();}
        private void commit(){if(heightMode)setViewportHeight(value);else setZoom(value);}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            float w=getWidth(),h=getHeight(),thickness=h*.62f,radius=w*.95f;
            float cy=radius+thickness/2f,angle=(float)Math.toDegrees(Math.asin((w-thickness)/2f/radius));
            oval.set(w/2-radius,cy-radius,w/2+radius,cy+radius);
            int saved=canvas.save();if(heightMode){canvas.translate(0,h);canvas.scale(1,-1);}
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(Color.rgb(33,48,52));paint.setStrokeWidth(thickness);canvas.drawArc(oval,270-angle,angle*2,false,paint);
            canvas.restoreToCount(saved);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(FG);paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTextSize(dp(12));
            float labelY=heightMode?h-thickness/2f:thickness/2f;
            canvas.drawText(value+"%",w/2,labelY-(paint.ascent()+paint.descent())/2,paint);
            float dx=(w-thickness)/2f;
            float endY=cy-(float)Math.sqrt(Math.max(0,radius*radius-dx*dx));
            if(heightMode)endY=h-endY;
            float half=Math.min(dp(4),thickness*.16f),left=thickness/2f,right=w-thickness/2f;
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.3f*getResources().getDisplayMetrics().density);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            canvas.drawLine(left-half,endY,left+half,endY,paint);
            canvas.drawLine(right-half,endY,right+half,endY,paint);
            canvas.drawLine(right,endY-half,right,endY+half,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();initial=value;horizontal=false;cancelled=false;return true;}
            if(e.getPointerCount()>1||e.getActionMasked()==MotionEvent.ACTION_CANCEL){cancelled=true;adjustingRange=false;preview(initial);return true;}
            if(cancelled)return true;
            float dx=e.getX()-downX,dy=e.getY()-downY;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                if(!horizontal&&Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.5f){horizontal=true;adjustingRange=true;}
                if(horizontal)preview(initial+Math.round(dx/Math.max(1,getWidth()*.72f)*(max()-min())));
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                if(!horizontal&&Math.abs(dx)<=slop&&Math.abs(dy)<=slop){
                    if(e.getX()<getWidth()*.27f)preview(value-10);
                    else if(e.getX()>getWidth()*.73f)preview(value+10);
                    performClick();
                }
                adjustingRange=false;commit();
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,min(),max(),value));
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args){
            if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){preview(value+(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?10:-10));commit();return true;}
            return super.performAccessibilityAction(action,args);
        }
    }

    /** All controls lie inside the inscribed circle, including on square emulators. */
    private final class RoundMenuLayout extends android.view.ViewGroup {
        private int page;
        private final android.graphics.Paint dotPaint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float downX,downY,offset;
        private boolean paging,eligible;
        private android.animation.ValueAnimator pageMotion;
        private final int touchSlop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        RoundMenuLayout(){
            super(MainActivity.this);
            heightControl=new ArcRangeControl(true);addView(heightControl);
            zoomControl=new ArcRangeControl(false);addView(zoomControl);
            // Six actions per page; the second page currently contains three actions.
            addView(menuIcon(R.drawable.ic_home,"主页",false,()->{hideMenu();if(configured)session.loadUri(home);}));
            addView(menuIcon(R.drawable.ic_refresh,"刷新",false,()->{hideMenu();hideError();if(configured)session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);}));
            clearCacheButton=menuIcon(R.drawable.ic_clear_cache,"清除所有缓存",false,MainActivity.this::confirmClearCaches);
            clearCacheButton.setEnabled(!clearingCache);clearCacheButton.setAlpha(clearingCache?.45f:1f);addView(clearCacheButton);
            addView(menuIcon(R.drawable.ic_open_url,"打开 URL",false,()->editAddress(false)));
            addView(menuIcon(R.drawable.ic_bookmark,"书签",false,MainActivity.this::showBookmarks));
            addView(menuIcon(R.drawable.ic_vault,"账号管理器",false,()->{startActivity(new Intent(MainActivity.this,VaultActivity.class));overridePendingTransition(R.anim.page_enter,R.anim.page_hold);}));
            addView(menuIcon(R.drawable.ic_download,"下载",false,()->fileUi().showDownloads()));
            addView(menuIcon(R.drawable.ic_globe_edit,"设置主页",false,MainActivity.this::editHome));
            addView(menuIcon(R.drawable.ic_info,"软件信息",false,MainActivity.this::showAppInfo));
            for(int i=2;i<getChildCount();i++){
                View child=getChildAt(i);
                GradientDrawable tile=new GradientDrawable();tile.setCornerRadius(dp(12));
                tile.setColor(Color.rgb(33,48,52));
                GradientDrawable tileMask=new GradientDrawable();tileMask.setCornerRadius(dp(12));tileMask.setColor(Color.WHITE);
                child.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x407DE8CA),tile,tileMask),dp(6)));
                child.setPadding(dp(11),dp(11),dp(11),dp(11));
            }
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec);
            int height=MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED?root.getHeight():MeasureSpec.getSize(heightSpec);
            height=Math.max(dp(180),height);setMeasuredDimension(width,height);
            float side=Math.min(width,height);
            for(int i=0;i<getChildCount();i++){
                int size=Math.min(dp(48),Math.round(side*.22f));
                int w=i<=1?Math.round(side*.46f):size;
                int h=i<=1?Math.min(dp(42),Math.round(side*.20f)):size;
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));
            }
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            float side=Math.min(getWidth(),getHeight()),left=(getWidth()-side)/2f,top=(getHeight()-side)/2f;
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);
                float cx=i<2?.5f:(.23f+((i-2)%3)*.27f);
                float cy=i==0?.87f:i==1?.13f:((i-2)%6<3?.35f:.56f);
                int x=Math.round(left+side*cx-child.getMeasuredWidth()/2f);
                int y=Math.round(top+side*cy-child.getMeasuredHeight()/2f);
                child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());
            }
            positionPages();
        }
        private void positionPages(){
            for(int i=2;i<getChildCount();i++){
                View child=getChildAt(i);int group=(i-2)/6;
                child.setTranslationX((group-page)*getWidth()+offset);
                child.setVisibility(group==page||paging||pageMotion!=null?View.VISIBLE:View.INVISIBLE);
                child.setImportantForAccessibility(group==page?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            invalidate();
        }
        @Override protected void dispatchDraw(android.graphics.Canvas canvas){
            int save=canvas.save();canvas.clipRect(0,0,getWidth(),getHeight());
            super.dispatchDraw(canvas);canvas.restoreToCount(save);
            float side=Math.min(getWidth(),getHeight()),y=(getHeight()-side)/2f+side*.71f;
            for(int i=0;i<2;i++){dotPaint.setColor(i==page?ACCENT:0xFF405659);canvas.drawCircle(getWidth()/2f+(i==0?-dp(5):dp(5)),y,dp(2),dotPaint);}
        }
        private void settlePage(int target){
            if(pageMotion!=null)pageMotion.cancel();
            float destination=(page-target)*getWidth();
            pageMotion=android.animation.ValueAnimator.ofFloat(offset,destination);pageMotion.setDuration(220);
            pageMotion.setInterpolator(new android.view.animation.DecelerateInterpolator());
            pageMotion.addUpdateListener(animation->{offset=(float)animation.getAnimatedValue();positionPages();});
            pageMotion.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){page=target;offset=0;pageMotion=null;positionPages();}
            });pageMotion.start();
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(pageMotion!=null)pageMotion.end();
                downX=event.getX();downY=event.getY();paging=false;
                float side=Math.min(getWidth(),getHeight()),top=(getHeight()-side)/2f;
                eligible=downY>top+side*.24f&&downY<top+side*.76f;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&eligible){
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(Math.abs(dx)>touchSlop&&Math.abs(dx)>Math.abs(dy)*1.5f){
                    paging=true;adjustingRange=true;getParent().requestDisallowInterceptTouchEvent(true);return true;
                }
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!paging&&eligible&&event.getActionMasked()==MotionEvent.ACTION_MOVE){
                float movement=event.getX()-downX,vertical=event.getY()-downY;
                if(Math.abs(movement)>touchSlop&&Math.abs(movement)>Math.abs(vertical)*1.5f){
                    paging=true;adjustingRange=true;getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if(!paging)return true;
            float dx=event.getX()-downX;
            if((page==0&&dx>0)||(page==1&&dx<0))dx*=.25f;
            offset=Math.max(-getWidth(),Math.min(getWidth(),dx));positionPages();
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                int target=page;
                if(event.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(offset)>getWidth()*.16f)target=Math.max(0,Math.min(1,page+(offset<0?1:-1)));
                paging=false;adjustingRange=false;getParent().requestDisallowInterceptTouchEvent(false);settlePage(target);
            }
            return true;
        }
        @Override protected void onDetachedFromWindow(){
            if(pageMotion!=null)pageMotion.end();adjustingRange=false;super.onDetachedFromWindow();
        }
    }

    private void confirmClearCaches(){
        if(clearingCache||(clearCacheDialog!=null&&clearCacheDialog.isShowing()))return;
        Dialog dialog=new Dialog(this);clearCacheDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        TextView message=text("此操作将清除所有页面缓存及登录记录，是否继续？",13);message.setGravity(Gravity.CENTER);body.addView(message);
        LinearLayout actions=singleClose(dialog);
        actions.addView(plainIcon(R.drawable.ic_check,"确认清除",()->{dialog.dismiss();clearAllCaches();}),new LinearLayout.LayoutParams(dp(40),dp(40)));
        dialog.setOnDismissListener(d->{if(clearCacheDialog==dialog)clearCacheDialog=null;});
        showPinnedDialog(dialog,"清除缓存",body,actions,true);
    }
    private JSONObject readSiteSettings(){
        return siteSettings;
    }
    private String domainOf(String url){
        try{String host=new java.net.URI(url).getHost();return host==null?"":host.toLowerCase(Locale.ROOT);}catch(Exception error){return "";}
    }
    private void loadDomainSettings(String url){
        String domain=domainOf(url);if(domain.equals(settingsDomain))return;settingsDomain=domain;
        JSONObject profile=readSiteSettings().optJSONObject(domain);
        zoom=profile==null?70:Math.max(20,Math.min(200,profile.optInt("zoom",70)));
        viewportHeight=profile==null?100:Math.max(100,Math.min(300,profile.optInt("height",100)));
        viewportPan=0;if(root!=null)applyViewportHeight();
        if(zoomControl!=null)zoomControl.preview(zoom);if(heightControl!=null)heightControl.preview(viewportHeight);
    }
    private void saveDomainSettings(){
        if(settingsDomain.isEmpty())return;
        try{JSONObject all=readSiteSettings(),profile=new JSONObject();profile.put("zoom",zoom);profile.put("height",viewportHeight);all.put(settingsDomain,profile);prefs.edit().putString("siteSettings",all.toString()).apply();}
        catch(Exception error){toast("设置未能保存");}
    }
    private void clearAllCaches(){
        if(clearingCache||runtime==null)return;
        clearingCache=true;homeReuse.invalidate();
        if(clearCacheButton!=null){clearCacheButton.setEnabled(false);clearCacheButton.setAlpha(.45f);}
        DownloadService.cancelPending();stopService(new Intent(this,DownloadService.class));
        DownloadFiles.get(this).clearAll(downloadsOk->{if(!isDestroyed()&&!isFinishing())fileUi().clearTemporary(temporaryOk->clearBrowserCaches(downloadsOk&&temporaryOk));});
    }
    private void clearBrowserCaches(boolean localSuccess){
        if(isDestroyed())return;
        // Clear browser caches and website authentication/storage, leaving the local vault intact.
        try{
            runtime.getStorageController().clearData(StorageController.ClearFlags.ALL_CACHES | StorageController.ClearFlags.COOKIES | StorageController.ClearFlags.DOM_STORAGES | StorageController.ClearFlags.AUTH_SESSIONS).accept(
                value->completeCacheClear(localSuccess),error->completeCacheClear(false));
        }catch(Exception error){completeCacheClear(false);}
    }
    private void completeCacheClear(boolean success){
        clearingCache=false;if(isDestroyed())return;
        if(clearCacheButton!=null){clearCacheButton.setEnabled(true);clearCacheButton.setAlpha(1f);}
        if(success){
            bookmarkIcons.clear();
            try{JSONObject clear=new JSONObject();clear.put("type","faviconClear");sendVault(clear);}catch(Exception ignored){}
            siteSettings=new JSONObject();siteSettingsSent=false;
            prefs.edit().remove("siteSettings").remove("zoom").remove("viewportHeight").apply();
            zoom=70;viewportHeight=100;viewportPan=0;applyViewportHeight();
            if(zoomControl!=null)zoomControl.preview(zoom);if(heightControl!=null)heightControl.preview(viewportHeight);
            configure("zoom",false);
        }
        toast(success?"已清除":"清除失败，请重试");
    }
    private void setZoom(int value){
        zoom=Math.max(20,Math.min(200,Math.round(value/10f)*10));saveDomainSettings();configure("zoom",false);
    }
    private float maxViewportPan(){return Math.max(0,Math.round(root.getHeight()*viewportHeight/100f)-root.getHeight());}
    private void setViewportPan(float value){viewportPan=Math.max(0,Math.min(maxViewportPan(),value));setRoundOffset(roundOffset);}
    private void applyViewportHeight(){
        if(browser==null||root.getHeight()==0)return;
        int target=Math.round(root.getHeight()*viewportHeight/100f);
        if(browser.getLayoutParams().height!=target){browser.getLayoutParams().height=target;browser.requestLayout();}
        setViewportPan(viewportPan);
    }
    private void setViewportHeight(int value){
        viewportHeight=Math.max(100,Math.min(300,Math.round(value/10f)*10));
        saveDomainSettings();configure("settings",false);applyViewportHeight();
    }

    private void editHome(){editAddress(true);}
    private void editAddress(boolean startup) {
        if(homeEditor!=null&&homeEditor.isShowing())return;
        Dialog dialog=new Dialog(this);homeEditor=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int gap=dp(10);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title=text("启动网址",16);title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));

        EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(13);
        input.setTextColor(FG);input.setHintTextColor(Color.rgb(137,157,158));input.setHint("https://");
        input.setSelectAllOnFocus(true);input.setText(startup?home:"");input.setPadding(dp(12),0,dp(12),0);
        input.setContentDescription(startup?"启动网址":"打开 URL");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        android.graphics.drawable.StateListDrawable field=new android.graphics.drawable.StateListDrawable();
        for(boolean focused:new boolean[]{true,false}){
            GradientDrawable background=new GradientDrawable();background.setColor(Color.rgb(24,37,40));
            background.setCornerRadius(dp(16));background.setStroke(dp(1),focused?ACCENT:Color.rgb(52,73,76));
            field.addState(focused?new int[]{android.R.attr.state_focused}:new int[]{},background);
        }
        input.setBackground(field);LinearLayout.LayoutParams fieldParams=new LinearLayout.LayoutParams(-1,dp(48));
        fieldParams.topMargin=gap;card.addView(input,fieldParams);
        Runnable save=()->{
            String value=input.getText().toString().trim();if(!value.contains("://"))value="https://"+value;
            try{
                java.net.URI uri=new java.net.URI(value);
                if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getRawUserInfo()!=null)throw new IllegalArgumentException();
                if(uri.getRawPath()==null||uri.getRawPath().isEmpty())value=Uri.parse(value).buildUpon().path("/").build().toString();
            }catch(Exception e){input.setError("请输入有效网址");return;}
            if(!startup&&!configured){toast("浏览器尚未就绪");return;}
            boolean changed=startup&&!home.equals(value);
            if(startup){homeReuse.invalidate();home=value;prefs.edit().putString("home",home).apply();}
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
            dialog.dismiss();
            if(startup)configure("home",changed);else{hideMenu();hideError();session.loadUri(value);}
        };
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        ImageButton cancel=plainIcon(R.drawable.ic_close,"取消",dialog::dismiss);
        ImageButton confirm=plainIcon(R.drawable.ic_check,startup?"保存并打开":"打开",save);
        actions.addView(cancel,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(dp(40),dp(40));saveParams.leftMargin=dp(6);actions.addView(confirm,saveParams);
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,dp(40));actionParams.topMargin=gap;
        input.setOnEditorActionListener((view,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO){save.run();return true;}return false;
        });
        dialog.setOnDismissListener(d->{if(homeEditor==dialog)homeEditor=null;});
        showPinnedDialog(dialog,startup?"启动网址":"打开 URL",card,actions,true);
    }
    private void showAppInfo(){
        if(infoDialog!=null&&infoDialog.isShowing())return;
        Dialog dialog=new Dialog(this);infoDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout emblem=new FrameLayout(this);
        GradientDrawable ring=new GradientDrawable();ring.setShape(GradientDrawable.OVAL);ring.setColor(0xff172b2d);ring.setStroke(dp(1),0x507de8ca);emblem.setBackground(ring);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.go_icon_art);logo.setContentDescription("Go web");
        emblem.addView(logo,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER));
        body.addView(emblem,new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView name=text("Go web",18);name.setGravity(Gravity.CENTER);name.setLetterSpacing(.035f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-1,dp(28));nameParams.topMargin=dp(10);body.addView(name,nameParams);
        String version="";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        TextView number=text("v"+version,11);number.setGravity(Gravity.CENTER);number.setTextColor(ACCENT);
        number.setContentDescription("版本 "+version);number.setPadding(dp(14),0,dp(14),0);
        GradientDrawable versionBadge=new GradientDrawable();versionBadge.setColor(0xff213034);versionBadge.setCornerRadius(dp(12));number.setBackground(versionBadge);
        LinearLayout.LayoutParams versionParams=new LinearLayout.LayoutParams(-2,dp(24));versionParams.topMargin=dp(5);body.addView(number,versionParams);
        TextView engine=text("GeckoView",10);engine.setTextColor(0xff899d9e);engine.setGravity(Gravity.CENTER);engine.setLetterSpacing(.04f);
        LinearLayout.LayoutParams engineParams=new LinearLayout.LayoutParams(-1,dp(18));engineParams.topMargin=dp(9);body.addView(engine,engineParams);
        dialog.setOnDismissListener(d->{if(infoDialog==dialog)infoDialog=null;});
        showPinnedDialog(dialog,"软件信息",body,singleClose(dialog),true);
    }
    private void showRoundDialog(Dialog dialog,LinearLayout content){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout safe=new LinearLayout(this);safe.setOrientation(LinearLayout.VERTICAL);safe.setGravity(Gravity.CENTER);
        int side=Math.min(root.getWidth(),root.getHeight());
        safe.setPadding(Math.round(side*.17f),Math.round(side*.17f),Math.round(side*.17f),Math.round(side*.17f));
        safe.addView(content,new LinearLayout.LayoutParams(-1,-2));scroll.addView(safe,new ScrollView.LayoutParams(-1,-2));
        dialog.setContentView(scroll);
        openFullDialog(dialog);
    }
    private void openFullDialog(Dialog dialog){
        Window window=dialog.getWindow();
        if(window!=null){
            window.setWindowAnimations(R.style.PageWindowAnimation);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
        dialog.show();if(window!=null)window.setLayout(-1,-1);
    }
    ImageButton plainIcon(int icon,String label,Runnable action){
        ImageButton button=menuIcon(icon,label,false,action);
        button.setPadding(dp(11),dp(11),dp(11),dp(11));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x407DE8CA),null,null));return button;
    }
    void showPinnedDialog(Dialog dialog,String heading,View content,View actions,boolean centered){
        TextView title=text(heading,16);title.setGravity(Gravity.CENTER);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
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
        dialog.setContentView(page);openFullDialog(dialog);
    }
    LinearLayout singleClose(Dialog dialog){
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        actions.addView(plainIcon(R.drawable.ic_close,"关闭",dialog::dismiss),new LinearLayout.LayoutParams(dp(40),dp(40)));return actions;
    }
    private void bookmarkOptions(String original,Runnable refresh){
        Dialog dialog=new Dialog(this);bookmarkAction=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);
        addButton(choices,"编辑",()->{dialog.dismiss();editBookmark(original,refresh);});
        addButton(choices,"删除",()->{
            org.json.JSONArray items=readBookmarks(),updated=new org.json.JSONArray();
            for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&!original.equals(item.optString("url")))updated.put(item);}
            prefs.edit().putString("bookmarks",updated.toString()).apply();dialog.dismiss();refresh.run();toast("书签已删除");
        });
        dialog.setOnDismissListener(d->{if(bookmarkAction==dialog)bookmarkAction=null;});
        showPinnedDialog(dialog,"书签选项",choices,singleClose(dialog),true);
    }
    private EditText bookmarkField(String value,String hint,boolean url){
        EditText field=new EditText(this);field.setTextColor(FG);field.setTextSize(13);field.setSingleLine(true);
        field.setHint(hint);field.setContentDescription(hint);field.setHintTextColor(0xff899d9e);field.setText(value);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(url?android.text.InputType.TYPE_TEXT_VARIATION_URI:android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        field.setPadding(dp(10),0,dp(10),0);
        GradientDrawable background=new GradientDrawable();background.setColor(Color.rgb(24,37,40));background.setCornerRadius(dp(14));background.setStroke(dp(1),Color.rgb(52,73,76));field.setBackground(background);return field;
    }
    private void editBookmark(String original,Runnable refresh){
        org.json.JSONArray items=readBookmarks();JSONObject found=null;
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&original.equals(item.optString("url")))found=item;}
        if(found==null)return;
        Dialog dialog=new Dialog(this);bookmarkAction=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        EditText title=bookmarkField(found.optString("title"),"标题",false),url=bookmarkField(original,"网址",true);
        fields.addView(title,new LinearLayout.LayoutParams(-1,dp(42)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(42));lp.topMargin=dp(10);fields.addView(url,lp);
        LinearLayout actions=singleClose(dialog);
        actions.addView(plainIcon(R.drawable.ic_check,"保存",()->{
            String target=url.getText().toString().trim();if(!target.contains("://"))target="https://"+target;
            try{java.net.URI parsed=new java.net.URI(target);if(!bookmarkUrl(target)||parsed.getHost()==null||parsed.getRawUserInfo()!=null)throw new IllegalArgumentException();}
            catch(Exception error){url.setError("请输入有效网址");return;}
            org.json.JSONArray latest=readBookmarks();
            for(int i=0;i<latest.length();i++){JSONObject item=latest.optJSONObject(i);if(item!=null&&!original.equals(item.optString("url"))&&target.equals(item.optString("url"))){url.setError("该网址已在书签中");return;}}
            try{
                for(int i=0;i<latest.length();i++){JSONObject item=latest.optJSONObject(i);if(item!=null&&original.equals(item.optString("url"))){item.put("url",target);String name=title.getText().toString().trim();item.put("title",name.isEmpty()?Uri.parse(target).getHost():name);}}
                prefs.edit().putString("bookmarks",latest.toString()).apply();dialog.dismiss();refresh.run();
            }catch(org.json.JSONException error){toast("保存失败");}
        }),new LinearLayout.LayoutParams(dp(40),dp(40)));
        dialog.setOnDismissListener(d->{if(bookmarkAction==dialog)bookmarkAction=null;});
        showPinnedDialog(dialog,"编辑书签",fields,actions,true);
    }
    private org.json.JSONArray readBookmarks(){
        try{return new org.json.JSONArray(prefs.getString("bookmarks","[]"));}
        catch(org.json.JSONException error){return new org.json.JSONArray();}
    }
    private boolean bookmarkUrl(String url){
        if(url==null)return false;
        Uri uri=Uri.parse(url);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null;
    }
    private View bookmarkRow(JSONObject entry,Runnable open){
        String url=entry.optString("url"),title=entry.optString("title",Uri.parse(url).getHost());
        String trimmed=title.trim();String initial=trimmed.isEmpty()?"?":trimmed.substring(0,trimmed.offsetByCodePoints(0,1));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding((dp(44)-dp(26))/2,0,dp(12),0);
        GradientDrawable fill=new GradientDrawable();fill.setColor(Color.rgb(35,47,51));fill.setCornerRadius(dp(22));
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x407DE8CA),fill,null));
        row.setOnClickListener(v->open.run());row.setContentDescription(title+"，"+url);
        return row;
    }
    private void populateBookmarkRow(LinearLayout row,JSONObject entry){
        String url=entry.optString("url"),title=entry.optString("title",Uri.parse(url).getHost());
        String trimmed=title.trim();String initial=trimmed.isEmpty()?"?":trimmed.substring(0,trimmed.offsetByCodePoints(0,1));
        ImageView icon=new ImageView(this){
            private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas){
                if(getDrawable()!=null){super.onDraw(canvas);return;}
                paint.setColor(Color.rgb(33,65,61));paint.setTextSize(dp(14));paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                canvas.drawText(initial,getWidth()/2f,getHeight()/2f-(paint.ascent()+paint.descent())/2,paint);
            }
        };
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setPadding(dp(4),dp(4),dp(4),dp(4));
        GradientDrawable badge=new GradientDrawable();badge.setColor(0xffdcece6);badge.setShape(GradientDrawable.OVAL);icon.setBackground(badge);icon.setClipToOutline(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));
        TextView label=text(title,13);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,-1,1);textParams.leftMargin=dp(10);row.addView(label,textParams);
        String source=entry.optString("icon");if(!bookmarkUrl(source))source=Uri.parse(url).buildUpon().path("/favicon.ico").clearQuery().fragment(null).build().toString();
        bookmarkIcons.load(url,source,icon);
    }
    private void showBookmarks(){
        if(bookmarksEditor!=null&&bookmarksEditor.isShowing())return;
        Dialog dialog=new Dialog(this);bookmarksEditor=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setGravity(Gravity.CENTER_HORIZONTAL);
        Runnable render=new Runnable(){@Override public void run(){
            bookmarkIcons.releaseViews();list.removeAllViews();

            Button add=button("＋ 添加当前页",()->{
                String url=current;
                if(!bookmarkUrl(url)){toast("当前页面无法添加");return;}
                org.json.JSONArray items=readBookmarks();
                for(int i=0;i<items.length();i++)if(url.equals(items.optJSONObject(i)==null?null:items.optJSONObject(i).optString("url"))){toast("已在书签中");return;}
                try{
                    JSONObject entry=new JSONObject();entry.put("url",url);entry.put("title",pageTitle.isEmpty()?Uri.parse(url).getHost():pageTitle);if(!pageIcon.isEmpty())entry.put("icon",pageIcon);
                    items.put(entry);prefs.edit().putString("bookmarks",items.toString()).apply();toast("已添加书签");run();
                }catch(org.json.JSONException error){toast("添加失败");}
            });
            add.setTextSize(13);LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(-1,dp(40));addParams.topMargin=dp(8);list.addView(add,addParams);
            org.json.JSONArray items=readBookmarks();
            java.util.List<View> sortable=new ArrayList<>();
            if(items.length()==0){TextView empty=text("暂无书签",13);empty.setGravity(Gravity.CENTER);list.addView(empty,new LinearLayout.LayoutParams(-1,dp(48)));}
            for(int i=0;i<items.length();i++){
                JSONObject entry=items.optJSONObject(i);if(entry==null)continue;
                String url=entry.optString("url");if(!bookmarkUrl(url))continue;
                View row=bookmarkRow(entry,()->{dialog.dismiss();hideMenu();hideError();if(configured)session.loadUri(url);});
                row.setTag(entry);sortable.add(row);
                LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,dp(44));rowParams.topMargin=dp(7);list.addView(row,rowParams);
            }
            DragReorder reorder=new DragReorder(list,sortable,()->dialog.isShowing(),ordered->{
                org.json.JSONArray updated=new org.json.JSONArray();for(View row:ordered)updated.put(row.getTag());
                prefs.edit().putString("bookmarks",updated.toString()).apply();run();
            });
            for(View row:sortable)reorder.bind(row,()->bookmarkOptions(((JSONObject)row.getTag()).optString("url"),this));

        }};
        final Rect visible=new Rect();
        Runnable refreshVisible=()->{
            if(!dialog.isShowing())return;
            for(int i=0;i<list.getChildCount();i++){
                View child=list.getChildAt(i);if(!(child.getTag() instanceof JSONObject))continue;
                LinearLayout row=(LinearLayout)child;
                if(row.getLocalVisibleRect(visible)){
                    if(row.getChildCount()==0)populateBookmarkRow(row,(JSONObject)row.getTag());
                }else if(row.getChildCount()>0){bookmarkIcons.releaseView((ImageView)row.getChildAt(0));row.removeAllViews();}
            }
        };
        list.getViewTreeObserver().addOnScrollChangedListener(refreshVisible::run);
        list.getViewTreeObserver().addOnGlobalLayoutListener(refreshVisible::run);
        render.run();dialog.setOnDismissListener(d->{bookmarkIcons.releaseViews();try{JSONObject stop=new JSONObject();stop.put("type","faviconClear");sendVault(stop);}catch(Exception ignored){}if(bookmarkAction!=null)bookmarkAction.dismiss();if(bookmarksEditor==dialog)bookmarksEditor=null;});showPinnedDialog(dialog,"书签",list,singleClose(dialog),false);
    }

    private void hideMenu() {
        if(menu!=null){menu.animate().cancel();root.removeView(menu);menu=null;}
        if(menuTopCover!=null){root.removeView(menuTopCover);menuTopCover=null;}
        zoomControl=null;heightControl=null;clearCacheButton=null;adjustingRange=false;
    }
    private void goBack() {
        if(menu!=null){hideMenu();return;}
        root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        if(canBack)session.goBack();else moveTaskToBack(true);
    }
    @Override public void onBackPressed() { goBack(); }
    private void showError(String message,Runnable retry) {
        if(isFinishing()||isDestroyed())return;finishStartup();hideError();progress.hide();
        error=new LinearLayout(this);error.setOrientation(LinearLayout.VERTICAL);error.setGravity(Gravity.CENTER);error.setPadding(dp(32),dp(24),dp(32),dp(24));error.setBackgroundColor(BG);
        TextView t=text(message,16);t.setGravity(Gravity.CENTER);error.addView(t);addButton(error,"重试",retry);addButton(error,"设置",this::showMenu);
        root.addView(error,new android.widget.FrameLayout.LayoutParams(-1,-1));if(menu!=null)menu.bringToFront();
    }
    private void hideError(){if(error!=null){root.removeView(error);error=null;}}
    TextView text(String label,int size){TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(FG);return t;}
    Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(FG);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(6),0,dp(6),0);GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(35,47,51));bg.setCornerRadius(dp(22));b.setBackground(bg);b.setOnClickListener(v -> action.run());return b;}
    private void addButton(LinearLayout parent,String text,Runnable action){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(46));lp.topMargin=dp(8);parent.addView(button(text,action),lp);}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);if(bookmarkIcons!=null)bookmarkIcons.trimMemory();
    }
    @Override protected void onPause(){if(fileUi!=null)fileUi.pause();hideVaultFill();if(startupAnimation!=null)startupAnimation.stop();root.cancelPull();super.onPause();if(session!=null)session.setActive(false);}
    @Override protected void onResume(){super.onResume();if(fileUi!=null)fileUi.resume();if(startupAnimation!=null)startupAnimation.start();if(session!=null)session.setActive(true);}
    @Override protected void onDestroy(){
        if(fileUi!=null){fileUi.close();fileUi=null;}
        bookmarkIcons.close();VaultFillTransfer.clear();finishStartup();progress.hide();root.cancelPull();hideMenu();
        if(infoDialog!=null)infoDialog.dismiss();
        if(homeEditor!=null)homeEditor.dismiss();if(bookmarksEditor!=null)bookmarksEditor.dismiss();
        if(bookmarkAction!=null)bookmarkAction.dismiss();if(clearCacheDialog!=null)clearCacheDialog.dismiss();
        if(browserPrompts!=null){browserPrompts.dismissAll();browserPrompts=null;}
        handler.removeCallbacksAndMessages(null);
        if(port!=null){port.setDelegate(IDLE_PORT);port=null;}
        if(attachedExtension!=null){attachedExtension.setMessageDelegate(IDLE_EXTENSION,"goapp");attachedExtension=null;}
        if(session!=null){browser.releaseSession();session.close();session=null;}
        super.onDestroy();
    }

    /** Draw over the page without changing its viewport or consuming touches. */
    private final class EdgeProgress extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF ring=new android.graphics.RectF();
        private float displayed,target;
        private boolean loading;
        private android.animation.ValueAnimator motion;
        private final Runnable reveal=()->{if(loading)setVisibility(View.VISIBLE);};
        private final Runnable conceal=()->setVisibility(View.GONE);
        EdgeProgress(){
            super(MainActivity.this);setVisibility(View.GONE);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }
        private void stopMotion(){if(motion!=null){motion.cancel();motion=null;}}
        void begin(){
            hide();loading=true;displayed=.02f;target=.02f;
            // Cached pages often finish before a visible loading indicator is needed.
            postDelayed(reveal,100);
        }
        void advance(int percent){
            if(!loading)return;
            float next=Math.max(target,Math.min(.97f,Math.max(0,percent)/100f));
            if(next==target)return;target=next;stopMotion();
            motion=android.animation.ValueAnimator.ofFloat(displayed,target);motion.setDuration(120);
            motion.setInterpolator(new android.view.animation.DecelerateInterpolator());
            motion.addUpdateListener(a->{displayed=(float)a.getAnimatedValue();invalidate();});motion.start();
        }
        void finish(boolean success){
            if(!loading)return;loading=false;removeCallbacks(reveal);stopMotion();
            if(success&&getVisibility()==View.VISIBLE){displayed=1f;invalidate();postDelayed(conceal,150);}
            else hide();
        }
        void hide(){loading=false;removeCallbacks(reveal);removeCallbacks(conceal);stopMotion();setVisibility(View.GONE);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
            super.onSizeChanged(w,h,oldw,oldh);
            float radius=Math.max(0,Math.min(w,h)/2f-dp(3));
            ring.set(w/2f-radius,h/2f-radius,w/2f+radius,h/2f+radius);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas){
            super.onDraw(canvas);paint.setStrokeWidth(dp(2));
            paint.setColor(0x287DE8CA);canvas.drawOval(ring,paint);
            paint.setColor(ACCENT);canvas.drawArc(ring,-90,360*displayed,false,paint);
        }
        @Override protected void onDetachedFromWindow(){hide();super.onDetachedFromWindow();}
    }

    /** Scroll normally until the menu bottom, then stretch upward to dismiss. */
    private final class MenuScroll extends ScrollView {
        private float originX,originY,distance;
        private boolean eligible,dragging,blocked,armed,closing;
        private final int slop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private final android.graphics.Path outline=new android.graphics.Path();
        MenuScroll(){super(MainActivity.this);setOverScrollMode(OVER_SCROLL_NEVER);setBackgroundColor(BG);animate().setUpdateListener(animation->updateTopCover());}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
            super.onSizeChanged(w,h,oldw,oldh);outline.reset();
            float radius=Math.min(w,h)/2f,cy=h-radius;
            outline.moveTo(-w,-h);outline.lineTo(w*2f,-h);outline.lineTo(w*2f,cy);
            outline.lineTo(w/2f+radius,cy);
            outline.arcTo(w/2f-radius,cy-radius,w/2f+radius,cy+radius,0,180,false);
            outline.lineTo(-w,cy);outline.close();
            updateTopCover();
        }
        private void updateTopCover(){
            if(menuTopCover!=null&&root.getHeight()>0){
                float center=getHeight()-Math.min(getWidth(),getHeight())/2f;
                menuTopCover.setScaleY(Math.max(0,Math.min(1,(getTranslationY()+center)/root.getHeight())));
            }
        }
        @Override public void setTranslationY(float value){super.setTranslationY(value);updateTopCover();}
        @Override public void draw(android.graphics.Canvas canvas){
            int save=canvas.save();canvas.clipPath(outline);super.draw(canvas);canvas.restoreToCount(save);
        }
        private void updateDrag(MotionEvent e){
            distance=Math.max(0,originY-e.getY()-slop);
            boolean ready=distance>=dp(96);
            if(ready&&!armed)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            armed=ready;
            float stretch=dp(104)*(1f-(float)Math.exp(-distance/dp(150)));
            // Move the full-size circular sheet; never scale its content.
            setTranslationY(-stretch);
        }
        void resetStretch(){
            animate().cancel();dragging=false;armed=false;closing=false;blocked=true;
            setTranslationY(0);
        }
        private void settle(boolean dismiss){
            dragging=false;armed=false;
            if(dismiss){
                closing=true;
                animate().translationY(-Math.max(1,getHeight())).setDuration(280)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(()->{if(menu==this)hideMenu();}).start();
            }else{
                animate().translationY(0).setDuration(430)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                    .start();
            }
        }
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            if(closing)return true;
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                float radius=Math.min(getWidth(),getHeight())/2f,dx=e.getX()-getWidth()/2f,dy=e.getY()-(getHeight()-radius);
                if(dy>0&&dx*dx+dy*dy>radius*radius)return false;
                // Finish a previous settling animation before accepting a fresh gesture.
                animate().cancel();setTranslationY(0);
                originX=e.getRawX();originY=e.getRawY();distance=0;
                eligible=!canScrollVertically(1);dragging=false;blocked=false;armed=false;
            }
            if(e.getPointerCount()>1||action==MotionEvent.ACTION_CANCEL){
                blocked=true;if(dragging){settle(false);return true;}
            }
            if(action==MotionEvent.ACTION_MOVE&&!blocked&&!adjustingRange){
                // Raw coordinates remain stable while this view moves under the finger.
                float dx=e.getRawX()-originX,dy=e.getRawY()-originY;
                if(!eligible&&!canScrollVertically(1)){eligible=true;originY=e.getRawY();dy=0;}
                if(!dragging&&eligible&&-dy>slop&&-dy>Math.abs(dx)*1.5f){
                    MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);cancel.recycle();
                    dragging=true;getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if(dragging){
                // Use an event copy so the distance calculation is independent of translation.
                MotionEvent raw=MotionEvent.obtain(e);raw.setLocation(e.getRawX(),e.getRawY());
                if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)updateDrag(raw);
                raw.recycle();
                if(action==MotionEvent.ACTION_UP)settle(armed);
                return true;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** Elastic overlay: the Gecko surface and CSS viewport never change size. */
    private final class PullCurtain extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private float depth, fingerX, fraction;
        private android.animation.ValueAnimator rebound;
        PullCurtain(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void update(float distance,float x){
            stop();
            // Increasing resistance, with a bounded visual stretch even on long drags.
            depth=dp(104)*(1f-(float)Math.exp(-Math.max(0,distance)/dp(150)));
            fingerX=x;fraction=Math.min(1f,Math.max(0,distance)/dp(96));invalidate();
        }
        void stop(){if(rebound!=null){rebound.cancel();rebound=null;}}
        void reset(){stop();depth=0;fraction=0;invalidate();}
        void release(){
            stop();final float initial=depth;
            rebound=android.animation.ValueAnimator.ofFloat(0f,1f);
            rebound.setDuration(430);rebound.setInterpolator(new android.view.animation.LinearInterpolator());
            rebound.addUpdateListener(a->{float t=(float)a.getAnimatedValue();depth=Math.max(0,initial*(float)(Math.exp(-6*t)*Math.cos(10*t)));if(t>=1)depth=0;invalidate();});
            rebound.start();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas){
            super.onDraw(canvas);if(depth<0.5f)return;
            float width=getWidth(), center=Math.max(width*.2f,Math.min(width*.8f,fingerX));
            float shoulder=depth*.38f;
            path.reset();path.moveTo(0,0);path.lineTo(width,0);path.lineTo(width,shoulder);
            path.cubicTo(width*.8f,shoulder,center+width*.22f,depth,center,depth);
            path.cubicTo(center-width*.22f,depth,width*.2f,shoulder,0,shoulder);path.close();
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(BG);canvas.drawPath(path,paint);
            float cy=depth*.42f, radius=dp(10);
            if(depth>dp(24)){
                paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setColor(fraction>=1?ACCENT:Color.rgb(137,167,159));
                canvas.drawArc(getWidth()/2f-radius,cy-radius,getWidth()/2f+radius,cy+radius,-90,360*fraction,false,paint);
                float half=dp(4), middle=getWidth()/2f;
                canvas.drawLine(middle-half,cy-dp(1),middle,cy+dp(3),paint);
                canvas.drawLine(middle,cy+dp(3),middle+half,cy-dp(1),paint);
            }
        }
    }

    private final class GestureFrame extends FrameLayout {
        private float downX,downY,distance;
        private boolean top,edge,rightEdge,consumed,disabled,pulling,armed,slidingSpace,leftSwipe,panningViewport;
        private float startOffset,startPan;
        private final int slop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        GestureFrame(){super(MainActivity.this);}
        void cancelPull(){if(menu instanceof MenuScroll)((MenuScroll)menu).resetStretch();pulling=false;armed=false;disabled=true;distance=0;if(pullCurtain!=null)pullCurtain.reset();}
        private void moveRoundSpace(float dy){
            float offset=startOffset+dy;
            if(!pageAtBottom||viewportPan<maxViewportPan()-1)offset=Math.max(0,offset);
            if(!top)offset=Math.min(0,offset);
            setRoundOffset(offset);
        }
        private void drag(MotionEvent e){
            distance=Math.max(0,e.getY()-downY-slop);
            boolean ready=distance>=dp(96);
            if(ready&&!armed)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            armed=ready;pullCurtain.update(distance,e.getX());
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent e){
            if(menu!=null||startupOverlay!=null)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                pullCurtain.reset();downX=e.getX();downY=e.getY();distance=0;
                top=scrollY<=1&&viewportPan<=1;
                float edgeWidth=getWidth()*.08f;
                edge=downX<=edgeWidth;rightEdge=downX>=getWidth()-edgeWidth;
                consumed=false;disabled=false;pulling=false;armed=false;slidingSpace=false;leftSwipe=false;panningViewport=false;startPan=viewportPan;startOffset=roundOffset;
            }
            if(e.getPointerCount()>1)disabled=true;
            if(!disabled&&e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float dx=e.getX()-downX,dy=e.getY()-downY;
                if(edge&&dx>dp(48)&&dx>Math.abs(dy)*1.5f){consumed=true;post(MainActivity.this::goBack);return true;}
                if(rightEdge&&dx < -dp(48)&&-dx>Math.abs(dy)*1.5f){leftSwipe=true;consumed=true;return true;}
                if(roundSpace&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.5f){
                    boolean moveSpace=roundOffset!=0 || (top&&dy>0) || (pageAtBottom&&viewportPan>=maxViewportPan()-1&&dy<0);
                    // At the fully exposed header a further pull opens the existing menu.
                    if(roundOffset>=getHeight()/2f-1&&dy>0)moveSpace=false;
                    if(moveSpace){slidingSpace=true;consumed=true;moveRoundSpace(dy);return true;}
                }
                if(roundOffset==0&&!browser.shouldPinOnScreen()&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.5f&&
                    ((dy<0&&viewportPan<maxViewportPan()-1)||(dy>0&&viewportPan>1))){
                    panningViewport=true;consumed=true;setViewportPan(startPan-dy);return true;
                }
                if(top&&scrollY<=1&&dy>slop&&dy>Math.abs(dx)*1.5f){pulling=true;consumed=true;drag(e);return true;}
                // Only edge-origin horizontal drags remain eligible for application gestures.
                if(!edge&&!rightEdge&&Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)){disabled=true;return false;}
                if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.5f)disabled=true;
            }
            return false;
        }
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!consumed)return super.onTouchEvent(e);
            if(e.getPointerCount()>1||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                disabled=true;pulling=false;armed=false;slidingSpace=false;leftSwipe=false;panningViewport=false;pullCurtain.release();return true;
            }
            if(leftSwipe&&!disabled&&e.getActionMasked()==MotionEvent.ACTION_UP){
                if(e.getX()-downX < -dp(48)&&Math.abs(e.getY()-downY)<Math.abs(e.getX()-downX)/1.5f)toggleRoundSpace();
                leftSwipe=false;
            }
            if(panningViewport&&!disabled){
                setViewportPan(startPan-(e.getY()-downY));
                if(e.getActionMasked()==MotionEvent.ACTION_UP)panningViewport=false;
                return true;
            }
            if(slidingSpace&&!disabled){
                moveRoundSpace(e.getY()-downY);
                if(e.getActionMasked()==MotionEvent.ACTION_UP)slidingSpace=false;
                return true;
            }
            if(pulling&&!disabled){
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE)drag(e);
                if(e.getActionMasked()==MotionEvent.ACTION_UP){
                    drag(e);boolean open=armed;pulling=false;armed=false;
                    if(open){
                        pullCurtain.reset();showMenu();
                        menu.setTranslationY(-Math.max(1,getHeight()));
                        menu.animate().translationY(0).setDuration(360)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(.65f)).start();
                    }else pullCurtain.release();
                }
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
            return true;
        }
    }
}
