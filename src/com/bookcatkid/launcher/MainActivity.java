package com.bookcatkid.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Home screen activity. Owns the Home Assistant connection, merges the Echo data snapshot and
 * the Home Assistant state into one dashboard snapshot, and forwards user actions back to Home
 * Assistant.
 */
public class MainActivity extends Activity implements DashboardView.Actions {

    private static final long CLOCK_TICK_MS = 3000;
    private static final long SONOS_DISCOVERY_INTERVAL_MS = 300000;
    private static final long TODO_REFRESH_INTERVAL_MS = 15000;
    private static final long ARTWORK_RETRY_DELAY_MS = 5000;
    private static final long FIRE_TV_REQUEST_TIMEOUT_MS = 3500;
    private static final int SNAPSHOT_LIMIT_BYTES = 4_000_000;
    private static final String DEFAULT_TODO = "todo.grocery_list";

    private DashboardView dashboard;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newFixedThreadPool(2);

    private HaStateFeed feed;
    private Future<?> artworkJob;
    private long artworkSerial;
    private boolean refreshQueued;
    private String requestedArtwork = "";
    private long artworkRetryAt;

    private JSONObject config = new JSONObject();
    private final JSONObject snapshot = new JSONObject();
    private HaClient client;

    private JSONArray players = new JSONArray();
    private JSONArray items = new JSONArray();
    private JSONArray sonosIds;
    private JSONArray allStates = new JSONArray();

    private String selected = "";
    private String todo = DEFAULT_TODO;

    private boolean active;
    private boolean busy;
    private volatile long generation;
    private long lastTodo;
    private long lastDiscovery;

    private interface Job {
        void run() throws Exception;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            dashboard.updateClock();
            refresh(false);
            mainHandler.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        loadConfig();
        dashboard = new DashboardView(this, this);
        setContentView(dashboard);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        active = true;
        if (feed != null) {
            feed.start();
        }
        immersive();
        mainHandler.removeCallbacks(tick);
        mainHandler.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        active = false;
        mainHandler.removeCallbacks(tick);
        if (feed != null) {
            feed.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (feed != null) {
            feed.stop();
        }
        ioExecutor.shutdownNow();
        commandExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            immersive();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        dashboard = new DashboardView(this, this);
        setContentView(dashboard);
        requestedArtwork = "";
        render();
    }

