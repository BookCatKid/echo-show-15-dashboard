package com.bookcatkid.launcher;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Household control overlay built on the dashboard's Home Assistant client.
 *
 * <p>The entity identifiers below belong to one installation. Replace them with the
 * entities of the household you are deploying to.
 */
final class SmartHomeOverlay {

    private static final int BACKGROUND = 0xff22232c;
    private static final int TILE = 0xff32343e;
    private static final int LIGHT = 0xffffd49f;
    private static final int CLIMATE = 0xffa9caff;
    private static final int FAN = 0xffa5e5ce;
    private static final int INK = DashboardPopup.INK;
    private static final int MUTED = DashboardPopup.MUTED;
    private static final int COOL = 0xffa9caff;
    private static final int HEAT = 0xffffb28b;

    private static final int LIGHT_ON_TILE = 0xff5a4938;
    private static final int CLIMATE_TILE = 0xff273346;
    private static final int FAN_TILE = 0xff253d39;
    private static final int SHADE_TILE = 0xff443a30;
    private static final int TARGET_PANEL = 0xff1e293a;
    private static final int TIMER_PANEL = 0xff1d312d;
    private static final int CONTROL_BACKGROUND = 0xff35445b;
    private static final int FAN_CONTROL_BACKGROUND = 0xff39534d;
    private static final int SLIDER_TRACK = 0xff52605d;
    private static final int RIPPLE = 0x28ffffff;

    private static final long LIVE_REFRESH_MS = 6000;
    private static final long REST_REFRESH_MS = 3000;
    private static final long BUSY_RETRY_MS = 1000;
    private static final int OVERLAY_HEIGHT_DP = 2000;
    private static final int EDGE_MARGIN_DP = 48;
    private static final int EDGE_MARGIN_BOTTOM_DP = 64;
    private static final int SECTION_GAP_DP = 18;
    private static final int TILE_GAP_DP = 10;

    private static final String WHOLE_HOUSE_FAN = "light.shelly0110dimg3_80b54e25b874";
    private static final String SLEEP_TIMER = "timer.bedtime";
    private static final String THERMOSTAT = "climate.thermostat";
    private static final String SHADE = "cover.somfy_bridge_somfy_shade";

    private static final String[][] INSIDE_LIGHTS = {
            {"switch.poang_chair_light", "Poang Chair Light"},
            {"switch.credenza_lamp", "Credenza Light"},
            {"switch.rf_bridge_downstairs_lights", "Dining Lights"},
            {"switch.rf_bridge_smart_hab_lights", "Bedroom Light"},
            {"switch.jenns_lamp", "Jenn's Lamp"},
            {"switch.bathroom_lamp", "Bathroom Light"},
            {"switch.yankees_light", "Yankee's Light"},
            {"light.simons_lights", "Simon's Lights"},
            {"switch.snake_light", "Snake Light"}
    };

    private static final String[][] OUTSIDE_LIGHTS = {
            {"light.front_door_left_light", "Front Door Left"},
            {"light.front_door_right_light", "Front Door Right"},
            {"switch.tp_link_smart_plug_1050_bush_lights", "Bush Lights"},
            {"switch.pool_light", "Pool Light"}
    };

    private static final Set<String> DIMMABLE_COLOR_MODES = new HashSet<>(Arrays.asList(
            "brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white"));

    private final Activity activity;
    private final HaClient client;
    private final HaStateFeed feed;
    private final DashboardPopup popup;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    private final Set<String> pendingActions = new HashSet<>();
    private final Map<String, JSONObject> states = new HashMap<>();
    private final Set<String> entityIds = new HashSet<>();
    private final ScrollView scrollView;
    private final LinearLayout content;

    private boolean closed;
    private boolean busy;
    private boolean dragging;
    private boolean redrawPending;
    private String signature = "";

    private final HaStateFeed.Listener listener = this::liveChanged;

    private final Runnable redraw = () -> {
        if (closed) {
            return;
        }
        if (dragging) {
            redrawPending = true;
            return;
        }
        redrawPending = false;
        String next = fingerprint();
        if (!next.equals(signature)) {
            signature = next;
            render();
        }
    };

