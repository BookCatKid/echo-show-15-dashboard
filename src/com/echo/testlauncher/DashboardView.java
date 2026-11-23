package com.echo.testlauncher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native, network-independent dashboard. Public update methods may be called from any thread;
 * they marshal to the main thread before touching views.
 */
public final class DashboardView extends LinearLayout {

    public interface Actions {
        void onSettings();

        void onStockHome();

        void onFireTv();

        void onSmartHome();

        void onPlayerSelected(String id);

        void onMediaAction(String action);

        void onVolume(float volume);

        void onGroup();

        void onTodoToggle(String uid, boolean complete);

        void onTodoAdd(String title);

        void onRefresh();
    }

    private static final int INK = 0xfff8f5ff;
    private static final int MUTED = 0xffc1bdce;
    private static final int BACKGROUND = 0xff171820;
    private static final int CALENDAR = 0xff273346;
    private static final int MONTH = 0xff30405a;
    private static final int MUSIC = 0xff392c49;
    private static final int SHOPPING = 0xff253d39;
    private static final int CALENDAR_ACCENT = 0xffa9caff;
    private static final int MUSIC_ACCENT = 0xffdfbaf8;
    private static final int SHOPPING_ACCENT = 0xffa5e5ce;
    private static final int WEATHER_ACCENT = 0xffffd49f;

    private static final int SMART_HOME_CARD = 0xff443a30;
    private static final int DAY_HEADER = 0xff3b5071;
    private static final int WEEKDAY_LABEL = 0xffc9d5e7;
    private static final int OUTSIDE_MONTH = 0xff8b9ab3;
    private static final int SELECTED_DAY = 0xff546b91;
    private static final int ARTWORK_BACKING = 0xff4a3a5b;
    private static final int VOLUME_TRACK = 0xff62526e;
    private static final int AGENDA_SECONDARY = 0xffc2cee1;
    private static final int AGENDA_ALL_DAY = 0xfff0ba9e;
    private static final int FOOTER_TEXT = 0xffe5bd89;
    private static final int INPUT_BACKGROUND = 0xff344e47;
    private static final int ROOM_SELECTED_BACKGROUND = 0xff554267;
    private static final int ROOM_BACKGROUND = 0xff443651;
    private static final int RIPPLE = 0x28ffffff;

    private static final long CALENDAR_IDLE_RESET_MS = 30000;
    private static final long VOLUME_SETTLE_MS = 5000;
    private static final float MIN_SCALE = 0.72f;
    private static final float MAX_SCALE = 1.12f;
    private static final float SCALE_TOLERANCE = 0.03f;

    private static final DateTimeFormatter MONTH_TITLE =
            DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final DateTimeFormatter DAY_ACCESSIBILITY =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");
    private static final DateTimeFormatter WEEKDAY_NAME =
            DateTimeFormatter.ofPattern("EEEE");
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("MMM d");
    private static final String[] WEEKDAY_LABELS = {
            "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
    };

    private final Actions actions;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Typeface regular = loadFont("Regular");
    private final Typeface medium = loadFont("Medium");

    private JSONObject state = new JSONObject();

    private TextView clockView;
    private TextView dateView;
    private TextView temperatureView;
    private TextView weatherSummaryView;
    private ImageView weatherIconView;
    private TextView monthTitleView;
    private LinearLayout monthGrid;
    private ScrollView agendaScroll;
    private LinearLayout agendaList;
    private ScrollView todoScroll;
    private LinearLayout todoList;
    private TextView musicTitleView;
    private TextView musicArtistView;
    private TextView listTitleView;
    private TextView footerView;
    private Button roomButton;
    private Button playButton;
    private Button groupButton;
    private Button browseButton;
    private Button favoritesButton;
    private Button previousButton;
    private Button nextButton;
    private Button muteButton;
    private SeekBar volumeBar;
    private ImageView artworkView;
    private View artworkPlaceholder;

    private Bitmap artwork;
    private LocalDate selectedDay;
    private YearMonth displayedMonth = YearMonth.now();
    private String agendaKey = "";
    private String todoKey = "";
    private boolean landscape;
    private float scale = 1f;
    private int lastWidth;
    private int lastHeight;

    private boolean draggingVolume;
    private long volumeTouchedAt;
    private String pendingVolumeRoom = "";
    private int pendingVolume = -1;

    private boolean agendaTouchActive;

    private final Runnable resetCalendar = () -> {
        if (agendaTouchActive || agendaScroll == null) {
            return;
        }
        boolean changed = selectedDay != null || !displayedMonth.equals(YearMonth.now());
        selectedDay = null;
        displayedMonth = YearMonth.now();
        if (changed) {
            renderMonth();
            renderCalendarAgenda();
            agendaScroll.scrollTo(0, 0);
        } else {
            agendaScroll.smoothScrollTo(0, 0);
        }
    };

    public DashboardView(Context context, Actions actions) {
        super(context);
        if (actions == null) {
            throw new IllegalArgumentException("Actions is required");
        }
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(BACKGROUND);
        setClipChildren(false);
        build();
        applyState();
    }

    private static Typeface loadFont(String weight) {
        try {
            return Typeface.createFromFile("/system/fonts/Amazon-Ember-" + weight + ".ttf");
        } catch (Exception missing) {
            return Typeface.create(
                    "Medium".equals(weight) ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL);
        }
    }

