package com.echo.testlauncher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One authenticated Home Assistant state subscription, shared by every widget.
 *
 * <p>All cache mutation and listener delivery happen on the main thread. State is never
 * reported as current until the initial snapshot has arrived.
 */
final class HaStateFeed {

    interface Listener {
        void changed(Set<String> ids, boolean reset);
    }

    private static final String TAG = "HaStateFeed";
    private static final int INITIAL_RETRY_MS = 1000;
    private static final int MAX_RETRY_MS = 30000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int CONNECTION_LOST_TIMEOUT_S = 30;
    private static final int NOTIFY_DEBOUNCE_MS = 16;
    private static final int SUBSCRIBE_EVENTS_ID = 1;
    private static final int GET_STATES_ID = 2;

    private final URI uri;
    private final String token;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> states = new LinkedHashMap<>();
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final Set<String> pendingIds = new HashSet<>();

    private WebSocketClient connection;
    private boolean started;
    private boolean ready;
    private boolean resetPending;
    private boolean notificationScheduled;
    private boolean snapshotPending;
    private int retryMs = INITIAL_RETRY_MS;
    private long receivedEvents;

    private final Runnable notifyListeners = () -> {
        notificationScheduled = false;
        Set<String> ids = new HashSet<>(pendingIds);
        pendingIds.clear();
        boolean reset = resetPending;
        resetPending = false;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.changed(ids, reset);
        }
    };

    private final Runnable reconnect = this::connect;

    private final Runnable handshakeTimeout = () -> {
        if (!ready && connection != null) {
            failed(connection);
        }
    };

    HaStateFeed(URI uri, String token) {
        this.uri = uri;
        this.token = token;
    }

    void add(Listener listener) {
        listeners.add(listener);
    }

    void remove(Listener listener) {
        listeners.remove(listener);
    }

    boolean isReady() {
        return ready;
    }

    JSONObject get(String entityId) {
        return states.get(entityId);
    }

    JSONArray snapshot() {
        JSONArray result = new JSONArray();
        for (JSONObject state : states.values()) {
            result.put(state);
        }
        return result;
    }

    void requestSnapshot() {
        if (connection == null || !ready || snapshotPending) {
            return;
        }
        try {
            snapshotPending = true;
            send(connection, new JSONObject().put("id", GET_STATES_ID).put("type", "get_states"));
        } catch (Exception e) {
            failed(connection);
        }
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        retryMs = INITIAL_RETRY_MS;
        connect();
    }

    void stop() {
        started = false;
        ready = false;
        snapshotPending = false;
        mainHandler.removeCallbacks(reconnect);
        mainHandler.removeCallbacks(handshakeTimeout);
        mainHandler.removeCallbacks(notifyListeners);
        pendingIds.clear();
        resetPending = false;
        notificationScheduled = false;
        WebSocketClient previous = connection;
        connection = null;
        if (previous != null) {
            previous.close();
        }
    }

    private void signal(boolean reset, String entityId) {
        resetPending |= reset;
        if (entityId != null) {
            pendingIds.add(entityId);
        }
        if (!notificationScheduled) {
            notificationScheduled = true;
            mainHandler.postDelayed(notifyListeners, NOTIFY_DEBOUNCE_MS);
        }
    }

    private void connect() {
        if (!started || connection != null) {
            return;
        }
        WebSocketClient socket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
            }

            @Override
            public void onMessage(String message) {
                try {
                    JSONObject payload = new JSONObject(message);
                    mainHandler.post(() -> receive(this, payload));
                } catch (JSONException e) {
                    mainHandler.post(() -> failed(this));
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                mainHandler.post(() -> failed(this));
            }

            @Override
            public void onError(Exception error) {
                mainHandler.post(() -> failed(this));
            }
        };
        connection = socket;
        if ("wss".equalsIgnoreCase(uri.getScheme())) {
            try {
                socket.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
            } catch (Exception e) {
                failed(socket);
                return;
            }
        }
        socket.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_S);
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
        socket.connect();
    }

    private void failed(WebSocketClient socket) {
        if (socket != connection) {
            return;
        }
        connection = null;
        ready = false;
        snapshotPending = false;
        mainHandler.removeCallbacks(handshakeTimeout);
        socket.close();
        Log.w(TAG, "Disconnected; using REST fallback");
        signal(true, null);
        if (started) {
            mainHandler.removeCallbacks(reconnect);
            mainHandler.postDelayed(reconnect, retryMs);
            retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
        }
    }

    private void send(WebSocketClient socket, JSONObject payload) {
        socket.send(payload.toString());
    }

    private void receive(WebSocketClient socket, JSONObject message) {
        if (!started || socket != connection) {
            return;
        }
        try {
            switch (message.optString("type")) {
                case "auth_required":
                    send(socket, new JSONObject()
                            .put("type", "auth")
                            .put("access_token", token));
                    break;
                case "auth_ok":
                    send(socket, new JSONObject()
                            .put("id", SUBSCRIBE_EVENTS_ID)
                            .put("type", "subscribe_events")
                            .put("event_type", "state_changed"));
                    break;
                case "auth_invalid":
                    failed(socket);
                    break;
                case "result":
                    handleResult(socket, message);
                    break;
                case "event":
                    handleEvent(message);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            failed(socket);
        }
    }

    private void handleResult(WebSocketClient socket, JSONObject message) throws JSONException {
        if (!message.optBoolean("success")) {
            failed(socket);
            return;
        }
        int id = message.optInt("id");
        if (id == SUBSCRIBE_EVENTS_ID) {
            snapshotPending = true;
            send(socket, new JSONObject().put("id", GET_STATES_ID).put("type", "get_states"));
        } else if (id == GET_STATES_ID) {
            snapshotPending = false;
            JSONArray array = message.getJSONArray("result");
            states.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject state = array.getJSONObject(i);
                states.put(state.getString("entity_id"), state);
            }
            ready = true;
            retryMs = INITIAL_RETRY_MS;
            mainHandler.removeCallbacks(handshakeTimeout);
            Log.i(TAG, "Live subscription ready; " + states.size() + " states");
            signal(true, null);
        }
    }

    private void handleEvent(JSONObject message) throws JSONException {
        if (!ready || message.optInt("id") != SUBSCRIBE_EVENTS_ID) {
            return;
        }
        JSONObject event = message.getJSONObject("event");
        JSONObject data = event.getJSONObject("data");
        String entityId = data.getString("entity_id");
        JSONObject next = data.optJSONObject("new_state");
        if (next == null) {
            states.remove(entityId);
        } else {
            states.put(entityId, next);
        }

        receivedEvents++;
        if (receivedEvents <= 5 || receivedEvents % 100 == 0) {
            try {
                long elapsed = Duration.between(
                        Instant.parse(event.getString("time_fired")),
                        Instant.now()).toMillis();
                Log.i(TAG, "Event #" + receivedEvents + " server_to_app_ms=" + elapsed);
            } catch (Exception unknownTimestamp) {
                // Logging is best effort.
            }
        }
        if (entityId.startsWith("media_player.")
                || entityId.startsWith("cover.")
                || entityId.startsWith("switch.")
                || entityId.startsWith("light.")
                || entityId.startsWith("climate.")
                || entityId.startsWith("todo.")) {
            try {
                long lag = Duration.between(
                        Instant.parse(event.getString("time_fired")),
                        Instant.now()).toMillis();
                Log.d(TAG, "state_changed " + entityId + " receive_ms=" + lag);
            } catch (Exception unknownTimestamp) {
                // Logging is best effort.
            }
        }
        signal(false, entityId);
    }
}
