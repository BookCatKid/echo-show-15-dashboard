package com.echo.testlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements DashboardView.Actions {
    private DashboardView dashboard;
    private final Handler ui = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkIo = Executors.newFixedThreadPool(2);
    private HaStateFeed feed;
    private Future<?> artworkJob;
    private long artSerial;
    private boolean refreshQueued;
    private String requestedArtwork="";
    private long artworkRetryAt;
    private JSONObject config = new JSONObject(), snapshot = new JSONObject();
    private HaClient client;
    private JSONArray players = new JSONArray(), items = new JSONArray(), sonosIds;
    private String selected = "", todo = "todo.grocery_list", artworkPath = "";
    private JSONArray allStates = new JSONArray();
    private boolean active, busy;
    private volatile long generation;
    private long lastTodo, lastDiscovery;
    private final Runnable tick = new Runnable() { public void run() {
        if (!active) return;
        dashboard.updateClock();
        refresh(false);
        ui.postDelayed(this, 3000);
    }};
    private interface Job { void run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        loadConfig();
        dashboard = new DashboardView(this, this);
        setContentView(dashboard);
        render();
    }
    @Override public void onResume() { super.onResume(); active = true; if(feed!=null)feed.start(); immersive(); ui.removeCallbacks(tick); ui.post(tick); }
    @Override public void onPause() { super.onPause(); active = false; ui.removeCallbacks(tick); if(feed!=null)feed.stop(); }
    @Override public void onDestroy() { super.onDestroy(); if(feed!=null)feed.stop(); io.shutdownNow();commands.shutdownNow();artworkIo.shutdownNow(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c); dashboard = new DashboardView(this, this); setContentView(dashboard); artworkPath=""; requestedArtwork=""; render();
    }
    @Override public void onBackPressed() { /* The dashboard is home. */ }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) { out.write(b,0,n); if(out.size()>4000000) throw new IOException("Snapshot too large"); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void loadConfig() {
        generation++; if(feed!=null)feed.stop();feed=null;requestedArtwork="";artSerial++;if(artworkJob!=null)artworkJob.cancel(true); players=new JSONArray(); items=new JSONArray(); allStates=new JSONArray(); artworkPath=""; if(dashboard!=null) dashboard.setArtwork(null);
        try { config = new JSONObject(read(new File(getFilesDir(),"ha.json"))); } catch(Exception ignored) {}
        selected = config.optString("player", selected);
        todo = config.optString("todo", "todo.grocery_list");
        String base=config.optString("url"), token=config.optString("token");
        client=null;
        if(!base.isEmpty() && !token.isEmpty()) { try { client=new HaClient(base,token); } catch(Exception ignored) {} }
        sonosIds=null; lastDiscovery=0; lastTodo=0;
        if(client!=null){feed=client.stateFeed();feed.add(this::liveChanged);if(active)feed.start();}
    }
    private void saveConfig() {
        try {
            config.put("player",selected).put("todo",todo);
            try(FileOutputStream out=openFileOutput("ha.json",MODE_PRIVATE)) { out.write(config.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch(Exception e) { toast("Could not save settings"); }
    }
    private void render() {
        try {
            snapshot.put("players",players).put("selectedPlayer",selected).put("items",items).put("haConfigured",client!=null).put("todoName",config.optString("todoName","Grocery List"));
            dashboard.render(snapshot);requestArtwork();
        } catch(Exception ignored) {}
    }
    private void syncPlayers(JSONArray p){
        players=p;
        if(player(selected)==null&&p.length()>0){
            selected=p.optJSONObject(0).optString("entity_id");requestedArtwork="";artSerial++;
            dashboard.setArtwork(null);saveConfig();
        }
    }
    private void syncLivePlayers(){
        if(feed==null||!feed.ready()||sonosIds==null)return;
        JSONArray p=new JSONArray();
        for(int i=0;i<sonosIds.length();i++){JSONObject state=feed.get(sonosIds.optString(i));if(state!=null)p.put(state);}
        syncPlayers(p);
    }
    private void liveChanged(Set<String> ids,boolean reset){
        if(!active||feed==null)return;
        if(!feed.ready()){refresh(true);return;}
        boolean media=reset;
        if(sonosIds!=null)for(int i=0;i<sonosIds.length();i++)if(ids.contains(sonosIds.optString(i)))media=true;
        if(media){syncLivePlayers();render();}
        if(reset||ids.contains(todo)){lastTodo=0;refresh(true);}
    }
    private void requestArtwork(){
        if(dashboard==null||client==null)return;
        JSONObject p=player(selected),a=p==null?null:p.optJSONObject("attributes");
        String path=a==null?"":a.optString("entity_picture","");
        String key=selected+"\n"+path;
        if(key.equals(requestedArtwork)&&System.currentTimeMillis()<artworkRetryAt)return;
        requestedArtwork=key;artworkRetryAt=Long.MAX_VALUE;
        final HaClient c=client;final long serial=++artSerial;
        if(artworkJob!=null)artworkJob.cancel(true);
        if(path.isEmpty()){dashboard.setArtwork(null);return;}
        artworkJob=artworkIo.submit(()->{
            try{Bitmap bitmap=c.artwork(path);ui.post(()->{if(serial==artSerial&&client==c&&!isFinishing()){dashboard.setArtwork(bitmap);artworkPath=path;}});}
            catch(Exception e){ui.post(()->{if(serial==artSerial){artworkRetryAt=System.currentTimeMillis()+5000;}});}
        });
    }
    private void refresh(boolean force) {
        if(busy){refreshQueued|=force;return;}
        busy=true;
        final HaClient current=client;
        final boolean live=feed!=null&&feed.ready();
        final String todoId=todo;
        final long requestGeneration=generation, discoveryAtStart=lastDiscovery, todoAtStart=lastTodo;
        final JSONArray cachedIds=sonosIds;
        io.execute(()->{
            JSONObject echo=null;JSONArray discovered=cachedIds, fallback=null, nextItems=null;String error=null;
            long discoveryTime=discoveryAtStart,todoTime=todoAtStart;
            try{echo=new JSONObject(read(new File(getFilesDir(),"echo-data.json")));}catch(Exception ignored){}
            try{
                if(current!=null){
                    long now=System.currentTimeMillis();
                    if(discovered==null||now-discoveryAtStart>300000){discovered=current.getSonosIds();discoveryTime=now;}
                    if(!live)fallback=current.getStates();
                    if(force||now-todoAtStart>15000){nextItems=current.getItems(todoId);todoTime=now;}
                }
            }catch(Exception e){error=e instanceof HaClient.HttpException?"Home Assistant returned an error":"Home Assistant connection unavailable";}
            final JSONObject data=echo;final JSONArray idResult=discovered,states=fallback,t=nextItems;
            final long dtime=discoveryTime,ttime=todoTime;final String err=error;
            ui.post(()->{
                busy=false;
                if(isFinishing())return;
                if(current!=client||requestGeneration!=generation){if(active)refresh(true);return;}
                try{
                    if(data!=null){if(data.has("weather"))snapshot.put("weather",data.get("weather"));if(data.has("calendar"))snapshot.put("calendar",data.get("calendar"));}
                    sonosIds=idResult;lastDiscovery=dtime;
                    if(feed!=null&&feed.ready()){syncLivePlayers();}
                    else if(states!=null){
                        allStates=states;Set<String> ids=new HashSet<>();
                        if(sonosIds!=null)for(int i=0;i<sonosIds.length();i++)ids.add(sonosIds.optString(i));
                        JSONArray p=new JSONArray();
                        for(int i=0;i<states.length();i++){JSONObject v=states.optJSONObject(i);if(v!=null&&ids.contains(v.optString("entity_id")))p.put(v);}
                        syncPlayers(p);
                    }
                    if(t!=null&&todoId.equals(todo)){items=t;lastTodo=ttime;}
                    snapshot.put("status",err==null?(client==null?"Connect Home Assistant":""):(feed!=null&&feed.ready()?"":err));
                    render();
                }catch(Exception ignored){}
                if(refreshQueued){refreshQueued=false;if(active)refresh(true);}
            });
        });
    }
    private void command(Job job) { command(job,false); }
    private void command(Job job,boolean todoChanged) {
        final long commandGeneration=generation;
        commands.execute(()->{try{if(commandGeneration!=generation)return;job.run();ui.post(()->{if(commandGeneration==generation){if(todoChanged){lastTodo=0;refresh(true);}else if(feed==null||!feed.ready())refresh(false);}});}
            catch(Exception e){ui.post(()->toast("Action failed. Check the connection and try again."));}
        });
    }
    private String leader() {
        JSONObject p=player(selected); if(p==null) return selected;
        JSONArray group=p.optJSONObject("attributes").optJSONArray("group_members");
        return group!=null && group.length()>0 ? group.optString(0,selected) : selected;
    }
    private JSONObject player(String id) { for(int i=0;i<players.length();i++) if(id.equals(players.optJSONObject(i).optString("entity_id"))) return players.optJSONObject(i); return null; }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onRefresh() { if(feed!=null)feed.requestSnapshot();lastTodo=0; refresh(true); }
    @Override public void onSmartHome() {
        if(client==null){toast("Connect Home Assistant first");return;}
        new SmartHomeOverlay(this,client,feed).open();
    }
    @Override public void onFireTv() {
        try {
            getApplicationContext().startActivity(new Intent("com.amazon.tv.launcher.SYSTEM_UI_INTENT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch(SecurityException restricted) {
            // The owner-managed device bridge accepts only this single fixed action.
        } catch(Exception unavailable) {
            toast("Fire TV is unavailable"); return;
        }
        final File request=new File(getFilesDir(),"firetv-launch-request");
        try {
            if(!request.exists()&&!request.createNewFile())throw new IOException("Could not request Fire TV");
            ui.postDelayed(()->{
                if(request.exists()){
                    request.delete();
                    toast("Fire TV launch helper is unavailable");
                }
            },3500);
        } catch(IOException e) { toast("Fire TV is unavailable"); }
    }
    @Override public void onStockHome() { try { startActivity(new Intent().setComponent(new ComponentName("com.amazon.paladin","com.amazon.paladin.app.MMLauncherActivity"))); } catch(Exception e) { toast("Amazon home is unavailable"); } }
    @Override public void onPlayerSelected(String id) { selected=id; artworkPath=""; requestedArtwork=""; dashboard.setArtwork(null); saveConfig(); render(); refresh(false); }
    @Override public void onMediaAction(String action) {
        if(client==null || selected.isEmpty() || player(selected)==null) return;
        if(action.equals("favorites")) { browse("favorites",""); return; }
        if(action.equals("browse")) { browse(null,null); return; }
        String service;
        switch(action) {
            case "play": service="media_play"; break; case "pause": service="media_pause"; break;
            case "previous": case "prev": service="media_previous_track"; break;
            case "next": service="media_next_track"; break;
            case "mute": service="volume_mute"; break;
            case "shuffle": service="shuffle_set"; break;
            default: service="media_play_pause";
        }
        final String name=service,target=service.equals("volume_mute")?selected:leader(); final HaClient c=client;
        JSONObject source=player(target); final boolean oldMute=source!=null && source.optJSONObject("attributes").optBoolean("is_volume_muted"), oldShuffle=source!=null && source.optJSONObject("attributes").optBoolean("shuffle");
        command(() -> { JSONObject data=new JSONObject().put("entity_id",target);
            if(name.equals("volume_mute")) data.put("is_volume_muted",!oldMute);
            if(name.equals("shuffle_set")) data.put("shuffle",!oldShuffle);
            c.call("media_player",name,data,false);
        });
    }
    @Override public void onVolume(float value) { if(client==null || selected.isEmpty() || player(selected)==null) return; final HaClient c=client; final String target=selected; command(() -> c.call("media_player","volume_set",new JSONObject().put("entity_id",target).put("volume_level",Math.max(0,Math.min(1,value))),false)); }
    @Override public void onTodoToggle(String uid,boolean completed) { if(client==null) return; final HaClient c=client; final String target=todo; command(() -> c.call("todo","update_item",new JSONObject().put("entity_id",target).put("item",uid).put("status",completed?"completed":"needs_action"),false),true); }
    @Override public void onTodoAdd(String title) { if(client==null || title.trim().isEmpty()) return; final HaClient c=client; final String target=todo; command(() -> c.call("todo","add_item",new JSONObject().put("entity_id",target).put("item",title.trim()),false),true); }
    @Override public void onGroup() {
        if(client==null || selected.isEmpty() || player(selected)==null) return;
        final String target=selected; final HaClient c=client; final long dialogGeneration=generation; final List<String> ids=new ArrayList<>(),names=new ArrayList<>();
        JSONObject p=player(target); JSONArray members=p==null?null:p.optJSONObject("attributes").optJSONArray("group_members"); Set<String> old=new HashSet<>(); if(members!=null) for(int i=0;i<members.length();i++) old.add(members.optString(i));
        for(int i=0;i<players.length();i++) { JSONObject row=players.optJSONObject(i); String id=row.optString("entity_id"); if(!id.equals(target)) { ids.add(id); names.add(row.optJSONObject("attributes").optString("friendly_name",id)); } }
        final boolean[] checked=new boolean[ids.size()]; for(int i=0;i<checked.length;i++) checked[i]=old.contains(ids.get(i));
        old.add(target);
        DashboardPopup popup=new DashboardPopup(this,"Group rooms",DashboardPopup.MUSIC,DashboardPopup.MUSIC_ACCENT);
        LinearLayout list=popup.column();
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));popup.body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        for(int i=-1;i<ids.size();i++){
            final int index=i;
            boolean anchor=i<0;
            String name=anchor?p.optJSONObject("attributes").optString("friendly_name",target):names.get(i);
            CheckBox row=new CheckBox(this);
            row.setText(name);row.setTextSize(26);row.setTextColor(DashboardPopup.INK);
            try{row.setTypeface(android.graphics.Typeface.createFromFile("/system/fonts/Amazon-Ember-Regular.ttf"));}catch(Exception ignored){}
            row.setButtonTintList(android.content.res.ColorStateList.valueOf(popup.accent));
            row.setPadding(popup.dp(12),popup.dp(8),popup.dp(16),popup.dp(8));
            row.setMinimumHeight(popup.dp(72));row.setChecked(anchor||checked[i]);
            row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x28ffffff),popup.shape(0xff453653,12),popup.shape(android.graphics.Color.WHITE,12)));
            row.setEnabled(!anchor);
            if(anchor)row.setContentDescription(name+", selected room");
            else row.setOnCheckedChangeListener((button,value)->checked[index]=value);
            list.addView(row,new LinearLayout.LayoutParams(-1,-2));popup.gap(list,6);
        }
        popup.actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        popup.actions.addView(popup.button("Cancel",DashboardPopup.MUTED,android.graphics.Color.TRANSPARENT,()->popup.dismiss()));
        popup.actions.addView(popup.button("Apply",DashboardPopup.MUSIC,popup.accent,()->{
            popup.dismiss();
            if(dialogGeneration!=generation || client!=c || !target.equals(selected)) return;
            command(()->{
                Set<String> desired=new HashSet<>(); desired.add(target); for(int i=0;i<ids.size();i++) if(checked[i]) desired.add(ids.get(i));
                if(desired.equals(old)) return;
                String previousLeader=members!=null && members.length()>0?members.optString(0,target):target;
                String coordinator=desired.contains(previousLeader)?previousLeader:target;
                if(!coordinator.equals(previousLeader) && old.contains(coordinator)) c.call("media_player","unjoin",new JSONObject().put("entity_id",coordinator),false);
                for(String member:old) if(!desired.contains(member) && !member.equals(previousLeader)) c.call("media_player","unjoin",new JSONObject().put("entity_id",member),false);
                JSONArray joining=new JSONArray(); for(String member:desired) if(!member.equals(coordinator) && (!old.contains(member)||!coordinator.equals(previousLeader))) joining.put(member);
                if(joining.length()>0) c.call("media_player","join",new JSONObject().put("entity_id",coordinator).put("group_members",joining),false);
            });
        }));
        popup.open(false,Math.min(850,210+78*(ids.size()+1)));
    }
    private static final class BrowsePage {
        final String type,id,title; BrowsePage(String type,String id,String title){this.type=type;this.id=id;this.title=title;}
    }
    private void browse(String type,String id) {
        if(client==null || selected.isEmpty() || player(selected)==null) return;
        final HaClient c=client; final String target=leader(); final long browseGeneration=generation;
        DashboardPopup popup=new DashboardPopup(this,type!=null&&type.equals("favorites")?"Favorites":"Your music",DashboardPopup.MUSIC,DashboardPopup.MUSIC_ACCENT);
        // Keep the footer outside the scrolling region with a dedicated gap.
        LinearLayout.LayoutParams footerParams=(LinearLayout.LayoutParams)popup.actions.getLayoutParams();
        footerParams.topMargin=popup.dp(20);popup.actions.setLayoutParams(footerParams);
        popup.body.setClipChildren(true);popup.body.setClipToPadding(true);
        Deque<BrowsePage> pages=new ArrayDeque<>();
        pages.push(new BrowsePage(type,id,type!=null&&type.equals("favorites")?"Favorites":"Your music"));
        final class Browser {
            int request;
            boolean valid(int serial){return popup.isShowing() && request==serial && c==client && browseGeneration==generation && target.equals(leader());}
            void load(){
                int serial=++request;BrowsePage page=pages.peek();popup.title.setText(page.title);
                popup.body.removeAllViews();popup.actions.removeAllViews();
                popup.body.addView(popup.label("Loading music…",24,DashboardPopup.MUTED,false));
                if(pages.size()>1)popup.actions.addView(popup.button("‹ Back",popup.accent,0xff4a3a5b,()->{pages.pop();load();}));
                popup.actions.addView(new View(MainActivity.this),new LinearLayout.LayoutParams(0,1,1));
                popup.actions.addView(popup.button("Close",popup.accent,0xff4a3a5b,()->popup.dismiss()));
                io.execute(()->{try{
                    JSONArray children=c.browse(target,page.type,page.id);
                    ui.post(()->{
                        if(!valid(serial))return;popup.body.removeAllViews();
                        if(children.length()==0){popup.body.addView(popup.label("No music available in this folder",24,DashboardPopup.MUTED,false));return;}
                        LinearLayout list=popup.column();ScrollView sc=new ScrollView(MainActivity.this);sc.setVerticalScrollBarEnabled(false);sc.setClipChildren(true);sc.setClipToPadding(true);sc.addView(list,new ScrollView.LayoutParams(-1,-2));popup.body.addView(sc,new LinearLayout.LayoutParams(-1,-1));
                        for(int i=0;i<children.length();i++){
                            JSONObject item=children.optJSONObject(i);if(item==null)continue;
                            String name=item.optString("title","Music"), t=item.optString("media_content_type"),key=item.optString("media_content_id");
                            boolean expand=item.optBoolean("can_expand"),play=item.optBoolean("can_play");
                            LinearLayout entry=popup.row();entry.setPadding(popup.dp(16),popup.dp(10),popup.dp(12),popup.dp(10));entry.setMinimumHeight(popup.dp(68));
                            entry.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x28ffffff),popup.shape(0xff453653,12),popup.shape(android.graphics.Color.WHITE,12)));
                            TextView label=popup.label(name,24,DashboardPopup.INK,false);label.setMaxLines(2);entry.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                            if(expand||play)entry.addView(popup.label(expand?"›":"▶",24,popup.accent,true));
                            entry.setOnClickListener(v->{if(!valid(serial))return;if(expand){pages.push(new BrowsePage(t,key,name));load();}else if(play){popup.dismiss();command(()->c.call("media_player","play_media",new JSONObject().put("entity_id",target).put("media_content_type",t).put("media_content_id",key),false));}});
                            if(!expand&&!play)entry.setAlpha(.55f);
                            list.addView(entry,new LinearLayout.LayoutParams(-1,-2));popup.gap(list,6);
                        }
                    });
                }catch(Exception e){ui.post(()->{if(valid(serial)){popup.body.removeAllViews();popup.body.addView(popup.label("Music library is unavailable",24,DashboardPopup.MUTED,false));popup.body.addView(popup.button("Retry",popup.accent,0xff4a3a5b,()->load()));}});}});
            }
        }
        popup.open(false,760);new Browser().load();
    }
    @Override public void onSettings() {
        LinearLayout form=new LinearLayout(this); form.setOrientation(1); form.setPadding(32,16,32,8);
        EditText url=new EditText(this); url.setSingleLine(); url.setHint("Home Assistant URL"); url.setText(config.optString("url")); form.addView(url);
        EditText token=new EditText(this); token.setSingleLine(); token.setInputType(129); token.setHint("Token (leave empty to keep current)"); form.addView(token);
        Button list=new Button(this); list.setText("List: "+config.optString("todoName","Grocery List")); list.setOnClickListener(v->chooseTodo()); form.addView(list);
        Button music=new Button(this); music.setText("Browse Sonos music"); music.setOnClickListener(v->browse(null,null)); form.addView(music);
        new AlertDialog.Builder(this).setTitle("Home settings").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            String u=url.getText().toString().trim(); if(!u.startsWith("http://")&&!u.startsWith("https://")) u="http://"+u;
            try { String t=token.getText().toString().trim(); new HaClient(u,t.isEmpty()?config.optString("token"):t); config.put("url",u); if(!t.isEmpty()) config.put("token",t); saveConfig(); loadConfig(); render(); refresh(true); } catch(Exception e) { toast("Enter a valid URL and token"); }
        }).show();
    }
    private void chooseTodo() {
        JSONArray states=feed!=null&&feed.ready()?feed.snapshot():allStates; List<String> ids=new ArrayList<>(),names=new ArrayList<>(); for(int i=0;i<states.length();i++) { JSONObject s=states.optJSONObject(i); String id=s.optString("entity_id"); if(id.startsWith("todo.")) { ids.add(id); names.add(s.optJSONObject("attributes").optString("friendly_name",id)); } }
        if(ids.isEmpty()) { toast("Connect Home Assistant to choose a list"); return; }
        new AlertDialog.Builder(this).setTitle("Shopping list").setItems(names.toArray(new String[0]),(d,w)->{ todo=ids.get(w); try { config.put("todoName",names.get(w)); } catch(Exception ignored) {} saveConfig(); items=new JSONArray(); lastTodo=0; render(); refresh(true); }).show();
    }
}
