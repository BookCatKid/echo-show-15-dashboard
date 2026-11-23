package com.echo.testlauncher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.util.*;

/** One authenticated HA state subscription, shared by every widget.
 * All cache mutation and listener delivery happen on the main thread.
 * No state is claimed current until an initial snapshot has arrived. */
final class HaStateFeed {
    interface Listener { void changed(Set<String> ids, boolean reset); }
    private final URI uri;
    private final String token;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,JSONObject> states=new LinkedHashMap<>();
    private final Set<Listener> listeners=new LinkedHashSet<>();
    private final Set<String> pending=new HashSet<>();
    private WebSocketClient connection;
    private boolean started,ready,reset,notificationScheduled;
    private int retry=1000;
    private long receivedEvents;
    private boolean snapshotPending;
    private final Runnable notifyListeners=()->{
        notificationScheduled=false;Set<String> ids=new HashSet<>(pending);pending.clear();
        boolean full=reset;reset=false;
        for(Listener l:new ArrayList<>(listeners))l.changed(ids,full);
    };
    private final Runnable reconnect=this::connect;
    private final Runnable handshakeTimeout=()->{if(!ready&&connection!=null)failed(connection);};
    HaStateFeed(URI uri,String token){this.uri=uri;this.token=token;}
    void add(Listener l){listeners.add(l);}
    void remove(Listener l){listeners.remove(l);}
    boolean ready(){return ready;}
    JSONObject get(String id){return states.get(id);}
    JSONArray snapshot(){JSONArray out=new JSONArray();for(JSONObject s:states.values())out.put(s);return out;}
    void requestSnapshot(){
        if(connection==null||!ready||snapshotPending)return;
        try{snapshotPending=true;send(connection,new JSONObject().put("id",2).put("type","get_states"));}
        catch(Exception e){failed(connection);}
    }
    void start(){if(started)return;started=true;retry=1000;connect();}
    void stop(){
        started=false;ready=false;snapshotPending=false;ui.removeCallbacks(reconnect);ui.removeCallbacks(handshakeTimeout);ui.removeCallbacks(notifyListeners);
        pending.clear();reset=false;notificationScheduled=false;
        WebSocketClient old=connection;connection=null;if(old!=null)old.close();
    }
    private void signal(boolean full,String id){
        reset|=full;if(id!=null)pending.add(id);
        if(!notificationScheduled){notificationScheduled=true;ui.postDelayed(notifyListeners,16);}
    }
    private void connect(){
        if(!started||connection!=null)return;
        WebSocketClient ws=new WebSocketClient(uri){
            @Override public void onOpen(ServerHandshake h){}
            @Override public void onMessage(String message){
                try {JSONObject msg=new JSONObject(message);ui.post(()->receive(this,msg));}
                catch(JSONException e){ui.post(()->failed(this));}
            }
            @Override public void onClose(int code,String reason,boolean remote){ui.post(()->failed(this));}
            @Override public void onError(Exception e){ui.post(()->failed(this));}
        };
        connection=ws;
        if("wss".equalsIgnoreCase(uri.getScheme())){
            try {ws.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());}
            catch(Exception e){failed(ws);return;}
        }
        ws.setConnectionLostTimeout(30);
        ui.postDelayed(handshakeTimeout,15000);
        ws.connect();
    }
    private void failed(WebSocketClient ws){
        if(ws!=connection)return;
        connection=null;ready=false;snapshotPending=false;ui.removeCallbacks(handshakeTimeout);
        ws.close();
        Log.w("HaStateFeed","Disconnected; using REST fallback");
        signal(true,null);
        if(started){ui.removeCallbacks(reconnect);ui.postDelayed(reconnect,retry);retry=Math.min(30000,retry*2);}
    }
    private void send(WebSocketClient ws,JSONObject payload){ws.send(payload.toString());}
    private void receive(WebSocketClient ws,JSONObject msg){
        if(!started||ws!=connection)return;
        try{
            switch(msg.optString("type")){
                case "auth_required":
                    send(ws,new JSONObject().put("type","auth").put("access_token",token));break;
                case "auth_ok":
                    send(ws,new JSONObject().put("id",1).put("type","subscribe_events").put("event_type","state_changed"));break;
                case "auth_invalid": failed(ws);break;
                case "result":
                    if(!msg.optBoolean("success")){failed(ws);break;}
                    if(msg.optInt("id")==1){
                        snapshotPending=true;send(ws,new JSONObject().put("id",2).put("type","get_states"));
                    }else if(msg.optInt("id")==2){
                        snapshotPending=false;JSONArray array=msg.getJSONArray("result");states.clear();
                        for(int i=0;i<array.length();i++){JSONObject s=array.getJSONObject(i);states.put(s.getString("entity_id"),s);}
                        ready=true;retry=1000;ui.removeCallbacks(handshakeTimeout);
                        Log.i("HaStateFeed","Live subscription ready; "+states.size()+" states");
                        signal(true,null);
                    }
                    break;
                case "event":
                    if(!ready||msg.optInt("id")!=1)break;
                    JSONObject event=msg.getJSONObject("event"),data=event.getJSONObject("data");
                    String id=data.getString("entity_id");JSONObject next=data.optJSONObject("new_state");
                    if(++receivedEvents<=5||receivedEvents%100==0){
                        try{long elapsed=java.time.Duration.between(java.time.Instant.parse(event.getString("time_fired")),java.time.Instant.now()).toMillis();
                            Log.i("HaStateFeed","Event #"+receivedEvents+" server_to_app_ms="+elapsed);}
                        catch(Exception ignored){}
                    }
                    if(next==null)states.remove(id);else states.put(id,next);
                    if(id.startsWith("media_player.")||id.startsWith("cover.")||id.startsWith("switch.")||id.startsWith("light.")||id.startsWith("climate.")||id.startsWith("todo.")){
                        try{long lag=java.time.Duration.between(java.time.Instant.parse(event.getString("time_fired")),java.time.Instant.now()).toMillis();
                            Log.d("HaStateFeed","state_changed "+id+" receive_ms="+lag);}
                        catch(Exception ignored){}
                    }
                    signal(false,id);break;
            }
        }catch(Exception e){failed(ws);}
    }
}