    @Override
    public void onBackPressed() {
        // The dashboard is the home screen.
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static String read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > SNAPSHOT_LIMIT_BYTES) {
                    throw new IOException("Snapshot too large");
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void loadConfig() {
        generation++;
        if (feed != null) {
            feed.stop();
        }
        feed = null;
        requestedArtwork = "";
        artworkSerial++;
        if (artworkJob != null) {
            artworkJob.cancel(true);
        }
        players = new JSONArray();
        items = new JSONArray();
        allStates = new JSONArray();
        if (dashboard != null) {
            dashboard.setArtwork(null);
        }

        try {
            config = new JSONObject(read(new File(getFilesDir(), "ha.json")));
        } catch (Exception missingConfig) {
            // Keep the last known configuration.
        }
        selected = config.optString("player", selected);
        todo = config.optString("todo", DEFAULT_TODO);

        String base = config.optString("url");
        String token = config.optString("token");
        client = null;
        if (!base.isEmpty() && !token.isEmpty()) {
            try {
                client = new HaClient(base, token);
            } catch (Exception invalidConfig) {
                // Leave the dashboard unconfigured.
            }
        }

        sonosIds = null;
        lastDiscovery = 0;
        lastTodo = 0;
        if (client != null) {
            feed = client.stateFeed();
            feed.add(this::liveChanged);
            if (active) {
                feed.start();
            }
        }
    }

    private void saveConfig() {
        try {
            config.put("player", selected).put("todo", todo);
            try (FileOutputStream output = openFileOutput("ha.json", MODE_PRIVATE)) {
                output.write(config.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            toast("Could not save settings");
        }
    }

    private void render() {
        try {
            snapshot.put("players", players)
                    .put("selectedPlayer", selected)
                    .put("items", items)
                    .put("haConfigured", client != null)
                    .put("todoName", config.optString("todoName", "Grocery List"));
            dashboard.render(snapshot);
            requestArtwork();
        } catch (Exception ignored) {
            // A partial snapshot is still better than a crashed home screen.
        }
    }

    private void syncPlayers(JSONArray nextPlayers) {
        players = nextPlayers;
        if (player(selected) == null && nextPlayers.length() > 0) {
            selected = nextPlayers.optJSONObject(0).optString("entity_id");
            requestedArtwork = "";
            artworkSerial++;
            dashboard.setArtwork(null);
            saveConfig();
        }
    }

    private void syncLivePlayers() {
        if (feed == null || !feed.isReady() || sonosIds == null) {
            return;
        }
        JSONArray livePlayers = new JSONArray();
        for (int i = 0; i < sonosIds.length(); i++) {
            JSONObject state = feed.get(sonosIds.optString(i));
            if (state != null) {
                livePlayers.put(state);
            }
        }
        syncPlayers(livePlayers);
    }

    private void liveChanged(Set<String> changedIds, boolean reset) {
        if (!active || feed == null) {
            return;
        }
        if (!feed.isReady()) {
            refresh(true);
            return;
        }

        boolean mediaChanged = reset;
        if (sonosIds != null) {
            for (int i = 0; i < sonosIds.length(); i++) {
                if (changedIds.contains(sonosIds.optString(i))) {
                    mediaChanged = true;
                }
            }
        }
        if (mediaChanged) {
            syncLivePlayers();
            render();
        }
        if (reset || changedIds.contains(todo)) {
            lastTodo = 0;
            refresh(true);
        }
    }

    private void requestArtwork() {
        if (dashboard == null || client == null) {
            return;
        }
        JSONObject player = player(selected);
        JSONObject attributes = player == null ? null : player.optJSONObject("attributes");
        String path = attributes == null ? "" : attributes.optString("entity_picture", "");
        String key = selected + "\n" + path;
        if (key.equals(requestedArtwork) && System.currentTimeMillis() < artworkRetryAt) {
            return;
        }
        requestedArtwork = key;
        artworkRetryAt = Long.MAX_VALUE;

        final HaClient currentClient = client;
        final long serial = ++artworkSerial;
        if (artworkJob != null) {
            artworkJob.cancel(true);
        }
        if (path.isEmpty()) {
            dashboard.setArtwork(null);
            return;
        }

        artworkJob = artworkExecutor.submit(() -> {
            try {
                Bitmap bitmap = currentClient.artwork(path);
                mainHandler.post(() -> {
                    if (serial == artworkSerial && client == currentClient && !isFinishing()) {
                        dashboard.setArtwork(bitmap);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (serial == artworkSerial) {
                        artworkRetryAt = System.currentTimeMillis() + ARTWORK_RETRY_DELAY_MS;
                    }
                });
            }
        });
    }

    private void refresh(boolean force) {
        if (busy) {
            refreshQueued |= force;
            return;
        }
        busy = true;

        final HaClient currentClient = client;
        final boolean live = feed != null && feed.isReady();
        final String todoId = todo;
        final long requestGeneration = generation;
        final long discoveryAtStart = lastDiscovery;
        final long todoAtStart = lastTodo;
        final JSONArray cachedIds = sonosIds;

        ioExecutor.execute(() -> {
            JSONObject echoData = null;
            JSONArray discovered = cachedIds;
            JSONArray fallbackStates = null;
            JSONArray nextItems = null;
            String error = null;
            long discoveryTime = discoveryAtStart;
            long todoTime = todoAtStart;

            try {
                echoData = new JSONObject(read(new File(getFilesDir(), "echo-data.json")));
            } catch (Exception missingEchoData) {
                // The Echo bridge may not have written a snapshot yet.
            }

            try {
                if (currentClient != null) {
                    long now = System.currentTimeMillis();
                    if (discovered == null || now - discoveryAtStart > SONOS_DISCOVERY_INTERVAL_MS) {
                        discovered = currentClient.getSonosIds();
                        discoveryTime = now;
                    }
                    if (!live) {
                        fallbackStates = currentClient.getStates();
                    }
                    if (force || now - todoAtStart > TODO_REFRESH_INTERVAL_MS) {
                        nextItems = currentClient.getItems(todoId);
                        todoTime = now;
                    }
                }
            } catch (Exception e) {
                error = e instanceof HaClient.HttpException
                        ? "Home Assistant returned an error"
                        : "Home Assistant connection unavailable";
            }

            final JSONObject data = echoData;
            final JSONArray discoveredIds = discovered;
            final JSONArray states = fallbackStates;
            final JSONArray fetchedItems = nextItems;
            final long nextDiscoveryTime = discoveryTime;
            final long nextTodoTime = todoTime;
            final String requestError = error;

            mainHandler.post(() -> {
                busy = false;
                if (isFinishing()) {
                    return;
                }
                if (currentClient != client || requestGeneration != generation) {
                    if (active) {
                        refresh(true);
                    }
                    return;
                }
                try {
                    if (data != null) {
                        if (data.has("weather")) {
                            snapshot.put("weather", data.get("weather"));
                        }
                        if (data.has("calendar")) {
                            snapshot.put("calendar", data.get("calendar"));
                        }
                    }

                    sonosIds = discoveredIds;
                    lastDiscovery = nextDiscoveryTime;
                    if (feed != null && feed.isReady()) {
                        syncLivePlayers();
                    } else if (states != null) {
                        allStates = states;
                        Set<String> sonosEntityIds = new HashSet<>();
                        if (sonosIds != null) {
                            for (int i = 0; i < sonosIds.length(); i++) {
                                sonosEntityIds.add(sonosIds.optString(i));
                            }
                        }
                        JSONArray sonosPlayers = new JSONArray();
                        for (int i = 0; i < states.length(); i++) {
                            JSONObject state = states.optJSONObject(i);
                            if (state != null
                                    && sonosEntityIds.contains(state.optString("entity_id"))) {
                                sonosPlayers.put(state);
                            }
                        }
                        syncPlayers(sonosPlayers);
                    }

                    if (fetchedItems != null && todoId.equals(todo)) {
                        items = fetchedItems;
                        lastTodo = nextTodoTime;
                    }

                    snapshot.put("status", requestError == null
                            ? (client == null ? "Connect Home Assistant" : "")
                            : (feed != null && feed.isReady() ? "" : requestError));
                    render();
                } catch (Exception ignored) {
                    // Keep the last rendered snapshot.
                }

                if (refreshQueued) {
                    refreshQueued = false;
                    if (active) {
                        refresh(true);
                    }
                }
            });
        });
    }

    private void command(Job job) {
        command(job, false);
    }

    private void command(Job job, boolean todoChanged) {
        final long commandGeneration = generation;
        commandExecutor.execute(() -> {
            try {
                if (commandGeneration != generation) {
                    return;
                }
                job.run();
                mainHandler.post(() -> {
                    if (commandGeneration != generation) {
                        return;
                    }
                    if (todoChanged) {
                        lastTodo = 0;
                        refresh(true);
                    } else if (feed == null || !feed.isReady()) {
                        refresh(false);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(
                        () -> toast("Action failed. Check the connection and try again."));
            }
        });
    }

    private String leader() {
        JSONObject player = player(selected);
        if (player == null) {
            return selected;
        }
        JSONArray groupMembers = player.optJSONObject("attributes")
                .optJSONArray("group_members");
        return groupMembers != null && groupMembers.length() > 0
                ? groupMembers.optString(0, selected)
                : selected;
    }

    private JSONObject player(String id) {
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (id.equals(player.optString("entity_id"))) {
                return player;
            }
        }
        return null;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRefresh() {
        if (feed != null) {
            feed.requestSnapshot();
        }
        lastTodo = 0;
        refresh(true);
    }

    @Override
    public void onSmartHome() {
        if (client == null) {
            toast("Connect Home Assistant first");
            return;
        }
        new SmartHomeOverlay(this, client, feed).open();
    }

    @Override
    public void onFireTv() {
        try {
            getApplicationContext().startActivity(
                    new Intent("com.amazon.tv.launcher.SYSTEM_UI_INTENT")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (SecurityException restricted) {
            // The owner-managed device bridge accepts only this single fixed action.
        } catch (Exception unavailable) {
            toast("Fire TV is unavailable");
            return;
        }

        File request = new File(getFilesDir(), "firetv-launch-request");
        try {
            if (!request.exists() && !request.createNewFile()) {
                throw new IOException("Could not request Fire TV");
            }
            mainHandler.postDelayed(() -> {
                if (request.exists()) {
                    request.delete();
                    toast("Fire TV launch helper is unavailable");
                }
            }, FIRE_TV_REQUEST_TIMEOUT_MS);
        } catch (IOException e) {
            toast("Fire TV is unavailable");
        }
    }

    @Override
    public void onStockHome() {
        try {
            startActivity(new Intent().setComponent(
                    new ComponentName(
                            "com.amazon.paladin",
                            "com.amazon.paladin.app.MMLauncherActivity")));
        } catch (Exception e) {
            toast("Amazon home is unavailable");
        }
    }

    @Override
    public void onPlayerSelected(String id) {
        selected = id;
        requestedArtwork = "";
        dashboard.setArtwork(null);
        saveConfig();
        render();
        refresh(false);
    }

    @Override
    public void onMediaAction(String action) {
        if (client == null || selected.isEmpty() || player(selected) == null) {
            return;
        }
        if ("favorites".equals(action)) {
            browse("favorites", "");
            return;
        }
        if ("browse".equals(action)) {
            browse(null, null);
            return;
        }

        String service;
        switch (action) {
            case "play":
                service = "media_play";
                break;
            case "pause":
                service = "media_pause";
                break;
            case "previous":
            case "prev":
                service = "media_previous_track";
                break;
            case "next":
                service = "media_next_track";
                break;
            case "mute":
                service = "volume_mute";
                break;
            case "shuffle":
                service = "shuffle_set";
                break;
            default:
                service = "media_play_pause";
                break;
        }

        final String serviceName = service;
        final String target = "volume_mute".equals(service) ? selected : leader();
        final HaClient currentClient = client;
        JSONObject source = player(target);
        final boolean muted = source != null
                && source.optJSONObject("attributes").optBoolean("is_volume_muted");
        final boolean shuffle = source != null
                && source.optJSONObject("attributes").optBoolean("shuffle");

        command(() -> {
            JSONObject data = new JSONObject().put("entity_id", target);
            if ("volume_mute".equals(serviceName)) {
                data.put("is_volume_muted", !muted);
            }
            if ("shuffle_set".equals(serviceName)) {
                data.put("shuffle", !shuffle);
            }
            currentClient.call("media_player", serviceName, data, false);
        });
    }

    @Override
    public void onVolume(float value) {
        if (client == null || selected.isEmpty() || player(selected) == null) {
            return;
        }
        final HaClient currentClient = client;
        final String target = selected;
        command(() -> currentClient.call(
                "media_player",
                "volume_set",
                new JSONObject()
                        .put("entity_id", target)
                        .put("volume_level", Math.max(0, Math.min(1, value))),
                false));
    }

    @Override
    public void onTodoToggle(String uid, boolean completed) {
        if (client == null) {
            return;
        }
        final HaClient currentClient = client;
        final String target = todo;
        command(() -> currentClient.call(
                "todo",
                "update_item",
                new JSONObject()
                        .put("entity_id", target)
                        .put("item", uid)
                        .put("status", completed ? "completed" : "needs_action"),
                false), true);
    }

    @Override
    public void onTodoAdd(String title) {
        if (client == null || title.trim().isEmpty()) {
            return;
        }
        final HaClient currentClient = client;
        final String target = todo;
        command(() -> currentClient.call(
                "todo",
                "add_item",
                new JSONObject()
                        .put("entity_id", target)
                        .put("item", title.trim()),
                false), true);
    }

    @Override
    public void onGroup() {
        if (client == null || selected.isEmpty() || player(selected) == null) {
            return;
        }
        final String target = selected;
        final HaClient currentClient = client;
        final long dialogGeneration = generation;

        JSONObject anchorPlayer = player(target);
        JSONArray members = anchorPlayer == null
                ? null
                : anchorPlayer.optJSONObject("attributes").optJSONArray("group_members");
        Set<String> previousMembers = new HashSet<>();
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                previousMembers.add(members.optString(i));
            }
        }

        final List<String> candidateIds = new ArrayList<>();
        final List<String> candidateNames = new ArrayList<>();
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            String id = player.optString("entity_id");
            if (!id.equals(target)) {
                candidateIds.add(id);
                candidateNames.add(
                        player.optJSONObject("attributes").optString("friendly_name", id));
            }
        }

        final boolean[] checked = new boolean[candidateIds.size()];
        for (int i = 0; i < checked.length; i++) {
            checked[i] = previousMembers.contains(candidateIds.get(i));
        }
        previousMembers.add(target);

        DashboardPopup popup = new DashboardPopup(
                this, "Group rooms", DashboardPopup.MUSIC, DashboardPopup.MUSIC_ACCENT);
        LinearLayout list = popup.column();
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        popup.body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        for (int i = -1; i < candidateIds.size(); i++) {
            final int index = i;
            boolean anchor = i < 0;
            String name = anchor
                    ? anchorPlayer.optJSONObject("attributes").optString("friendly_name", target)
                    : candidateNames.get(i);

            CheckBox row = new CheckBox(this);
            row.setText(name);
            row.setTextSize(26);
            row.setTextColor(DashboardPopup.INK);
            try {
                row.setTypeface(Typeface.createFromFile(
                        "/system/fonts/Amazon-Ember-Regular.ttf"));
            } catch (Exception missingFont) {
                // Keep the platform typeface.
            }
            row.setButtonTintList(ColorStateList.valueOf(popup.accent));
            row.setPadding(popup.dp(12), popup.dp(8), popup.dp(16), popup.dp(8));
            row.setMinimumHeight(popup.dp(72));
            row.setChecked(anchor || checked[index]);
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x28ffffff),
                    popup.shape(0xff453653, 12),
                    popup.shape(Color.WHITE, 12)));
            row.setEnabled(!anchor);
            if (anchor) {
                row.setContentDescription(name + ", selected room");
            } else {
                row.setOnCheckedChangeListener(
                        (button, value) -> checked[index] = value);
            }
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            popup.gap(list, 6);
        }

        popup.actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        popup.actions.addView(popup.button(
                "Cancel",
                DashboardPopup.MUTED,
                Color.TRANSPARENT,
                popup::dismiss));
        popup.actions.addView(popup.button(
                "Apply",
                DashboardPopup.MUSIC,
                popup.accent,
                () -> {
                    popup.dismiss();
                    if (dialogGeneration != generation
                            || client != currentClient
                            || !target.equals(selected)) {
                        return;
                    }
                    command(() -> {
                        Set<String> desired = new HashSet<>();
                        desired.add(target);
                        for (int i = 0; i < candidateIds.size(); i++) {
                            if (checked[i]) {
                                desired.add(candidateIds.get(i));
                            }
                        }
                        if (desired.equals(previousMembers)) {
                            return;
                        }

                        String previousLeader = members != null && members.length() > 0
                                ? members.optString(0, target)
                                : target;
                        String coordinator = desired.contains(previousLeader)
                                ? previousLeader
                                : target;
                        if (!coordinator.equals(previousLeader)
                                && previousMembers.contains(coordinator)) {
                            currentClient.call(
                                    "media_player",
                                    "unjoin",
                                    new JSONObject().put("entity_id", coordinator),
                                    false);
                        }
                        for (String member : previousMembers) {
                            if (!desired.contains(member) && !member.equals(previousLeader)) {
                                currentClient.call(
                                        "media_player",
                                        "unjoin",
                                        new JSONObject().put("entity_id", member),
                                        false);
                            }
                        }

                        JSONArray joining = new JSONArray();
                        for (String member : desired) {
                            if (!member.equals(coordinator)
                                    && (!previousMembers.contains(member)
                                            || !coordinator.equals(previousLeader))) {
                                joining.put(member);
                            }
                        }
                        if (joining.length() > 0) {
                            currentClient.call(
                                    "media_player",
                                    "join",
                                    new JSONObject()
                                            .put("entity_id", coordinator)
                                            .put("group_members", joining),
                                    false);
                        }
                    });
                }));
        popup.open(false, Math.min(850, 210 + 78 * (candidateIds.size() + 1)));
    }

    private static final class BrowsePage {
        final String type;
        final String id;
        final String title;

        BrowsePage(String type, String id, String title) {
            this.type = type;
            this.id = id;
            this.title = title;
        }
    }

    private void browse(String type, String id) {
        if (client == null || selected.isEmpty() || player(selected) == null) {
            return;
        }
        final HaClient currentClient = client;
        final String target = leader();
        final long browseGeneration = generation;
        String title = "favorites".equals(type) ? "Favorites" : "Your music";

        DashboardPopup popup = new DashboardPopup(
                this, title, DashboardPopup.MUSIC, DashboardPopup.MUSIC_ACCENT);
        LinearLayout.LayoutParams footerParams =
                (LinearLayout.LayoutParams) popup.actions.getLayoutParams();
        footerParams.topMargin = popup.dp(20);
        popup.actions.setLayoutParams(footerParams);
        popup.body.setClipChildren(true);
        popup.body.setClipToPadding(true);

        Deque<BrowsePage> pages = new ArrayDeque<>();
        pages.push(new BrowsePage(type, id, title));

        final class Browser {
            int request;

            boolean isValid(int serial) {
                return popup.isShowing()
                        && request == serial
                        && currentClient == client
                        && browseGeneration == generation
                        && target.equals(leader());
            }

            void load() {
                final int serial = ++request;
                BrowsePage page = pages.peek();
                popup.title.setText(page.title);
                popup.body.removeAllViews();
                popup.actions.removeAllViews();
                popup.body.addView(
                        popup.label("Loading music…", 24, DashboardPopup.MUTED, false));

                if (pages.size() > 1) {
                    popup.actions.addView(popup.button(
                            "‹ Back",
                            popup.accent,
                            0xff4a3a5b,
                            () -> {
                                pages.pop();
                                load();
                            }));
                }
                popup.actions.addView(
                        new View(MainActivity.this),
                        new LinearLayout.LayoutParams(0, 1, 1));
                popup.actions.addView(popup.button(
                        "Close",
                        popup.accent,
                        0xff4a3a5b,
                        popup::dismiss));

                ioExecutor.execute(() -> {
                    try {
                        JSONArray children =
                                currentClient.browse(target, page.type, page.id);
                        mainHandler.post(() -> {
                            if (!isValid(serial)) {
                                return;
                            }
                            popup.body.removeAllViews();
                            if (children.length() == 0) {
                                popup.body.addView(popup.label(
                                        "No music available in this folder",
                                        24,
                                        DashboardPopup.MUTED,
                                        false));
                                return;
                            }

                            LinearLayout list = popup.column();
                            ScrollView scroll = new ScrollView(MainActivity.this);
                            scroll.setVerticalScrollBarEnabled(false);
                            scroll.setClipChildren(true);
                            scroll.setClipToPadding(true);
                            scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
                            popup.body.addView(
                                    scroll,
                                    new LinearLayout.LayoutParams(-1, -1));

                            for (int i = 0; i < children.length(); i++) {
                                JSONObject item = children.optJSONObject(i);
                                if (item == null) {
                                    continue;
                                }
                                String name = item.optString("title", "Music");
                                String mediaType = item.optString("media_content_type");
                                String mediaId = item.optString("media_content_id");
                                boolean expand = item.optBoolean("can_expand");
                                boolean play = item.optBoolean("can_play");

                                LinearLayout entry = popup.row();
                                entry.setPadding(
                                        popup.dp(16),
                                        popup.dp(10),
                                        popup.dp(12),
                                        popup.dp(10));
                                entry.setMinimumHeight(popup.dp(68));
                                entry.setBackground(new RippleDrawable(
                                        ColorStateList.valueOf(0x28ffffff),
                                        popup.shape(0xff453653, 12),
                                        popup.shape(Color.WHITE, 12)));

                                TextView label = popup.label(
                                        name, 24, DashboardPopup.INK, false);
                                label.setMaxLines(2);
                                entry.addView(
                                        label,
                                        new LinearLayout.LayoutParams(0, -2, 1));
                                if (expand || play) {
                                    entry.addView(popup.label(
                                            expand ? "›" : "▶",
                                            24,
                                            popup.accent,
                                            true));
                                }
                                entry.setOnClickListener(view -> {
                                    if (!isValid(serial)) {
                                        return;
                                    }
                                    if (expand) {
                                        pages.push(new BrowsePage(mediaType, mediaId, name));
                                        load();
                                    } else if (play) {
                                        popup.dismiss();
                                        command(() -> currentClient.call(
                                                "media_player",
                                                "play_media",
                                                new JSONObject()
                                                        .put("entity_id", target)
                                                        .put("media_content_type", mediaType)
                                                        .put("media_content_id", mediaId),
                                                false));
                                    }
                                });
                                if (!expand && !play) {
                                    entry.setAlpha(0.55f);
                                }
                                list.addView(entry, new LinearLayout.LayoutParams(-1, -2));
                                popup.gap(list, 6);
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (!isValid(serial)) {
                                return;
                            }
                            popup.body.removeAllViews();
                            popup.body.addView(popup.label(
                                    "Music library is unavailable",
                                    24,
                                    DashboardPopup.MUTED,
                                    false));
                            popup.body.addView(popup.button(
                                    "Retry",
                                    popup.accent,
                                    0xff4a3a5b,
                                    this::load));
                        });
                    }
                });
            }
        }

        popup.open(false, 760);
        new Browser().load();
    }

    @Override
    public void onSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(32, 16, 32, 8);

        EditText url = new EditText(this);
        url.setSingleLine();
        url.setHint("Home Assistant URL");
        url.setText(config.optString("url"));
        form.addView(url);

        EditText token = new EditText(this);
        token.setSingleLine();
        token.setInputType(129);
        token.setHint("Token (leave empty to keep current)");
        form.addView(token);

        Button list = new Button(this);
        list.setText("List: " + config.optString("todoName", "Grocery List"));
        list.setOnClickListener(view -> chooseTodo());
        form.addView(list);

        Button music = new Button(this);
        music.setText("Browse Sonos music");
        music.setOnClickListener(view -> browse(null, null));
        form.addView(music);

        new AlertDialog.Builder(this)
                .setTitle("Home settings")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String nextUrl = url.getText().toString().trim();
                    if (!nextUrl.startsWith("http://") && !nextUrl.startsWith("https://")) {
                        nextUrl = "http://" + nextUrl;
                    }
                    try {
                        String nextToken = token.getText().toString().trim();
                        new HaClient(
                                nextUrl,
                                nextToken.isEmpty()
                                        ? config.optString("token")
                                        : nextToken);
                        config.put("url", nextUrl);
                        if (!nextToken.isEmpty()) {
                            config.put("token", nextToken);
                        }
                        saveConfig();
                        loadConfig();
                        render();
                        refresh(true);
                    } catch (Exception e) {
                        toast("Enter a valid URL and token");
                    }
                })
                .show();
    }

    private void chooseTodo() {
        JSONArray states = feed != null && feed.isReady() ? feed.snapshot() : allStates;
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject state = states.optJSONObject(i);
            String id = state.optString("entity_id");
            if (id.startsWith("todo.")) {
                ids.add(id);
                names.add(state.optJSONObject("attributes").optString("friendly_name", id));
            }
        }
        if (ids.isEmpty()) {
            toast("Connect Home Assistant to choose a list");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Shopping list")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    todo = ids.get(which);
                    try {
                        config.put("todoName", names.get(which));
                    } catch (Exception ignored) {
                        // The selected id still applies for this session.
                    }
                    saveConfig();
                    items = new JSONArray();
                    lastTodo = 0;
                    render();
                    refresh(true);
                })
                .show();
    }
}