    SmartHomeOverlay(Activity activity, HaClient client, HaStateFeed feed) {
        this.activity = activity;
        this.client = client;
        this.feed = feed;
        this.popup = new DashboardPopup(activity, "Smart Home", BACKGROUND, LIGHT);

        for (String[][] group : new String[][][]{INSIDE_LIGHTS, OUTSIDE_LIGHTS}) {
            for (String[] light : group) {
                entityIds.add(light[0]);
            }
        }
        Collections.addAll(entityIds,
                WHOLE_HOUSE_FAN,
                SLEEP_TIMER,
                THERMOSTAT,
                SHADE,
                "sensor.thermostat_temperature",
                "sensor.thermostat_humidity",
                "sensor.downstairs_temperature",
                "sensor.downstairs_outside_temperature");

        scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        content = popup.column();
        scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));
        popup.body.addView(scrollView, new LinearLayout.LayoutParams(-1, -1));

        popup.actions.setPadding(0, popup.dp(16), 0, 0);
        popup.actions.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        popup.actions.addView(popup.button("Refresh", LIGHT, TILE,
                () -> {
                    if (feed != null) {
                        feed.requestSnapshot();
                    }
                    refresh(true);
                }));
        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(-2, -2);
        closeParams.leftMargin = popup.dp(16);
        popup.actions.addView(popup.button("Close", INK, TILE, popup::dismiss), closeParams);

        popup.setOnDismissListener(dialog -> {
            closed = true;
            if (feed != null) {
                feed.remove(listener);
            }
            mainHandler.removeCallbacksAndMessages(null);
            ioExecutor.shutdown();
            commandExecutor.shutdown();
        });
    }

    void open() {
        popup.open(false, OVERLAY_HEIGHT_DP);

        Window window = popup.getWindow();
        DisplayMetrics metrics = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        window.setLayout(
                metrics.widthPixels - popup.dp(EDGE_MARGIN_DP),
                metrics.heightPixels - popup.dp(EDGE_MARGIN_BOTTOM_DP));
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        content.addView(label("Loading devices…", 26, MUTED, false));
        if (feed != null) {
            feed.add(listener);
        }
        refresh(true);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView view = popup.label(text, sizeSp, color, bold);
        try {
            view.setTypeface(Typeface.createFromFile(
                    "/system/fonts/Amazon-Ember-" + (bold ? "Medium" : "Regular") + ".ttf"));
        } catch (Exception missing) {
            // The popup already applies a sans-serif fallback.
        }
        return view;
    }

    private void liveChanged(Set<String> changed, boolean reset) {
        if (closed) {
            return;
        }
        if (feed == null || !feed.isReady()) {
            refresh(false);
            return;
        }
        if (reset || !Collections.disjoint(entityIds, changed)) {
            copyLiveStates();
            mainHandler.removeCallbacks(redraw);
            mainHandler.post(redraw);
        }
    }

    private void copyLiveStates() {
        states.clear();
        for (String id : entityIds) {
            JSONObject value = feed.get(id);
            if (value != null) {
                states.put(id, value);
            }
        }
    }

    private void refresh(boolean force) {
        if (closed) {
            return;
        }
        mainHandler.removeCallbacks(poll);

        if (feed != null && feed.isReady()) {
            copyLiveStates();
            if (force) {
                signature = "";
            }
            redraw.run();
            mainHandler.postDelayed(poll, LIVE_REFRESH_MS);
            return;
        }

        if (busy || dragging) {
            mainHandler.postDelayed(poll, BUSY_RETRY_MS);
            return;
        }

        busy = true;
        ioExecutor.execute(() -> {
            try {
                JSONArray array = client.getStates();
                Map<String, JSONObject> result = new HashMap<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject state = array.optJSONObject(i);
                    if (state != null && entityIds.contains(state.optString("entity_id"))) {
                        result.put(state.optString("entity_id"), state);
                    }
                }
                mainHandler.post(() -> {
                    if (closed) {
                        return;
                    }
                    busy = false;
                    if (feed != null && feed.isReady()) {
                        copyLiveStates();
                    } else {
                        states.clear();
                        states.putAll(result);
                    }
                    if (force) {
                        signature = "";
                    }
                    redraw.run();
                    mainHandler.postDelayed(poll, REST_REFRESH_MS);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (closed) {
                        return;
                    }
                    busy = false;
                    if (feed != null && feed.isReady()) {
                        copyLiveStates();
                    } else {
                        states.clear();
                    }
                    signature = "";
                    redraw.run();
                    mainHandler.postDelayed(poll, REST_REFRESH_MS);
                });
            }
        });
    }

    private final Runnable poll = () -> refresh(false);

    private String fingerprint() {
        StringBuilder builder = new StringBuilder();
        for (String id : new TreeSet<>(entityIds)) {
            JSONObject state = states.get(id);
            if (state != null) {
                builder.append(id)
                        .append(state.optString("state"))
                        .append(state.optJSONObject("attributes"));
            }
        }
        if ("active".equals(stateOf(SLEEP_TIMER))) {
            builder.append(System.currentTimeMillis() / 60000);
        }
        return builder.toString();
    }

    private JSONObject attributes(String id) {
        JSONObject state = states.get(id);
        JSONObject attributes = state == null ? null : state.optJSONObject("attributes");
        return attributes == null ? new JSONObject() : attributes;
    }

    private String stateOf(String id) {
        JSONObject state = states.get(id);
        return state == null ? "unavailable" : state.optString("state", "unknown");
    }

    private boolean isAvailable(String id) {
        String state = stateOf(id);
        return !"unavailable".equals(state) && !"unknown".equals(state);
    }

    private static String domainOf(String id) {
        return id.substring(0, id.indexOf('.'));
    }

    private void callService(String id, String action, JSONObject extras) {
        if (closed || !isAvailable(id)) {
            return;
        }
        // Queue different devices immediately, but ignore repeat taps on a device until Home
        // Assistant has acknowledged the previous request.
        if (!pendingActions.add(id)) {
            return;
        }
        try {
            JSONObject data = extras == null ? new JSONObject() : extras;
            data.put("entity_id", id);
            commandExecutor.execute(() -> {
                String error = null;
                try {
                    client.call(domainOf(id), action, data, false);
                } catch (Exception e) {
                    error = "Action failed";
                }
                final String message = error;
                mainHandler.post(() -> {
                    pendingActions.remove(id);
                    if (closed) {
                        return;
                    }
                    if (message != null) {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    }
                    if (feed == null || !feed.isReady()) {
                        refresh(true);
                    } else {
                        copyLiveStates();
                        redraw.run();
                    }
                });
            });
        } catch (JSONException e) {
            pendingActions.remove(id);
        }
    }

    private static JSONObject arguments(String key, Object value) {
        JSONObject data = new JSONObject();
        try {
            data.put(key, value);
        } catch (JSONException invalid) {
            // Values passed here are always JSON-compatible.
        }
        return data;
    }

    private void toggle(String id) {
        callService(id, "on".equals(stateOf(id)) ? "turn_off" : "turn_on", null);
    }

    private void render() {
        int scrollY = scrollView.getScrollY();
        content.removeAllViews();

        section("Indoor lights", LIGHT);
        renderLights(INSIDE_LIGHTS);
        section("Outdoor lights", LIGHT);
        renderLights(OUTSIDE_LIGHTS);
        section("Whole house fan", FAN);
        renderFan();
        section("Shade", LIGHT);
        renderShade();
        section("Climate", CLIMATE);
        renderClimate();

        scrollView.post(() -> scrollView.scrollTo(0, scrollY));
    }

    private void section(String title, int color) {
        popup.gap(content, SECTION_GAP_DP);
        content.addView(label(title, 26, color, true));
        popup.gap(content, TILE_GAP_DP);
    }

    private LinearLayout tile(int background) {
        LinearLayout tile = popup.column();
        tile.setPadding(popup.dp(18), popup.dp(16), popup.dp(18), popup.dp(16));
        tile.setBackground(popup.shape(background, 16));
        return tile;
    }

    private void fill(LinearLayout row, View view, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.rightMargin = popup.dp(marginDp);
        row.addView(view, params);
    }

    private void addTile(LinearLayout row) {
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        popup.gap(content, TILE_GAP_DP);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
    }

    private boolean isDimmable(String id) {
        if (!id.startsWith("light.")) {
            return false;
        }
        JSONObject attributes = attributes(id);
        JSONArray modes = attributes.optJSONArray("supported_color_modes");
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                if (DIMMABLE_COLOR_MODES.contains(modes.optString(i))) {
                    return true;
                }
            }
        }
        return !attributes.isNull("brightness");
    }

    private void renderLights(String[][] group) {
        int columns = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;

        for (int index = 0; index < group.length; index += columns) {
            LinearLayout row = popup.row();
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                int position = index + column;
                if (position >= group.length) {
                    fill(row, new View(activity), column == columns - 1 ? 0 : 10);
                    continue;
                }
                fill(row, buildLightTile(group[position][0], group[position][1]),
                        column == columns - 1 ? 0 : 10);
            }
            addTile(row);
        }
    }

    private LinearLayout buildLightTile(String id, String name) {
        boolean on = "on".equals(stateOf(id));
        boolean available = isAvailable(id);
        boolean dimmable = isDimmable(id);
        int percent = on
                ? Math.max(1, Math.round(attributes(id).optInt("brightness", 255) * 100f / 255))
                : 0;

        LinearLayout card = tile(on ? LIGHT_ON_TILE : TILE);
        LinearLayout power = popup.column();
        LinearLayout top = popup.row();

        ImageView icon = new ImageView(activity);
        int iconResource = activity.getResources()
                .getIdentifier("ic_lightbulb", "drawable", activity.getPackageName());
        if (iconResource != 0) {
            icon.setImageResource(iconResource);
            icon.setColorFilter(on ? LIGHT : MUTED);
        }
        top.addView(icon, new LinearLayout.LayoutParams(popup.dp(36), popup.dp(36)));

        TextView nameView = label(name, 22, INK, true);
        nameView.setMaxLines(2);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = popup.dp(12);
        top.addView(nameView, nameParams);
        power.addView(top);
        popup.gap(power, 8);

        LinearLayout statusRow = popup.row();
        statusRow.addView(
                label(available ? (on ? "On" : "Off") : "Unavailable",
                        19, on ? LIGHT : MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        boolean showPercent = available && dimmable;
        boolean percentKnown = !attributes(id).isNull("brightness");
        String percentText = showPercent
                ? (on && !percentKnown ? "—" : percent + "%")
                : "";
        TextView value = label(percentText, 21, on ? LIGHT : MUTED, true);
        statusRow.addView(value);
        power.addView(statusRow);
        card.addView(power, new LinearLayout.LayoutParams(-1, -2));

        String description = name + ", " + (available ? (on ? "on" : "off") : "unavailable");
        power.setMinimumHeight(popup.dp(68));
        power.setContentDescription(description);
        power.setBackground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                popup.shape(Color.TRANSPARENT, 12),
                popup.shape(Color.WHITE, 12)));
        power.setEnabled(available);
        power.setOnClickListener(view -> toggle(id));
        card.setMinimumHeight(popup.dp(118));

        if (dimmable) {
            popup.gap(card, 4);
            if (available) {
                percentSlider(card, name + " brightness", percent, LIGHT, value, level -> {
                    if (level == percent) {
                        return;
                    }
                    if (level == 0) {
                        callService(id, "turn_off", null);
                    } else {
                        callService(id, "turn_on", arguments("brightness_pct", level));
                    }
                });
            } else {
                card.addView(label("Brightness unavailable", 18, MUTED, false));
            }
        } else {
            card.setContentDescription(description);
            card.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(RIPPLE),
                    popup.shape(on ? LIGHT_ON_TILE : TILE, 16),
                    popup.shape(Color.WHITE, 16)));
            card.setEnabled(available);
            card.setOnClickListener(view -> toggle(id));
        }
        return card;
    }

    private void renderClimate() {
        LinearLayout card = tile(CLIMATE_TILE);
        JSONObject attributes = attributes(THERMOSTAT);
        if (!isAvailable(THERMOSTAT)) {
            card.addView(label("Thermostat unavailable", 24, MUTED, false));
            addTile(card);
            return;
        }

        String mode = stateOf(THERMOSTAT);
        String action = attributes.optString("hvac_action", mode);
        int activeColor = "heat".equals(mode) ? HEAT : COOL;
        double current = attributes.optDouble("current_temperature", Double.NaN);
        double humidity = attributes.optDouble("current_humidity", Double.NaN);
        if (!Double.isFinite(humidity)) {
            try {
                humidity = Double.parseDouble(stateOf("sensor.thermostat_humidity"));
            } catch (Exception unknown) {
                // Keep humidity unavailable.
            }
        }

        LinearLayout header = popup.row();
        LinearLayout reading = popup.column();
        LinearLayout details = popup.column();
        reading.addView(label("Indoor temperature", 20, MUTED, false));
        reading.addView(label(degrees(current), 64, INK, true));
        header.addView(reading, new LinearLayout.LayoutParams(0, -2, 1));

        TextView hvac = label(
                capitalize(action),
                29,
                "heating".equals(action) ? HEAT : "cooling".equals(action) ? COOL : MUTED,
                true);
        hvac.setGravity(Gravity.END);
        details.addView(hvac);
        if (Double.isFinite(humidity)) {
            TextView humidityView = label(
                    String.format(Locale.US, "%.0f%% humidity", humidity), 22, MUTED, false);
            humidityView.setGravity(Gravity.END);
            details.addView(humidityView);
        }
        header.addView(details);
        card.addView(header);
        popup.gap(card, 16);

        JSONArray modes = attributes.optJSONArray("hvac_modes");
        LinearLayout modeButtons = popup.row();
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                String candidate = modes.optString(i);
                boolean selected = candidate.equals(mode);
                int selectedColor = "heat".equals(candidate)
                        ? HEAT
                        : "off".equals(candidate) ? MUTED : COOL;
                equal(modeButtons, control(
                                modeLabel(candidate),
                                selected ? BACKGROUND : INK,
                                selected ? selectedColor : CONTROL_BACKGROUND,
                                62,
                                () -> callService(
                                        THERMOSTAT,
                                        "set_hvac_mode",
                                        arguments("hvac_mode", candidate))),
                        62,
                        i == modes.length() - 1 ? 0 : 8);
            }
        }
        card.addView(modeButtons);

        if ("heat_cool".equals(mode)) {
            double low = attributes.optDouble("target_temp_low", Double.NaN);
            double high = attributes.optDouble("target_temp_high", Double.NaN);
            if (Double.isFinite(low) && Double.isFinite(high)) {
                popup.gap(card, 14);
                LinearLayout range = popup.row();
                equal(range, buildTargetPanel(attributes, "target_temp_low", "Heat to", low, HEAT),
                        -2, 10);
                equal(range, buildTargetPanel(attributes, "target_temp_high", "Cool to", high, COOL),
                        -2, 0);
                card.addView(range);
            }
        } else if (!"off".equals(mode)) {
            double target = attributes.optDouble("temperature", Double.NaN);
            if (Double.isFinite(target)) {
                popup.gap(card, 14);
                String label = "heat".equals(mode)
                        ? "Heat to"
                        : "cool".equals(mode) ? "Cool to" : "Target";
                card.addView(buildTargetPanel(
                        attributes, "temperature", label, target, activeColor));
            }
        }
        addTile(card);
    }

    private LinearLayout buildTargetPanel(
            JSONObject attributes, String key, String name, double value, int color) {
        double step = attributes.optDouble("target_temp_step", 1);
        final double delta = Double.isFinite(step) && step > 0 ? step : 1;

        LinearLayout panel = tile(TARGET_PANEL);
        panel.setPadding(popup.dp(12), popup.dp(12), popup.dp(12), popup.dp(12));

        TextView title = label(name, 22, color, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        popup.gap(panel, 8);

        LinearLayout row = popup.row();
        Button minus = control("−", color, CONTROL_BACKGROUND, 64,
                () -> setTarget(attributes, key, value - delta));
        Button plus = control("+", color, CONTROL_BACKGROUND, 64,
                () -> setTarget(attributes, key, value + delta));
        minus.setTextSize(34);
        plus.setTextSize(34);
        minus.setContentDescription("Decrease " + name.toLowerCase(Locale.US));
        plus.setContentDescription("Increase " + name.toLowerCase(Locale.US));

        double minimum = attributes.optDouble("min_temp", Double.NEGATIVE_INFINITY);
        double maximum = attributes.optDouble("max_temp", Double.POSITIVE_INFINITY);
        double low = attributes.optDouble("target_temp_low", Double.NEGATIVE_INFINITY);
        double high = attributes.optDouble("target_temp_high", Double.POSITIVE_INFINITY);
        minus.setEnabled(value - delta >= minimum
                && (!"target_temp_high".equals(key) || value - delta > low));
        plus.setEnabled(value + delta <= maximum
                && (!"target_temp_low".equals(key) || value + delta < high));

        row.addView(minus, new LinearLayout.LayoutParams(popup.dp(66), popup.dp(64)));
        TextView number = label(degrees(value), 42, color, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(plus, new LinearLayout.LayoutParams(popup.dp(66), popup.dp(64)));
        panel.addView(row);
        return panel;
    }

    private void setTarget(JSONObject attributes, String key, double value) {
        double minimum = attributes.optDouble("min_temp", value);
        double maximum = attributes.optDouble("max_temp", value);
        value = Math.max(minimum, Math.min(maximum, value));

        JSONObject data = arguments(key, value);
        if (!"temperature".equals(key)) {
            try {
                double low = "target_temp_low".equals(key)
                        ? value
                        : attributes.optDouble("target_temp_low");
                double high = "target_temp_high".equals(key)
                        ? value
                        : attributes.optDouble("target_temp_high");
                if (!Double.isFinite(low) || !Double.isFinite(high) || low >= high) {
                    return;
                }
                data.put("target_temp_low", low).put("target_temp_high", high);
            } catch (JSONException invalid) {
                return;
            }
        }
        callService(THERMOSTAT, "set_temperature", data);
    }

    private void renderFan() {
        LinearLayout card = tile(FAN_TILE);
        boolean on = "on".equals(stateOf(WHOLE_HOUSE_FAN));
        boolean available = isAvailable(WHOLE_HOUSE_FAN);
        int speed = on
                ? Math.round(attributes(WHOLE_HOUSE_FAN).optInt("brightness", 255) * 100f / 255)
                : 0;

        LinearLayout head = popup.row();
        head.addView(label(available ? (on ? "Running" : "Off") : "Unavailable",
                        36, FAN, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        Button power = control(on ? "Turn off" : "Turn on",
                on ? BACKGROUND : FAN,
                on ? FAN : FAN_CONTROL_BACKGROUND,
                64,
                () -> toggle(WHOLE_HOUSE_FAN));
        power.setEnabled(available);
        head.addView(power, new LinearLayout.LayoutParams(popup.dp(160), popup.dp(64)));
        card.addView(head);

        if (available) {
            popup.gap(card, 18);
            LinearLayout speedRow = popup.row();
            speedRow.addView(label("Fan speed", 22, INK, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = label(speed + "%", 32, FAN, true);
            speedRow.addView(value);
            card.addView(speedRow);

            percentSlider(card, "Fan speed", speed, FAN, value, this::setFanSpeed);

            LinearLayout presets = popup.row();
            for (int preset : new int[]{25, 50, 75, 100}) {
                equal(presets, control(
                                preset + "%",
                                speed == preset ? BACKGROUND : FAN,
                                speed == preset ? FAN : FAN_CONTROL_BACKGROUND,
                                58,
                                () -> setFanSpeed(preset)),
                        58,
                        preset == 100 ? 0 : 8);
            }
            card.addView(presets);
        }

        if (isAvailable(SLEEP_TIMER)) {
            popup.gap(card, 16);
            card.addView(buildSleepTimer(on));
        }
        addTile(card);
    }

    private LinearLayout buildSleepTimer(boolean fanRunning) {
        LinearLayout card = tile(TIMER_PANEL);
        String timer = stateOf(SLEEP_TIMER);
        boolean active = !"idle".equals(timer);

        LinearLayout row = popup.row();
        row.addView(label("Sleep timer", 23, INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (active) {
            String remaining = attributes(SLEEP_TIMER).optString("remaining", "");
            if ("active".equals(timer)) {
                try {
                    long seconds = Math.max(0, Duration.between(
                            Instant.now(),
                            OffsetDateTime.parse(
                                    attributes(SLEEP_TIMER).getString("finishes_at"))
                                    .toInstant()).getSeconds());
                    remaining = String.format(
                            Locale.US, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
                } catch (Exception unavailable) {
                    // Keep the reported remaining text.
                }
            }
            String text = "paused".equals(timer)
                    ? "Paused"
                    : remaining.isEmpty() ? "Running" : remaining + " left";
            row.addView(label(text, 23, FAN, true));
        } else {
            row.addView(label("Off", 22, MUTED, false));
        }
        card.addView(row);
        popup.gap(card, 10);

        LinearLayout actions = popup.row();
        if (active) {
            equal(actions, control("Cancel timer", FAN, FAN_CONTROL_BACKGROUND, 58,
                    () -> callService(SLEEP_TIMER, "cancel", null)), 58, 0);
        } else {
            for (int hours : new int[]{1, 2, 4}) {
                Button button = control(
                        hours + " hr",
                        FAN,
                        FAN_CONTROL_BACKGROUND,
                        58,
                        () -> callService(
                                SLEEP_TIMER,
                                "start",
                                arguments("duration",
                                        String.format(Locale.US, "%02d:00:00", hours))));
                button.setEnabled(fanRunning);
                button.setAlpha(fanRunning ? 1f : 0.55f);
                equal(actions, button, 58, hours == 4 ? 0 : 8);
            }
        }
        card.addView(actions);
        return card;
    }

    private void setFanSpeed(int speed) {
        if (speed == 0) {
            callService(WHOLE_HOUSE_FAN, "turn_off", null);
        } else {
            callService(WHOLE_HOUSE_FAN, "turn_on", arguments("brightness_pct", speed));
        }
    }

    private void renderShade() {
        LinearLayout card = tile(SHADE_TILE);
        boolean available = isAvailable(SHADE);
        JSONObject attributes = attributes(SHADE);
        int features = attributes.optInt("supported_features");
        int position = attributes.optInt("current_position", -1);

        LinearLayout head = popup.row();
        head.addView(label(available ? capitalize(stateOf(SHADE)) : "Unavailable",
                        36, LIGHT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView percent = label(available && position >= 0 ? position + "%" : "",
                32, LIGHT, true);
        head.addView(percent);
        card.addView(head);

        if (available && position >= 0 && (features & 4) != 0) {
            popup.gap(card, 10);
            card.addView(label(
                    attributes.optBoolean("assumed_state") ? "Estimated opening" : "Opening",
                    20,
                    MUTED,
                    false));
            percentSlider(card, "Shade opening", position, LIGHT, percent,
                    level -> callService(
                            SHADE, "set_cover_position", arguments("position", level)));
        }

        popup.gap(card, 12);
        LinearLayout row = popup.row();
        String[] names = {"Open", "Stop", "Close"};
        String[] actions = {"open_cover", "stop_cover", "close_cover"};
        int[] featureMasks = {1, 8, 2};
        for (int i = 0; i < names.length; i++) {
            if ((features & featureMasks[i]) == 0) {
                continue;
            }
            String action = actions[i];
            Button button = control(names[i], LIGHT, LIGHT_ON_TILE, 76,
                    () -> callService(SHADE, action, null));
            button.setEnabled(available);
            equal(row, button, 76, i == names.length - 1 ? 0 : 10);
        }
        card.addView(row);
        addTile(card);
    }

    private void percentSlider(
            LinearLayout parent,
            String description,
            int percent,
            int color,
            TextView value,
            PercentAction action) {
        SeekBar bar = new SeekBar(activity);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, percent)));

        GradientDrawable track = popup.shape(SLIDER_TRACK, 7);
        GradientDrawable progress = popup.shape(color, 7);
        GradientDrawable thumb = popup.shape(color, 16);
        track.setSize(1, popup.dp(14));
        progress.setSize(1, popup.dp(14));
        thumb.setSize(popup.dp(32), popup.dp(32));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        });
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, popup.dp(14));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        bar.setProgressDrawable(layers);
        bar.setThumb(thumb);
        bar.setThumbOffset(popup.dp(16));
        bar.setSplitTrack(false);
        bar.setPadding(popup.dp(18), 0, popup.dp(18), 0);
        bar.setContentDescription(description);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, popup.dp(64)));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser) {
                    value.setText(progressValue + "%");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                action.run(seekBar.getProgress());
                if (redrawPending) {
                    mainHandler.post(redraw);
                }
            }
        });
    }

    private interface PercentAction {
        void run(int value);
    }

    private Button control(String name, int foreground, int background, int heightDp,
            Runnable action) {
        Button button = popup.button(name, foreground, background, action);
        button.setTextSize(23);
        button.setMinHeight(popup.dp(heightDp));
        button.setMinimumHeight(popup.dp(heightDp));
        button.setPadding(popup.dp(8), 0, popup.dp(8), 0);
        try {
            button.setTypeface(
                    Typeface.createFromFile("/system/fonts/Amazon-Ember-Medium.ttf"));
        } catch (Exception missing) {
            // Keep the button's default typeface.
        }
        return button;
    }

    private void equal(LinearLayout row, View view, int heightDp, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, heightDp < 0 ? heightDp : popup.dp(heightDp), 1);
        params.rightMargin = popup.dp(marginDp);
        row.addView(view, params);
    }

    private static String degrees(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return String.format(
                Locale.US, value == Math.rint(value) ? "%.0f°" : "%.1f°", value);
    }

    private static String modeLabel(String mode) {
        return "heat_cool".equals(mode) ? "Auto" : capitalize(mode);
    }
}
