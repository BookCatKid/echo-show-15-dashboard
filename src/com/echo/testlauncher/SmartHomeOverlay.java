package com.echo.testlauncher;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** A native household control overlay using the existing HA client. */
final class SmartHomeOverlay {
    private static final int BG=0xff22232c, TILE=0xff32343e, LIGHT=0xffffd49f,
            CLIMATE=0xffa9caff, FAN=0xffa5e5ce, INK=DashboardPopup.INK, MUTED=DashboardPopup.MUTED;
    private static final String WHF="light.shelly0110dimg3_80b54e25b874", TIMER="timer.bedtime",
            HVAC="climate.thermostat", SHADE="cover.somfy_bridge_somfy_shade";
    private static final String[][] INSIDE={
        {"switch.poang_chair_light","Poang Chair Light"},{"switch.credenza_lamp","Credenza Light"},
        {"switch.rf_bridge_downstairs_lights","Dining Lights"},{"switch.rf_bridge_smart_hab_lights","Bedroom Light"},
        {"switch.jenns_lamp","Jenn’s Lamp"},{"switch.bathroom_lamp","Bathroom Light"},
        {"switch.yankees_light","Yankee’s Light"},{"light.simons_lights","Simon’s Lights"},
        {"switch.snake_light","Snake Light"}
    };
    private static final String[][] OUTSIDE={
        {"light.front_door_left_light","Front Door Left"},{"light.front_door_right_light","Front Door Right"},
        {"switch.tp_link_smart_plug_1050_bush_lights","Bush Lights"},{"switch.pool_light","Pool Light"}
    };
    private final Activity activity;private final HaClient client;private final DashboardPopup popup;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private final Set<String> pendingActions=new HashSet<>();
    private final Map<String,JSONObject> states=new HashMap<>();
    private final Set<String> ids=new HashSet<>();
    private final ScrollView scroll;private final LinearLayout content;
    private boolean closed,busy,dragging;private String signature="";
    private final HaStateFeed feed;
    private final HaStateFeed.Listener listener=this::liveChanged;
    private boolean redrawPending;
    private final Runnable redraw=()->{
        if(closed)return;
        if(dragging){redrawPending=true;return;}
        redrawPending=false;
        String next=fingerprint();if(!next.equals(signature)){signature=next;render();}
    };
    SmartHomeOverlay(Activity activity,HaClient client,HaStateFeed feed){
        this.feed=feed;
        this.activity=activity;this.client=client;popup=new DashboardPopup(activity,"Smart Home",BG,LIGHT);
        for(String[][] group:new String[][][]{INSIDE,OUTSIDE})for(String[] item:group)ids.add(item[0]);
        Collections.addAll(ids,WHF,TIMER,HVAC,SHADE,"sensor.thermostat_temperature","sensor.thermostat_humidity","sensor.downstairs_temperature","sensor.downstairs_outside_temperature");
        scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(true);scroll.setScrollbarFadingEnabled(false);
        content=popup.column();scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        popup.body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        popup.actions.setPadding(0,popup.dp(16),0,0);
        popup.actions.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        popup.actions.addView(popup.button("Refresh",LIGHT,TILE,()->{if(feed!=null)feed.requestSnapshot();refresh(true);}));
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(-2,-2);closeParams.leftMargin=popup.dp(16);
        popup.actions.addView(popup.button("Close",INK,TILE,()->popup.dismiss()),closeParams);
        popup.setOnDismissListener(d->{closed=true;if(feed!=null)feed.remove(listener);ui.removeCallbacksAndMessages(null);io.shutdown();commands.shutdown();});
    }
    void open(){
        popup.open(false,2000);Window w=popup.getWindow();android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
        w.getWindowManager().getDefaultDisplay().getMetrics(dm);
        w.setLayout(dm.widthPixels-popup.dp(48),dm.heightPixels-popup.dp(64));
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        content.addView(label("Loading devices…",26,MUTED,false));if(feed!=null)feed.add(listener);refresh(true);
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView v=popup.label(s,size,color,bold);
        try{v.setTypeface(Typeface.createFromFile("/system/fonts/Amazon-Ember-"+(bold?"Medium":"Regular")+".ttf"));}catch(Exception ignored){}
        return v;
    }
    private void liveChanged(Set<String> changed,boolean reset){
        if(closed)return;
        if(feed==null||!feed.ready()){refresh(false);return;}
        if(reset||!Collections.disjoint(ids,changed)){
            copyLive();
            ui.removeCallbacks(redraw);ui.post(redraw);
        }
    }
    private void copyLive(){
        states.clear();for(String id:ids){JSONObject value=feed.get(id);if(value!=null)states.put(id,value);}
    }
    private void refresh(boolean force){
        if(closed)return;
        ui.removeCallbacks(poll);
        if(feed!=null&&feed.ready()){
            copyLive();if(force)signature="";redraw.run();ui.postDelayed(poll,6000);return;
        }
        if(busy||dragging){ui.postDelayed(poll,1000);return;}
        busy=true;
        io.execute(()->{try{
            JSONArray array=client.getStates();Map<String,JSONObject> result=new HashMap<>();
            for(int i=0;i<array.length();i++){JSONObject state=array.optJSONObject(i);if(state!=null&&ids.contains(state.optString("entity_id")))result.put(state.optString("entity_id"),state);}
            ui.post(()->{if(closed)return;busy=false;if(feed!=null&&feed.ready())copyLive();else{states.clear();states.putAll(result);}
                if(force)signature="";redraw.run();ui.postDelayed(poll,3000);
            });
        }catch(Exception e){ui.post(()->{if(closed)return;busy=false;if(feed!=null&&feed.ready())copyLive();else states.clear();signature="";redraw.run();ui.postDelayed(poll,3000);});}});
    }
    private final Runnable poll=()->refresh(false);
    private String fingerprint(){
        StringBuilder b=new StringBuilder();
        for(String id:new TreeSet<>(ids)){JSONObject s=states.get(id);if(s!=null)b.append(id).append(s.optString("state")).append(s.optJSONObject("attributes"));}
        if("active".equals(state(TIMER)))b.append(System.currentTimeMillis()/60000);
        return b.toString();
    }
    private JSONObject attr(String id){JSONObject s=states.get(id);JSONObject a=s==null?null:s.optJSONObject("attributes");return a==null?new JSONObject():a;}
    private String state(String id){JSONObject s=states.get(id);return s==null?"unavailable":s.optString("state","unknown");}
    private boolean available(String id){String s=state(id);return !s.equals("unavailable")&&!s.equals("unknown");}
    private String domain(String id){return id.substring(0,id.indexOf('.'));}
    private void service(String id,String action,JSONObject extras){
        if(closed||!available(id))return;
        // Different devices can be queued immediately, with duplicate taps on the same
        // pending device blocked until HA has acknowledged the previous request.
        if(!pendingActions.add(id))return;
        try{JSONObject data=extras==null?new JSONObject():extras;data.put("entity_id",id);
            commands.execute(()->{String error=null;try{client.call(domain(id),action,data,false);}catch(Exception e){error="Action failed";}
                final String err=error;ui.post(()->{pendingActions.remove(id);if(closed)return;
                    if(err!=null)Toast.makeText(activity,err,Toast.LENGTH_SHORT).show();
                    if(feed==null||!feed.ready())refresh(true);else{copyLive();redraw.run();}
                });});
        }catch(JSONException e){pendingActions.remove(id);}
    }
    private JSONObject args(String key,Object value){JSONObject o=new JSONObject();try{o.put(key,value);}catch(JSONException ignored){}return o;}
    private void toggle(String id){service(id,"on".equals(state(id))?"turn_off":"turn_on",null);}
    private void render(){
        int y=scroll.getScrollY();content.removeAllViews();
        section("Indoor lights",LIGHT);lights(INSIDE);
        section("Outdoor lights",LIGHT);lights(OUTSIDE);
        section("Whole house fan",FAN);fan();
        section("Shade",LIGHT);shade();
        section("Climate",CLIMATE);climate();
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void section(String title,int color){popup.gap(content,18);content.addView(label(title,26,color,true));popup.gap(content,10);}
    private LinearLayout tile(int bg){LinearLayout t=popup.column();t.setPadding(popup.dp(18),popup.dp(16),popup.dp(18),popup.dp(16));t.setBackground(popup.shape(bg,16));return t;}
    private void fill(LinearLayout row,View v,int margin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=popup.dp(margin);row.addView(v,p);
    }
    private void addTile(LinearLayout row){content.addView(row,new LinearLayout.LayoutParams(-1,-2));popup.gap(content,10);}
    private String capitalize(String s){return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1).replace('_',' ');}
    private boolean dimmable(String id){
        if(!id.startsWith("light."))return false;
        JSONObject a=attr(id);JSONArray modes=a.optJSONArray("supported_color_modes");
        if(modes!=null)for(int i=0;i<modes.length();i++){
            String mode=modes.optString(i);
            if(Arrays.asList("brightness","color_temp","hs","xy","rgb","rgbw","rgbww","white").contains(mode))return true;
        }
        return !a.isNull("brightness");
    }
    private void lights(String[][] group){
        int cols=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?4:3;
        for(int i=0;i<group.length;i+=cols){
            LinearLayout row=popup.row();row.setGravity(Gravity.TOP);
            for(int j=0;j<cols;j++){
                if(i+j>=group.length){fill(row,new View(activity),j==cols-1?0:10);continue;}
                String id=group[i+j][0],name=group[i+j][1];
                boolean on="on".equals(state(id)),ok=available(id),dim=dimmable(id);
                int pct=on?Math.max(1,Math.round(attr(id).optInt("brightness",255)*100f/255)):0;
                LinearLayout card=tile(on?0xff5a4938:TILE);
                LinearLayout power=popup.column(),top=popup.row();ImageView icon=new ImageView(activity);
                int res=activity.getResources().getIdentifier("ic_lightbulb","drawable",activity.getPackageName());
                if(res!=0){icon.setImageResource(res);icon.setColorFilter(on?LIGHT:MUTED);}
                top.addView(icon,new LinearLayout.LayoutParams(popup.dp(36),popup.dp(36)));
                TextView nameView=label(name,22,INK,true);nameView.setMaxLines(2);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=popup.dp(12);top.addView(nameView,lp);power.addView(top);
                popup.gap(power,8);
                LinearLayout statusRow=popup.row();statusRow.addView(label(ok?(on?"On":"Off"):"Unavailable",19,on?LIGHT:MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
                TextView value=label(ok&&dim?(on&&attr(id).isNull("brightness")?"—":pct+"%"):"",21,on?LIGHT:MUTED,true);
                statusRow.addView(value);power.addView(statusRow);card.addView(power,new LinearLayout.LayoutParams(-1,-2));
                power.setMinimumHeight(popup.dp(68));power.setContentDescription(name+", "+(ok?(on?"on":"off"):"unavailable"));
                power.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),popup.shape(Color.TRANSPARENT,12),popup.shape(Color.WHITE,12)));
                power.setEnabled(ok);power.setOnClickListener(v->toggle(id));
                card.setMinimumHeight(popup.dp(118));
                if(dim){
                    popup.gap(card,4);
                    if(ok)percentSlider(card,name+" brightness",pct,LIGHT,value,n->{
                        if(n==pct)return;
                        if(n==0)service(id,"turn_off",null);else service(id,"turn_on",args("brightness_pct",n));
                    });
                    else card.addView(label("Brightness unavailable",18,MUTED,false));
                }else{
                    card.setContentDescription(name+", "+(ok?(on?"on":"off"):"unavailable"));
                    card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),popup.shape(on?0xff5a4938:TILE,16),popup.shape(Color.WHITE,16)));
                    card.setEnabled(ok);card.setOnClickListener(v->toggle(id));
                }
                fill(row,card,j==cols-1?0:10);
            }addTile(row);
        }
    }
    private static final int COOL=0xffa9caff, HEAT=0xffffb28b, CONTROL=0xff41434f;
    private Button control(String name,int fg,int bg,int height,Runnable action){
        Button b=popup.button(name,fg,bg,action);
        b.setTextSize(23);b.setMinHeight(popup.dp(height));b.setMinimumHeight(popup.dp(height));
        b.setPadding(popup.dp(8),0,popup.dp(8),0);
        try{b.setTypeface(Typeface.createFromFile("/system/fonts/Amazon-Ember-Medium.ttf"));}catch(Exception ignored){}
        return b;
    }
    private void equal(LinearLayout row,View v,int height,int margin){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,height<0?height:popup.dp(height),1);
        lp.rightMargin=popup.dp(margin);row.addView(v,lp);
    }
    private String degrees(double n){
        return Double.isFinite(n)?String.format(Locale.US,n==Math.rint(n)?"%.0f°":"%.1f°",n):"—";
    }
    private String modeLabel(String mode){
        return mode.equals("heat_cool")?"Auto":capitalize(mode);
    }
    private void climate(){
        LinearLayout t=tile(0xff273346);JSONObject a=attr(HVAC);
        if(!available(HVAC)){t.addView(label("Thermostat unavailable",24,MUTED,false));addTile(t);return;}
        String mode=state(HVAC),action=a.optString("hvac_action",mode);
        int activeColor=mode.equals("heat")?HEAT:COOL;
        double current=a.optDouble("current_temperature",Double.NaN);
        double humidity=a.optDouble("current_humidity",Double.NaN);
        if(!Double.isFinite(humidity))try{humidity=Double.parseDouble(state("sensor.thermostat_humidity"));}catch(Exception ignored){}
        LinearLayout header=popup.row(),reading=popup.column(),details=popup.column();
        reading.addView(label("Indoor temperature",20,MUTED,false));
        reading.addView(label(degrees(current),64,INK,true));
        header.addView(reading,new LinearLayout.LayoutParams(0,-2,1));
        TextView hvac=label(capitalize(action),29,action.equals("heating")?HEAT:action.equals("cooling")?COOL:MUTED,true);hvac.setGravity(Gravity.END);
        details.addView(hvac);
        if(Double.isFinite(humidity)){TextView h=label(String.format(Locale.US,"%.0f%% humidity",humidity),22,MUTED,false);h.setGravity(Gravity.END);details.addView(h);}
        header.addView(details);t.addView(header);
        popup.gap(t,16);
        JSONArray modes=a.optJSONArray("hvac_modes");LinearLayout buttons=popup.row();
        if(modes!=null)for(int i=0;i<modes.length();i++){
            String m=modes.optString(i);boolean selected=m.equals(mode);int selectedColor=m.equals("heat")?HEAT:m.equals("off")?MUTED:COOL;
            equal(buttons,control(modeLabel(m),selected?BG:INK,selected?selectedColor:0xff35445b,62,()->service(HVAC,"set_hvac_mode",args("hvac_mode",m))),62,i==modes.length()-1?0:8);
        }
        t.addView(buttons);
        if(mode.equals("heat_cool")){
            double low=a.optDouble("target_temp_low",Double.NaN),high=a.optDouble("target_temp_high",Double.NaN);
            if(Double.isFinite(low)&&Double.isFinite(high)){
                popup.gap(t,14);LinearLayout range=popup.row();
                equal(range,targetPanel(a,"target_temp_low","Heat to",low,HEAT),-2,10);
                equal(range,targetPanel(a,"target_temp_high","Cool to",high,COOL),-2,0);t.addView(range);
            }
        }else if(!mode.equals("off")){
            double target=a.optDouble("temperature",Double.NaN);
            if(Double.isFinite(target)){popup.gap(t,14);t.addView(targetPanel(a,"temperature",mode.equals("heat")?"Heat to":mode.equals("cool")?"Cool to":"Target",target,activeColor));}
        }
        addTile(t);
    }
    private LinearLayout targetPanel(JSONObject a,String key,String name,double value,int color){
        double step=a.optDouble("target_temp_step",1);final double delta=Double.isFinite(step)&&step>0?step:1;
        LinearLayout panel=tile(0xff1e293a);panel.setPadding(popup.dp(12),popup.dp(12),popup.dp(12),popup.dp(12));
        TextView title=label(name,22,color,true);title.setGravity(Gravity.CENTER);panel.addView(title);
        popup.gap(panel,8);LinearLayout row=popup.row();
        Button minus=control("−",color,0xff35445b,64,()->target(a,key,value-delta));
        Button plus=control("+",color,0xff35445b,64,()->target(a,key,value+delta));
        minus.setTextSize(34);plus.setTextSize(34);
        minus.setContentDescription("Decrease "+name.toLowerCase(Locale.US));plus.setContentDescription("Increase "+name.toLowerCase(Locale.US));
        minus.setEnabled(value-delta>=a.optDouble("min_temp",Double.NEGATIVE_INFINITY)&&(!key.equals("target_temp_high")||value-delta>a.optDouble("target_temp_low",Double.NEGATIVE_INFINITY)));
        plus.setEnabled(value+delta<=a.optDouble("max_temp",Double.POSITIVE_INFINITY)&&(!key.equals("target_temp_low")||value+delta<a.optDouble("target_temp_high",Double.POSITIVE_INFINITY)));
        row.addView(minus,new LinearLayout.LayoutParams(popup.dp(66),popup.dp(64)));
        TextView num=label(degrees(value),42,color,true);num.setGravity(Gravity.CENTER);
        row.addView(num,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(plus,new LinearLayout.LayoutParams(popup.dp(66),popup.dp(64)));panel.addView(row);
        return panel;
    }
    private void target(JSONObject a,String key,double value){
        double min=a.optDouble("min_temp",value),max=a.optDouble("max_temp",value);value=Math.max(min,Math.min(max,value));
        JSONObject data=args(key,value);
        if(!key.equals("temperature"))try{
            double low=key.equals("target_temp_low")?value:a.optDouble("target_temp_low"),high=key.equals("target_temp_high")?value:a.optDouble("target_temp_high");
            if(!Double.isFinite(low)||!Double.isFinite(high)||low>=high)return;
            data.put("target_temp_low",low).put("target_temp_high",high);
        }catch(JSONException e){return;}
        service(HVAC,"set_temperature",data);
    }
    private interface PercentAction{void run(int value);}
    private void percentSlider(LinearLayout parent,String description,int pct,int color,TextView value,PercentAction action){
        SeekBar bar=new SeekBar(activity);bar.setMax(100);bar.setProgress(Math.max(0,Math.min(100,pct)));
        GradientDrawable track=popup.shape(0xff52605d,7),progress=popup.shape(color,7),thumb=popup.shape(color,16);
        track.setSize(1,popup.dp(14));progress.setSize(1,popup.dp(14));thumb.setSize(popup.dp(32),popup.dp(32));
        LayerDrawable layer=new LayerDrawable(new Drawable[]{track,new ClipDrawable(progress,Gravity.LEFT,ClipDrawable.HORIZONTAL)});
        layer.setId(0,android.R.id.background);layer.setId(1,android.R.id.progress);
        for(int i=0;i<2;i++){layer.setLayerHeight(i,popup.dp(14));layer.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL);}
        bar.setProgressDrawable(layer);bar.setThumb(thumb);bar.setThumbOffset(popup.dp(16));bar.setSplitTrack(false);
        bar.setPadding(popup.dp(18),0,popup.dp(18),0);bar.setContentDescription(description);
        parent.addView(bar,new LinearLayout.LayoutParams(-1,popup.dp(64)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user)value.setText(n+"%");}
            public void onStartTrackingTouch(SeekBar b){dragging=true;b.getParent().requestDisallowInterceptTouchEvent(true);}
            public void onStopTrackingTouch(SeekBar b){dragging=false;b.getParent().requestDisallowInterceptTouchEvent(false);action.run(b.getProgress());if(redrawPending)ui.post(redraw);}
        });
    }
    private void fanSpeed(int speed){if(speed==0)service(WHF,"turn_off",null);else service(WHF,"turn_on",args("brightness_pct",speed));}
    private void fan(){
        LinearLayout t=tile(0xff253d39);boolean on="on".equals(state(WHF)),ok=available(WHF);
        int speed=on?Math.round(attr(WHF).optInt("brightness",255)*100f/255):0;
        LinearLayout head=popup.row();head.addView(label(ok?(on?"Running":"Off"):"Unavailable",36,FAN,true),new LinearLayout.LayoutParams(0,-2,1));
        Button power=control(on?"Turn off":"Turn on",on?BG:FAN,on?FAN:0xff39534d,64,()->toggle(WHF));power.setEnabled(ok);head.addView(power,new LinearLayout.LayoutParams(popup.dp(160),popup.dp(64)));t.addView(head);
        if(ok){
            popup.gap(t,18);LinearLayout row=popup.row();row.addView(label("Fan speed",22,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            TextView value=label(speed+"%",32,FAN,true);row.addView(value);t.addView(row);
            percentSlider(t,"Fan speed",speed,FAN,value,this::fanSpeed);
            LinearLayout presets=popup.row();
            for(int n:new int[]{25,50,75,100})equal(presets,control(n+"%",speed==n?BG:FAN,speed==n?FAN:0xff39534d,58,()->fanSpeed(n)),58,n==100?0:8);
            t.addView(presets);
        }
        if(available(TIMER)){
            popup.gap(t,16);LinearLayout timerCard=tile(0xff1d312d);
            String timer=state(TIMER);boolean active=!timer.equals("idle");
            LinearLayout row=popup.row();row.addView(label("Sleep timer",23,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            if(active){
                String remaining=attr(TIMER).optString("remaining","");
                if(timer.equals("active"))try{
                    long seconds=Math.max(0,java.time.Duration.between(java.time.Instant.now(),java.time.OffsetDateTime.parse(attr(TIMER).getString("finishes_at")).toInstant()).getSeconds());
                    remaining=String.format(Locale.US,"%dh %02dm",seconds/3600,(seconds%3600)/60);
                }catch(Exception ignored){}
                row.addView(label((timer.equals("paused")?"Paused":remaining.isEmpty()?"Running":remaining+" left"),23,FAN,true));
            }else row.addView(label("Off",22,MUTED,false));
            timerCard.addView(row);popup.gap(timerCard,10);
            LinearLayout times=popup.row();
            if(active)equal(times,control("Cancel timer",FAN,0xff39534d,58,()->service(TIMER,"cancel",null)),58,0);
            else for(int hours:new int[]{1,2,4}){
                Button b=control(hours+" hr",FAN,0xff39534d,58,()->service(TIMER,"start",args("duration",String.format(Locale.US,"%02d:00:00",hours))));b.setEnabled(on);b.setAlpha(on?1f:.55f);equal(times,b,58,hours==4?0:8);
            }
            timerCard.addView(times);t.addView(timerCard);
        }
        addTile(t);
    }
    private void shade(){
        LinearLayout t=tile(0xff443a30);boolean ok=available(SHADE);JSONObject a=attr(SHADE);int features=a.optInt("supported_features");
        int position=a.optInt("current_position",-1);
        LinearLayout head=popup.row();
        head.addView(label(ok?capitalize(state(SHADE)):"Unavailable",36,LIGHT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView percent=label(position>=0&&ok?position+"%":"",32,LIGHT,true);head.addView(percent);t.addView(head);
        if(ok&&position>=0&&(features&4)!=0){
            popup.gap(t,10);t.addView(label(a.optBoolean("assumed_state")?"Estimated opening":"Opening",20,MUTED,false));
            percentSlider(t,"Shade opening",position,LIGHT,percent,n->service(SHADE,"set_cover_position",args("position",n)));
        }
        LinearLayout row=popup.row();popup.gap(t,12);
        String[] names={"Open","Stop","Close"},actions={"open_cover","stop_cover","close_cover"};int[] masks={1,8,2};
        for(int i=0;i<names.length;i++){final String action=actions[i];if((features&masks[i])==0)continue;
            Button b=control(names[i],LIGHT,0xff5a4938,76,()->service(SHADE,action,null));b.setEnabled(ok);equal(row,b,76,i==names.length-1?0:10);
        }
        t.addView(row);addTile(t);
    }
}