    private int dp(float value) {
        return Math.round(value * scale * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sizeSp * scale);
        view.setTextColor(color);
        view.setTypeface(bold ? medium : regular);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView singleLine(String value, float sizeSp, int color, boolean bold) {
        TextView view = text(value, sizeSp, color, bold);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private void gap(LinearLayout parent, int heightDp) {
        parent.addView(new View(getContext()),
                new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private void spacer(LinearLayout parent) {
        parent.addView(new View(getContext()),
                new LinearLayout.LayoutParams(0, 1, 1));
    }

    private Button button(String label, int foreground, int background, Runnable click) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(22 * scale);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTypeface(medium);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x22ffffff),
                shape(background, 12),
                shape(Color.WHITE, 12)));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(56));
        button.setMinimumHeight(dp(56));
        button.setMinWidth(dp(56));
        button.setMinimumWidth(dp(56));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> click.run());
        button.setContentDescription(label);
        return button;
    }

    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(28), dp(26), dp(28), dp(24));
        card.setBackground(shape(color, 24));
        card.setClipToOutline(true);
        return card;
    }

    private void weighted(
            LinearLayout parent, View child, float weight, int marginDp, boolean horizontal) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                weight);
        if (horizontal) {
            params.setMarginEnd(dp(marginDp));
        } else {
            params.bottomMargin = dp(marginDp);
        }
        parent.addView(child, params);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0 || (width == lastWidth && height == lastHeight)) {
            return;
        }
        lastWidth = width;
        lastHeight = height;

        float density = getResources().getDisplayMetrics().density;
        boolean wide = width > height;
        float target = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                (wide ? height : width) / density / 1080f));
        if (wide != landscape || Math.abs(target - scale) > SCALE_TOLERANCE) {
            landscape = wide;
            scale = target;
            mainHandler.post(() -> {
                build();
                applyState();
            });
        }
    }

    private Drawable glyph(String name, int color, int sizeDp) {
        int id = getResources().getIdentifier(
                "ic_" + name, "drawable", getContext().getPackageName());
        if (id == 0) {
            return null;
        }
        Drawable icon = getContext().getDrawable(id).mutate();
        icon.setTint(color);
        icon.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return icon;
    }

    private void setGlyph(Button button, String name, int color, int sizeDp) {
        button.setText("");
        button.setPadding(0, 0, 0, 0);
        button.setCompoundDrawables(null, null, null, null);
        button.setGravity(Gravity.CENTER);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x24ffffff),
                shape(Color.TRANSPARENT, 10),
                shape(Color.WHITE, 10)));

        Drawable icon = glyph(name, color, sizeDp);
        if (icon == null) {
            return;
        }
        LayerDrawable layer = new LayerDrawable(new Drawable[]{icon});
        layer.setLayerWidth(0, dp(sizeDp));
        layer.setLayerHeight(0, dp(sizeDp));
        layer.setLayerGravity(0, Gravity.CENTER);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x24ffffff),
                layer,
                shape(Color.WHITE, 10)));
    }

    private Button iconButton(
            String name, String description, int color, int sizeDp, Runnable click) {
        Button button = button("", color, Color.TRANSPARENT, click);
        setGlyph(button, name, color, sizeDp);
        button.setContentDescription(description);
        return button;
    }

    private void build() {
        removeAllViews();
        agendaKey = "";
        todoKey = "";
        setPadding(dp(30), dp(28), dp(30), dp(24));

        LinearLayout body = column();
        addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout calendar = buildCalendar();
        LinearLayout music = buildMusic();
        LinearLayout shopping = buildShopping();

        if (landscape) {
            LinearLayout top = row();
            weighted(top, shopping, 1f, 18, true);
            weighted(top, buildSmartHome(), 0.72f, 18, true);
            weighted(top, music, 1.35f, 0, true);
            weighted(body, top, 1f, 18, false);
            weighted(body, calendar, 1.02f, 0, false);
        } else {
            LinearLayout top = row();
            weighted(top, shopping, 1f, 18, true);
            weighted(top, buildSmartHome(), 1f, 0, true);
            weighted(body, top, 0.82f, 18, false);
            weighted(body, music, 0.94f, 18, false);
            weighted(body, calendar, 1.15f, 0, false);
        }

        footerView = singleLine("", 18, FOOTER_TEXT, false);
        footerView.setVisibility(GONE);
        addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        gap(this, 22);
        addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        updateClock();
        showArtwork();
    }

    private LinearLayout buildHeader() {
        LinearLayout header = row();

        LinearLayout heading = column();
        clockView = singleLine("", landscape ? 62 : 66, Color.WHITE, false);
        dateView = singleLine("", 24, MUTED, false);
        heading.addView(clockView);
        gap(heading, 3);
        heading.addView(dateView);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout weather = row();
        weatherIconView = new ImageView(getContext());
        weather.addView(weatherIconView, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout conditions = column();
        conditions.setPadding(dp(12), 0, dp(8), 0);
        temperatureView = singleLine("", 36, INK, false);
        weatherSummaryView = text("", 19, MUTED, false);
        weatherSummaryView.setMaxLines(3);
        conditions.addView(temperatureView);
        conditions.addView(weatherSummaryView);
        weather.addView(conditions, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(weather, new LinearLayout.LayoutParams(dp(235), -2));

        Button refresh = iconButton(
                "refresh", "Refresh dashboard", INK, 30, () -> actions.onRefresh());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(56), dp(56)));

        Button home = iconButton("home", "Amazon home", INK, 30, () -> actions.onStockHome());
        header.addView(home, new LinearLayout.LayoutParams(dp(56), dp(56)));

        refresh.setOnLongClickListener(view -> {
            actions.onSettings();
            return true;
        });

        header.addView(buildFireTvButton(), new LinearLayout.LayoutParams(dp(72), dp(66)));
        return header;
    }

    private View buildFireTvButton() {
        ImageView fireTv = new ImageView(getContext());
        fireTv.setContentDescription("Fire TV");
        fireTv.setClickable(true);
        fireTv.setFocusable(true);
        fireTv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            android.content.res.Resources stock = getContext()
                    .getPackageManager()
                    .getResourcesForApplication("com.amazon.paladin");
            int logo = stock.getIdentifier("ic_ftv_logo", "drawable", "com.amazon.paladin");
            int backing = stock.getIdentifier("ic_ftv_backing", "drawable", "com.amazon.paladin");
            if (logo == 0 || backing == 0) {
                throw new IllegalStateException("Fire TV resources unavailable");
            }
            fireTv.setImageDrawable(stock.getDrawable(logo, null));
            fireTv.setBackground(stock.getDrawable(backing, null));
        } catch (Exception unavailable) {
            fireTv.setImageDrawable(glyph("fire_tv", INK, 30));
            fireTv.setPadding(dp(12), dp(12), dp(12), dp(12));
            fireTv.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x24ffffff),
                    shape(Color.TRANSPARENT, 10),
                    shape(Color.WHITE, 10)));
        }
        fireTv.setOnClickListener(view -> actions.onFireTv());
        return fireTv;
    }

    private LinearLayout buildSmartHome() {
        LinearLayout entry = card(SMART_HOME_CARD);
        entry.setGravity(Gravity.CENTER);

        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(glyph("home", WEATHER_ACCENT, 96));
        entry.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        gap(entry, 18);

        TextView label = text("Smart Home", 34, WEATHER_ACCENT, true);
        label.setGravity(Gravity.CENTER);
        entry.addView(label);

        entry.setBackground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                shape(SMART_HOME_CARD, 24),
                shape(Color.WHITE, 24)));
        entry.setContentDescription("Smart Home");
        entry.setFocusable(true);
        entry.setOnClickListener(view -> actions.onSmartHome());
        return entry;
    }

    private LinearLayout buildCalendar() {
        LinearLayout outer = row();
        outer.setGravity(Gravity.TOP);
        outer.setBackground(shape(CALENDAR, 24));
        outer.setClipToOutline(true);

        LinearLayout left = column();
        left.setPadding(dp(24), dp(22), dp(24), dp(22));
        agendaList = column();
        agendaScroll = new AgendaScrollView();
        agendaScroll.setVerticalScrollBarEnabled(false);
        agendaScroll.addView(agendaList, new ScrollView.LayoutParams(-1, -2));
        left.addView(agendaScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        outer.addView(left, new LinearLayout.LayoutParams(0, -1, 1.53f));

        LinearLayout right = column();
        right.setBackgroundColor(MONTH);
        right.setPadding(dp(16), dp(22), dp(16), dp(22));

        LinearLayout navigation = row();
        Button previous = iconButton(
                "chevron_left", "Previous month", INK, 28, () -> changeMonth(-1));
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        monthTitleView = singleLine("", 26, CALENDAR_ACCENT, true);
        monthTitleView.setGravity(Gravity.CENTER);
        navigation.addView(monthTitleView, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = iconButton(
                "chevron_right", "Next month", INK, 28, () -> changeMonth(1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        right.addView(navigation);
        gap(right, 12);

        monthGrid = column();
        right.addView(monthGrid, new LinearLayout.LayoutParams(-1, -2));
        outer.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        return outer;
    }

    /** Tracks touch and scrolling so the idle reset does not fight the user. */
    private final class AgendaScrollView extends ScrollView {

        AgendaScrollView() {
            super(DashboardView.this.getContext());
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                agendaTouchActive = true;
                calendarIdle();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                agendaTouchActive = false;
                calendarIdle();
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            if (y != oldY && !agendaTouchActive) {
                calendarIdle();
            }
        }
    }

    private void calendarIdle() {
        mainHandler.removeCallbacks(resetCalendar);
        mainHandler.postDelayed(resetCalendar, CALENDAR_IDLE_RESET_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(resetCalendar);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        calendarIdle();
    }

    private void changeMonth(int offset) {
        displayedMonth = displayedMonth.plusMonths(offset);
        selectedDay = null;
        renderMonth();
        renderCalendarAgenda();
        agendaScroll.scrollTo(0, 0);
        calendarIdle();
    }

    private LocalDate dayOf(JSONObject event) {
        try {
            return Instant.parse(event.optString("start"))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        } catch (Exception invalid) {
            return null;
        }
    }

    private void renderMonth() {
        if (monthGrid == null) {
            return;
        }
        monthGrid.removeAllViews();
        monthTitleView.setText(displayedMonth.format(MONTH_TITLE));

        Set<LocalDate> eventDays = new HashSet<>();
        JSONObject calendar = state.optJSONObject("calendar");
        JSONArray events = calendar == null ? null : calendar.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) {
                    continue;
                }
                LocalDate day = dayOf(event);
                if (day != null) {
                    eventDays.add(day);
                }
            }
        }

        LinearLayout week = row();
        for (String label : WEEKDAY_LABELS) {
            TextView view = singleLine(label, 15, WEEKDAY_LABEL, false);
            view.setGravity(Gravity.CENTER);
            week.addView(view, new LinearLayout.LayoutParams(0, dp(32), 1));
        }
        monthGrid.addView(week);

        LocalDate first = displayedMonth.atDay(1);
        LocalDate gridStart = first.minusDays(first.getDayOfWeek().getValue() % 7);
        LocalDate today = LocalDate.now();

        for (int weekIndex = 0; weekIndex < 6; weekIndex++) {
            LinearLayout line = row();
            for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
                final LocalDate day = gridStart.plusDays(weekIndex * 7L + dayIndex);
                line.addView(buildDayCell(day, eventDays, today),
                        new LinearLayout.LayoutParams(0, -2, 1));
            }
            monthGrid.addView(line);
        }
    }

    private View buildDayCell(LocalDate day, Set<LocalDate> eventDays, LocalDate today) {
        boolean inMonth = YearMonth.from(day).equals(displayedMonth);
        boolean selected = day.equals(selectedDay);
        boolean isToday = day.equals(today);

        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.setMinimumHeight(dp(landscape ? 37 : 77));

        TextView number = singleLine(
                String.valueOf(day.getDayOfMonth()),
                24,
                inMonth ? INK : OUTSIDE_MONTH,
                false);
        number.setGravity(Gravity.CENTER);
        if (isToday || selected) {
            number.setTextColor(isToday ? MONTH : INK);
            number.setBackground(shape(isToday ? CALENDAR_ACCENT : SELECTED_DAY, 24));
        }
        int numberSize = dp(landscape ? 30 : 46);
        cell.addView(number, new LinearLayout.LayoutParams(numberSize, numberSize));

        View dot = new View(getContext());
        dot.setBackground(shape(
                eventDays.contains(day) ? CALENDAR_ACCENT : Color.TRANSPARENT, 3));
        cell.addView(dot, new LinearLayout.LayoutParams(dp(5), dp(5)));

        cell.setContentDescription(day.format(DAY_ACCESSIBILITY));
        cell.setOnClickListener(view -> {
            displayedMonth = YearMonth.from(day);
            selectedDay = day.equals(selectedDay) ? null : day;
            renderMonth();
            renderCalendarAgenda();
            agendaScroll.scrollTo(0, 0);
            calendarIdle();
        });
        return cell;
    }

    private void renderCalendarAgenda() {
        JSONObject calendar = state.optJSONObject("calendar");
        renderAgenda(calendar == null ? null : calendar.optJSONArray("events"));
    }

    private LinearLayout buildMusic() {
        LinearLayout card = card(MUSIC);

        LinearLayout heading = row();
        heading.addView(text("Sonos", 28, MUSIC_ACCENT, true));
        spacer(heading);
        roomButton = button("Choose a room", INK, Color.TRANSPARENT, this::chooseRoom);
        roomButton.setSingleLine(true);
        roomButton.setEllipsize(TextUtils.TruncateAt.END);
        roomButton.setMaxWidth(dp(700));
        heading.addView(roomButton, new LinearLayout.LayoutParams(-2, dp(56)));
        card.addView(heading);
        gap(card, 18);

        LinearLayout nowPlaying = row();
        FrameLayout cover = new FrameLayout(getContext());
        cover.setBackground(shape(ARTWORK_BACKING, 12));
        cover.setClipToOutline(true);

        ImageView placeholder = new ImageView(getContext());
        placeholder.setImageDrawable(glyph("music", MUSIC_ACCENT, 64));
        placeholder.setScaleType(ImageView.ScaleType.CENTER);
        artworkPlaceholder = placeholder;
        cover.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        artworkView = new ImageView(getContext());
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(artworkView, new FrameLayout.LayoutParams(-1, -1));

        int coverSize = dp(landscape ? 112 : 190);
        nowPlaying.addView(cover, new LinearLayout.LayoutParams(coverSize, coverSize));

        LinearLayout track = column();
        track.setPadding(dp(24), 0, 0, 0);
        musicTitleView = singleLine("Sonos unavailable", landscape ? 28 : 32, INK, true);
        track.addView(musicTitleView);
        gap(track, 9);
        musicArtistView = singleLine("", 22, MUTED, false);
        track.addView(musicArtistView);
        nowPlaying.addView(track, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(nowPlaying);

        card.addView(new View(getContext()), new LinearLayout.LayoutParams(1, 0, 1));
        gap(card, 14);

        LinearLayout controls = row();
        browseButton = button("Browse", INK, Color.TRANSPARENT,
                () -> actions.onMediaAction("browse"));
        controls.addView(browseButton, new LinearLayout.LayoutParams(-2, dp(56)));

        favoritesButton = iconButton("favorite", "Music favorites", INK, 32,
                () -> actions.onMediaAction("favorites"));
        LinearLayout.LayoutParams favoritesParams =
                new LinearLayout.LayoutParams(dp(56), dp(56));
        favoritesParams.setMarginStart(dp(8));
        controls.addView(favoritesButton, favoritesParams);
        spacer(controls);

        previousButton = iconButton("previous", "Previous track", INK, 38,
                () -> actions.onMediaAction("previous"));
        controls.addView(previousButton, new LinearLayout.LayoutParams(dp(58), dp(58)));

        playButton = iconButton("play", "Play or pause", INK, 48,
                () -> actions.onMediaAction("play_pause"));
        controls.addView(playButton, new LinearLayout.LayoutParams(dp(68), dp(64)));

        nextButton = iconButton("next", "Next track", INK, 38,
                () -> actions.onMediaAction("next"));
        controls.addView(nextButton, new LinearLayout.LayoutParams(dp(58), dp(58)));
        spacer(controls);

        groupButton = button("Group", INK, Color.TRANSPARENT, () -> actions.onGroup());
        groupButton.setContentDescription("Group Sonos rooms");
        controls.addView(groupButton, new LinearLayout.LayoutParams(-2, dp(56)));
        card.addView(controls);
        gap(card, 8);

        LinearLayout volumeRow = row();
        muteButton = iconButton("volume", "Mute", MUSIC_ACCENT, 36,
                () -> actions.onMediaAction("mute"));
        volumeRow.addView(muteButton, new LinearLayout.LayoutParams(dp(64), dp(64)));
        volumeRow.addView(buildVolumeBar(), new LinearLayout.LayoutParams(0, dp(64), 1));
        card.addView(volumeRow);
        return card;
    }

    private SeekBar buildVolumeBar() {
        volumeBar = new SeekBar(getContext());
        volumeBar.setMax(100);
        volumeBar.setThumbTintList(ColorStateList.valueOf(MUSIC_ACCENT));
        volumeBar.setProgressTintList(ColorStateList.valueOf(MUSIC_ACCENT));
        volumeBar.setProgressBackgroundTintList(ColorStateList.valueOf(VOLUME_TRACK));

        GradientDrawable thumb = shape(MUSIC_ACCENT, 30);
        thumb.setSize(dp(28), dp(28));
        volumeBar.setThumb(thumb);
        volumeBar.setThumbOffset(dp(14));

        GradientDrawable background = shape(VOLUME_TRACK, 10);
        ClipDrawable fill = new ClipDrawable(
                shape(MUSIC_ACCENT, 10), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable progress = new LayerDrawable(new Drawable[]{background, fill});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) {
            progress.setLayerHeight(layer, dp(12));
            progress.setLayerGravity(
                    layer, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        volumeBar.setProgressDrawable(progress);
        volumeBar.setPadding(dp(14), 0, dp(14), 0);
        volumeBar.setContentDescription("Room volume");
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingVolume = false;
                volumeTouchedAt = SystemClock.uptimeMillis();
                pendingVolume = bar.getProgress();
                pendingVolumeRoom = state.optString("selectedPlayer", "");
                actions.onVolume(bar.getProgress() / 100f);
            }

            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }
        });
        return volumeBar;
    }

    private LinearLayout buildShopping() {
        LinearLayout card = card(SHOPPING);

        LinearLayout heading = row();
        LinearLayout label = column();
        listTitleView = text("Grocery List", 28, SHOPPING_ACCENT, true);
        label.addView(listTitleView);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = iconButton("add", "Add item", SHOPPING_ACCENT, 32, this::addTodo);
        heading.addView(add, new LinearLayout.LayoutParams(dp(56), dp(56)));
        card.addView(heading);
        gap(card, 12);

        todoList = column();
        todoScroll = new TodoScrollView();
        todoScroll.setVerticalScrollBarEnabled(false);
        todoScroll.setPadding(0, 0, dp(15), 0);
        todoScroll.setClipToPadding(true);
        todoScroll.addView(todoList, new ScrollView.LayoutParams(-1, -2));
        todoScroll.setVerticalFadingEdgeEnabled(true);
        todoScroll.setFadingEdgeLength(dp(18));
        card.addView(todoScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return card;
    }

    /** Draws a slim scroll indicator in place of the stock scrollbar. */
    private final class TodoScrollView extends ScrollView {

        private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);

        TodoScrollView() {
            super(DashboardView.this.getContext());
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            int extent = getHeight();
            int range = computeVerticalScrollRange();
            if (extent <= 0 || range <= extent) {
                return;
            }

            float x = getWidth() - dp(4);
            float start = getScrollY() + dp(4);
            float length = extent - dp(8);
            float thumbHeight = Math.max(dp(32), length * extent / range);
            float thumbTop = start + (length - thumbHeight)
                    * Math.min(1f, getScrollY() / (float) (range - extent));

            indicator.setColor(0x3344c6a4);
            canvas.drawRoundRect(
                    x - dp(3), start, x + dp(3), start + length, dp(3), dp(3), indicator);
            indicator.setColor(SHOPPING_ACCENT);
            canvas.drawRoundRect(
                    x - dp(3), thumbTop, x + dp(3), thumbTop + thumbHeight,
                    dp(3), dp(3), indicator);
        }
    }

    public void updateClock() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::updateClock);
            return;
        }
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("h:mm a", Locale.US).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    public void render(JSONObject next) {
        final JSONObject incoming = next == null ? new JSONObject() : next;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> render(incoming));
            return;
        }
        state = incoming;
        applyState();
    }

    public void setArtwork(Bitmap bitmap) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> setArtwork(bitmap));
            return;
        }
        artwork = bitmap;
        showArtwork();
    }

    private void showArtwork() {
        if (artworkView == null) {
            return;
        }
        artworkView.setImageBitmap(artwork);
        artworkView.setVisibility(artwork == null ? GONE : VISIBLE);
        artworkPlaceholder.setVisibility(artwork == null ? VISIBLE : GONE);
    }

    private String value(JSONObject object, String key, String fallback) {
        if (object == null || object.isNull(key)) {
            return fallback;
        }
        String text = object.optString(key, "").trim();
        if (text.isEmpty() || "unknown".equals(text) || "unavailable".equals(text)) {
            return fallback;
        }
        return text;
    }

    private void applyState() {
        updateClock();
        applyWeather();

        JSONObject calendar = state.optJSONObject("calendar");
        JSONArray events = calendar == null ? null : calendar.optJSONArray("events");
        String nextAgendaKey = (events == null ? "pending" : events.toString()) + LocalDate.now();
        if (!nextAgendaKey.equals(agendaKey)) {
            agendaKey = nextAgendaKey;
            renderMonth();
            renderCalendarAgenda();
        }

        JSONArray items = state.optJSONArray("items");
        String nextTodoKey = items == null ? "pending" : items.toString();
        if (!nextTodoKey.equals(todoKey)) {
            todoKey = nextTodoKey;
            renderTodos(items);
        }

        applyPlayer();
    }

    private void applyWeather() {
        JSONObject weather = state.optJSONObject("weather");
        String temperature = value(weather, "temperatureValue", "");
        String scaleLabel = value(weather, "temperatureScale", "");
        if (temperature.endsWith(".0")) {
            temperature = temperature.substring(0, temperature.length() - 2);
        }
        temperatureView.setText(temperature.isEmpty()
                ? ""
                : temperature + "°" + scaleLabel.replace("°", ""));

        String summary = value(weather, "weatherSummary", "");
        weatherSummaryView.setText(summary);
        weatherIconView.setImageDrawable(
                summary.isEmpty() ? null : glyph(weatherIcon(summary), WEATHER_ACCENT, 48));
    }

    private static String weatherIcon(String summary) {
        String condition = summary.toLowerCase(Locale.ROOT);
        if (condition.contains("rain") || condition.contains("shower")) {
            return "rain";
        }
        if (condition.contains("snow")) {
            return "snow";
        }
        if (condition.contains("cloud") || condition.contains("fog")) {
            return "cloud";
        }
        return "sun";
    }

    private void applyPlayer() {
        JSONObject player = selectedPlayer();
        JSONObject attributes = player == null ? null : player.optJSONObject("attributes");
        String playerState = value(player, "state", "");
        boolean ready = player != null && !playerState.isEmpty();

        roomButton.setText(player == null ? "Choose a room" : groupName(player));
        roomButton.setCompoundDrawables(null, null, glyph("expand_more", INK, 24), null);

        String title = value(attributes, "media_title", "");
        musicTitleView.setText(
                !ready ? "Sonos unavailable" : title.isEmpty() ? "Nothing playing" : title);
        musicArtistView.setText(!ready
                ? ""
                : value(attributes, "media_artist", value(attributes, "media_album_name", "")));

        boolean playing = "playing".equals(playerState);
        setGlyph(playButton, playing ? "pause" : "play", MUSIC_ACCENT, 48);
        playButton.setContentDescription(playing ? "Pause" : "Play");

        for (Button button : new Button[]{
                playButton, previousButton, nextButton,
                groupButton, browseButton, favoritesButton}) {
            button.setEnabled(ready);
            button.setAlpha(ready ? 1f : 0.45f);
        }

        boolean canVolume = ready && attributes != null
                && attributes.has("volume_level") && !attributes.isNull("volume_level");
        volumeBar.setEnabled(canVolume);

        boolean canMute = ready && attributes != null
                && attributes.has("is_volume_muted") && !attributes.isNull("is_volume_muted");
        boolean muted = canMute && attributes.optBoolean("is_volume_muted");
        setGlyph(muteButton, muted ? "volume_off" : "volume",
                muted ? 0xffffaaa9 : MUSIC_ACCENT, 36);
        muteButton.setContentDescription(!canMute
                ? "Mute status unavailable"
                : muted ? "Muted, tap to unmute" : "Unmuted, tap to mute");
        muteButton.setEnabled(canMute);
        muteButton.setAlpha(canMute ? 1f : 0.45f);

        applyVolume(canVolume, attributes);
        applyStatusFooter();
    }

    private void applyVolume(boolean canVolume, JSONObject attributes) {
        if (draggingVolume) {
            return;
        }
        int level = canVolume
                ? (int) Math.round(Math.max(0, Math.min(1,
                        attributes.optDouble("volume_level", 0))) * 100)
                : 0;
        String room = state.optString("selectedPlayer", "");
        if (!pendingVolumeRoom.equals(room)
                || level == pendingVolume
                || !canVolume
                || SystemClock.uptimeMillis() - volumeTouchedAt >= VOLUME_SETTLE_MS) {
            pendingVolume = -1;
        }
        if (pendingVolume < 0) {
            volumeBar.setProgress(level);
        }
    }

    private void applyStatusFooter() {
        String status = value(state, "status", "");
        footerView.setText(status);
        footerView.setVisibility(
                status.isEmpty() || status.startsWith("Connected") ? GONE : VISIBLE);
    }

    private JSONObject selectedPlayer() {
        JSONArray players = state.optJSONArray("players");
        if (players == null) {
            return null;
        }
        String id = state.optString("selectedPlayer", "");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player != null && id.equals(player.optString("entity_id"))) {
                return player;
            }
        }
        return null;
    }

    private String roomName(String id) {
        JSONArray players = state.optJSONArray("players");
        if (players != null) {
            for (int i = 0; i < players.length(); i++) {
                JSONObject player = players.optJSONObject(i);
                if (player != null && id.equals(player.optString("entity_id"))) {
                    return value(player.optJSONObject("attributes"), "friendly_name", id);
                }
            }
        }
        return id.startsWith("media_player.")
                ? id.substring("media_player.".length()).replace('_', ' ')
                : id;
    }

    private static JSONArray members(JSONObject player) {
        JSONObject attributes = player.optJSONObject("attributes");
        return attributes == null ? null : attributes.optJSONArray("group_members");
    }

    private static String coordinator(JSONObject player) {
        JSONArray groupMembers = members(player);
        if (groupMembers != null && groupMembers.length() > 0) {
            return groupMembers.optString(0, player.optString("entity_id"));
        }
        return player.optString("entity_id");
    }

    private String groupName(JSONObject player) {
        JSONArray groupMembers = members(player);
        if (groupMembers == null || groupMembers.length() < 2) {
            return roomName(player.optString("entity_id"));
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < groupMembers.length(); i++) {
            String id = groupMembers.optString(i);
            if (!id.isEmpty()) {
                names.add(roomName(id));
            }
        }
        return names.isEmpty()
                ? roomName(player.optString("entity_id"))
                : TextUtils.join(" + ", names);
    }

    private void chooseRoom() {
        JSONArray players = state.optJSONArray("players");
        DashboardPopup popup = new DashboardPopup(
                getContext(), "Sonos rooms", MUSIC, MUSIC_ACCENT);
        LinearLayout rows = popup.column();
        ScrollView list = new ScrollView(getContext());
        list.addView(rows, new ScrollView.LayoutParams(-1, -2));
        list.setVerticalScrollBarEnabled(false);
        popup.body.addView(list, new LinearLayout.LayoutParams(-1, -1));

        Map<String, JSONObject> groups = new LinkedHashMap<>();
        if (players != null) {
            for (int i = 0; i < players.length(); i++) {
                JSONObject player = players.optJSONObject(i);
                if (player == null || player.optString("entity_id").isEmpty()) {
                    continue;
                }
                String id = coordinator(player);
                JSONObject existing = groups.get(id);
                if (existing == null || isMoreComplete(player, existing)) {
                    groups.put(id, player);
                }
            }
        }

        JSONObject selected = selectedPlayer();
        String current = selected == null ? "" : coordinator(selected);
        for (Map.Entry<String, JSONObject> entry : groups.entrySet()) {
            String id = entry.getKey();
            boolean active = id.equals(current);
            String name = groupName(entry.getValue());
            Button button = popup.button(
                    name + (active ? "   ✓" : ""),
                    active ? MUSIC_ACCENT : INK,
                    active ? ROOM_SELECTED_BACKGROUND : ROOM_BACKGROUND,
                    () -> {
                        actions.onPlayerSelected(id);
                        popup.dismiss();
                    });
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            button.setSingleLine(false);
            button.setMaxLines(3);
            button.setPadding(popup.dp(20), popup.dp(12), popup.dp(20), popup.dp(12));
            button.setContentDescription(name + (active ? ", selected" : ""));
            rows.addView(button, new LinearLayout.LayoutParams(-1, -2));
            popup.gap(rows, 8);
        }

        if (groups.isEmpty()) {
            rows.addView(popup.label("Waiting for Sonos rooms", 24, MUTED, false));
        }
        popup.actions.setVisibility(GONE);
        popup.open(false, Math.min(800, 140 + Math.max(1, groups.size()) * 90));
    }

    private static boolean isMoreComplete(JSONObject candidate, JSONObject existing) {
        JSONArray candidateMembers = members(candidate);
        if (candidateMembers == null) {
            return false;
        }
        JSONArray existingMembers = members(existing);
        return existingMembers == null
                || candidateMembers.length() > existingMembers.length();
    }

    private void empty(LinearLayout list, String title, String subtitle, int color) {
        TextView heading = text(title, 21, color, true);
        heading.setPadding(0, dp(12), 0, dp(10));
        list.addView(heading);

        TextView detail = text(subtitle, 16, color, false);
        detail.setLineSpacing(dp(4), 1);
        list.addView(detail);
    }

    private static String dayHeading(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return "Today";
        }
        if (day.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return day.format(WEEKDAY_NAME);
    }

    private void addDayHeader(LocalDate day, boolean first) {
        if (!first) {
            gap(agendaList, 22);
        }
        LinearLayout header = row();
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setBackground(shape(DAY_HEADER, 10));
        TextView weekday = text(dayHeading(day), 26, CALENDAR_ACCENT, true);
        header.addView(weekday, new LinearLayout.LayoutParams(0, -2, 1));
        TextView number = singleLine(day.format(SHORT_DATE), 22, INK, false);
        header.addView(number);
        agendaList.addView(header, new LinearLayout.LayoutParams(-1, -2));
        gap(agendaList, 6);
    }

    private void renderAgenda(JSONArray events) {
        int oldScroll = agendaScroll == null ? 0 : agendaScroll.getScrollY();
        agendaList.removeAllViews();
        if (events == null) {
            agendaList.addView(text("Calendar unavailable", 22, INK, false));
            return;
        }

        LocalDate from = selectedDay != null
                ? selectedDay
                : displayedMonth.equals(YearMonth.now())
                        ? LocalDate.now()
                        : displayedMonth.atDay(1);
        LocalDate previous = null;
        int added = 0;
        boolean showToday = from.equals(LocalDate.now());
        boolean todayHasEvents = false;

        if (showToday) {
            addDayHeader(from, true);
            previous = from;
        }

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                continue;
            }
            LocalDate day = dayOf(event);
            if (day == null || day.isBefore(from)) {
                continue;
            }
            if (selectedDay != null && !day.equals(selectedDay)) {
                continue;
            }
            if (selectedDay == null && day.isAfter(displayedMonth.atEndOfMonth())) {
                break;
            }
            if (showToday && day.equals(from)) {
                todayHasEvents = true;
            }
            if (!day.equals(previous)) {
                if (showToday && from.equals(previous) && !todayHasEvents) {
                    addTodayEmpty();
                }
                addDayHeader(day, previous == null);
                previous = day;
            }
            agendaList.addView(buildAgendaEntry(event));
            added++;
        }

        if (showToday && from.equals(previous) && !todayHasEvents) {
            addTodayEmpty();
        }
        if (added == 0 && !showToday) {
            if (selectedDay == null && !displayedMonth.equals(YearMonth.now())) {
                agendaList.addView(text(
                        displayedMonth.format(MONTH_TITLE), 26, CALENDAR_ACCENT, true));
            } else {
                addDayHeader(from, true);
            }
            gap(agendaList, 12);
            agendaList.addView(text("No events", 25, INK, false));
        }
        if (agendaScroll != null && oldScroll > 0) {
            agendaScroll.scrollTo(0, oldScroll);
        }
    }

    private LinearLayout buildAgendaEntry(JSONObject event) {
        LinearLayout entry = row();
        entry.setGravity(Gravity.TOP);
        entry.setPadding(dp(9), dp(12), dp(4), dp(12));

        View marker = new View(getContext());
        marker.setBackground(shape(
                event.optBoolean("allDay") ? AGENDA_ALL_DAY : CALENDAR_ACCENT, 2));
        LinearLayout.LayoutParams markerParams =
                new LinearLayout.LayoutParams(dp(4), dp(58));
        markerParams.setMarginEnd(dp(14));
        entry.addView(marker, markerParams);

        LinearLayout detail = column();
        String when = event.optBoolean("allDay")
                ? "All day"
                : displayTime(event.optString("start")) + " – "
                        + displayTime(event.optString("end"));
        TextView name = text(value(event, "title", "Untitled event"), 26, INK, false);
        name.setMaxLines(2);
        detail.addView(name);
        gap(detail, 4);
        detail.addView(text(when, 19, AGENDA_SECONDARY, false));
        entry.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        return entry;
    }

    private void addTodayEmpty() {
        TextView empty = text("No more events today", 22, AGENDA_SECONDARY, false);
        empty.setPadding(dp(16), dp(12), dp(8), dp(12));
        agendaList.addView(empty);
    }

    private void renderTodos(JSONArray items) {
        int oldScroll = todoScroll == null ? 0 : todoScroll.getScrollY();
        todoList.removeAllViews();
        listTitleView.setText(state.optString("todoName", "Grocery List"));

        if (items == null) {
            empty(todoList, "List unavailable", "", MUTED);
            return;
        }
        if (items.length() == 0) {
            empty(todoList, "No items", "", MUTED);
            return;
        }

        // Completed items sink below the open ones.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                boolean done = "completed".equals(item.optString("status"));
                if ((pass == 1) != done) {
                    continue;
                }
                todoList.addView(buildTodoRow(item, done),
                        new LinearLayout.LayoutParams(-1, -2));
            }
        }

        if (todoScroll != null && oldScroll > 0) {
            todoScroll.scrollTo(0, oldScroll);
        }
    }

    private CheckBox buildTodoRow(JSONObject item, boolean done) {
        String uid = item.optString("uid", "");
        CheckBox check = new CheckBox(getContext());
        check.setText(value(item, "summary", "Untitled item"));
        check.setTextSize(26 * scale);
        check.setTextColor(done ? MUTED : INK);
        check.setTypeface(regular);
        check.setButtonTintList(ColorStateList.valueOf(SHOPPING_ACCENT));
        check.setPadding(dp(2), dp(6), dp(8), dp(6));
        check.setMinHeight(dp(58));
        check.setChecked(done);
        check.setEnabled(!uid.isEmpty());
        if (done) {
            check.setPaintFlags(check.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        check.setOnCheckedChangeListener(
                (button, checked) -> actions.onTodoToggle(uid, checked));
        return check;
    }

    private void addTodo() {
        DashboardPopup popup = new DashboardPopup(
                getContext(),
                "Add to " + state.optString("todoName", "Grocery List"),
                SHOPPING,
                SHOPPING_ACCENT);

        EditText input = new EditText(getContext());
        input.setHint("What do you need?");
        input.setSingleLine(true);
        input.setTextSize(26);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTypeface(regular);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setPadding(popup.dp(18), popup.dp(16), popup.dp(18), popup.dp(16));
        input.setBackground(popup.shape(INPUT_BACKGROUND, 12));
        popup.body.addView(input, new LinearLayout.LayoutParams(-1, -2));

        Runnable submit = () -> {
            String title = input.getText().toString().trim();
            if (!title.isEmpty()) {
                actions.onTodoAdd(title);
                popup.dismiss();
            }
        };

        popup.actions.addView(new View(getContext()),
                new LinearLayout.LayoutParams(0, 1, 1));
        popup.actions.addView(
                popup.button("Cancel", MUTED, Color.TRANSPARENT, popup::dismiss));

        Button add = popup.button("Add item", SHOPPING, SHOPPING_ACCENT, submit);
        add.setEnabled(false);
        add.setAlpha(0.45f);
        popup.actions.addView(add);

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                boolean valid = !text.toString().trim().isEmpty();
                add.setEnabled(valid);
                add.setAlpha(valid ? 1f : 0.45f);
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });

        input.requestFocus();
        popup.open(true, 260);
    }

    private Date parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String normalized = raw.replaceAll("\\.\\d+(?=Z|[+-]\\d\\d:?\\d\\d$)", "");
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                return format.parse(normalized);
            } catch (Exception invalid) {
                // Try the next accepted shape.
            }
        }
        return null;
    }

    private String displayTime(String raw) {
        Date parsed;
        try {
            parsed = new Date(Long.parseLong(raw));
        } catch (NumberFormatException epoch) {
            parsed = parseDate(raw);
        }
        if (parsed == null) {
            return raw;
        }
        String pattern = android.text.format.DateFormat.is24HourFormat(getContext())
                ? "HH:mm"
                : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(parsed);
    }
}
