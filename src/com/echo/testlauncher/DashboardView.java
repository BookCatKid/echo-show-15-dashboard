package com.echo.testlauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Native, network-independent dashboard. All public updates may be called from any thread. */
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
    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject state = new JSONObject();
    private final int ink = 0xfff8f5ff, muted = 0xffc1bdce;
    private static final int BACKGROUND=0xff171820, CALENDAR=0xff273346, MONTH=0xff30405a,
            MUSIC=0xff392c49, SHOPPING=0xff253d39, CAL_ACCENT=0xffa9caff,
            MUSIC_ACCENT=0xffdfbaf8, SHOP_ACCENT=0xffa5e5ce, WEATHER_ACCENT=0xffffd49f;
    private TextView clock, date, temperature, weatherSummary, monthTitle;
    private final Typeface regular=font("Regular"), medium=font("Medium");
    private ImageView weatherIcon;
    private LinearLayout monthGrid;
    private ScrollView agendaScroll, todoScroll;
    private boolean agendaTouch;
    private LocalDate selectedDay;
    private YearMonth displayedMonth=YearMonth.now();
    private TextView musicTitle, musicArtist, footer, listTitle;
    private LinearLayout agendaList, todoList;
    private Button roomButton, playButton, groupButton, browseButton, favoritesButton, previousButton, nextButton;
    private SeekBar volume;
    private Button muteButton;
    private ImageView art;
    private View artPlaceholder;
    private Bitmap artwork;
    private boolean dragging, settingVolume;
    private long volumeTouched;
    private String pendingVolumeRoom="";
    private int pendingVolume=-1;
    private String agendaKey = "", todoKey = "";
    private boolean landscape;
    private float unit = 1f;
    private int lastWidth, lastHeight;
    private final Runnable resetCalendar=()->{
        if(agendaTouch||agendaScroll==null)return;
        boolean changed=selectedDay!=null||!displayedMonth.equals(YearMonth.now());
        selectedDay=null;displayedMonth=YearMonth.now();
        if(changed){renderMonth();renderCalendarAgenda();agendaScroll.scrollTo(0,0);}
        else agendaScroll.smoothScrollTo(0,0);
    };
    private void calendarIdle(){main.removeCallbacks(resetCalendar);main.postDelayed(resetCalendar,30000);}
    @Override protected void onDetachedFromWindow(){main.removeCallbacks(resetCalendar);super.onDetachedFromWindow();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();calendarIdle();}


    public DashboardView(Context context, Actions actions) {
        super(context);
        if (actions == null) throw new IllegalArgumentException("Actions is required");
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(BACKGROUND);
        setClipChildren(false);
        build();
        applyState();
    }
    private static Typeface font(String weight) {
        try { return Typeface.createFromFile("/system/fonts/Amazon-Ember-"+weight+".ttf"); }
        catch(Exception ignored) { return Typeface.create(weight.equals("Medium")?"sans-serif-medium":"sans-serif",Typeface.NORMAL); }
    }
    private int d(float value) { return Math.round(value * unit * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, float radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(d(radius)); return g; }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size * unit); t.setTextColor(color);
        t.setTypeface(bold?medium:regular);
        t.setIncludeFontPadding(false); return t;
    }
    private TextView single(String value, float size, int color, boolean bold) {
        TextView t = text(value,size,color,bold); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); return t;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(getContext()),new LinearLayout.LayoutParams(1,d(height))); }
    private void spacer(LinearLayout parent) { parent.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1)); }
    private Button button(String label, int foreground, int background, Runnable click) {
        Button b = new Button(getContext()); b.setText(label); b.setTextSize(22 * unit); b.setAllCaps(false);
        b.setTextColor(foreground); b.setTypeface(medium);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(background,12),shape(Color.WHITE,12))); b.setPadding(d(16),0,d(16),0);
        b.setMinHeight(d(56)); b.setMinimumHeight(d(56)); b.setMinWidth(d(56)); b.setMinimumWidth(d(56));
        b.setStateListAnimator(null); b.setOnClickListener(v -> click.run());
        b.setContentDescription(label); return b;
    }
    private LinearLayout card(int color) {
        LinearLayout c = column(); c.setPadding(d(28),d(26),d(28),d(24));
        c.setBackground(shape(color,24)); c.setClipToOutline(true); return c;
    }
    private void weighted(LinearLayout parent,View child,float weight,int margin,boolean horizontal) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(horizontal?0:ViewGroup.LayoutParams.MATCH_PARENT,
                horizontal?ViewGroup.LayoutParams.MATCH_PARENT:0,weight);
        if(horizontal) p.setMarginEnd(d(margin)); else p.bottomMargin=d(margin);
        parent.addView(child,p);
    }
    private ScrollView scroll(LinearLayout contents) {
        ScrollView s = new ScrollView(getContext()); s.setFillViewport(false); s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS); s.addView(contents,new ScrollView.LayoutParams(-1,-2)); return s;
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if(w<=0||h<=0||w==lastWidth&&h==lastHeight) return;
        lastWidth=w; lastHeight=h;
        float density=getResources().getDisplayMetrics().density;
        boolean wide=w>h;
        float scale=Math.max(.72f,Math.min(1.12f,(wide?h/density/1080f:w/density/1080f)));
        if(wide!=landscape || Math.abs(scale-unit)>.03f) {
            landscape=wide; unit=scale;
            main.post(() -> { build(); applyState(); });
        }
    }
    private Drawable glyph(String name,int color,int size) {
        int id=getResources().getIdentifier("ic_"+name,"drawable",getContext().getPackageName());
        if(id==0) return null;
        Drawable icon=getContext().getDrawable(id).mutate();icon.setTint(color);icon.setBounds(0,0,d(size),d(size));return icon;
    }
    private void setGlyph(Button b,String name,int color,int size) {
        b.setText(""); b.setPadding(0,0,0,0);b.setCompoundDrawables(null,null,null,null);
        b.setGravity(Gravity.CENTER);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),shape(Color.TRANSPARENT,10),shape(Color.WHITE,10)));
        // Center the established vector as the background layer, preserving native Button semantics.
        Drawable icon=glyph(name,color,size); if(icon!=null) {
            android.graphics.drawable.LayerDrawable layer=new android.graphics.drawable.LayerDrawable(new Drawable[]{icon});
            layer.setLayerWidth(0,d(size));layer.setLayerHeight(0,d(size));layer.setLayerGravity(0,Gravity.CENTER);
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),layer,shape(Color.WHITE,10)));
        }
    }
    private Button iconButton(String name,String description,int color,int size,Runnable click) {
        Button b=button("",color,Color.TRANSPARENT,click);setGlyph(b,name,color,size);b.setContentDescription(description);return b;
    }
    private void build() {
        removeAllViews(); agendaKey=""; todoKey="";
        setPadding(d(30),d(28),d(30),d(24));
        LinearLayout header=row();
        LinearLayout heading=column();
        clock=single("",landscape?62:66,Color.WHITE,false);
        date=single("",24,muted,false); heading.addView(clock);gap(heading,3);heading.addView(date);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout weather=row(); weatherIcon=new ImageView(getContext());weather.addView(weatherIcon,new LinearLayout.LayoutParams(d(52),d(52)));
        LinearLayout conditions=column();conditions.setPadding(d(12),0,d(8),0);temperature=single("",36,ink,false);weatherSummary=text("",19,muted,false);weatherSummary.setMaxLines(3);conditions.addView(temperature);conditions.addView(weatherSummary);weather.addView(conditions,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(weather,new LinearLayout.LayoutParams(d(235),-2));
        Button refresh=iconButton("refresh","Refresh dashboard",ink,30,()->actions.onRefresh()); header.addView(refresh,new LinearLayout.LayoutParams(d(56),d(56)));
        Button home=iconButton("home","Amazon home",ink,30,()->actions.onStockHome());header.addView(home,new LinearLayout.LayoutParams(d(56),d(56)));
        refresh.setOnLongClickListener(v->{actions.onSettings();return true;});
        ImageView fireTv=new ImageView(getContext());
        fireTv.setContentDescription("Fire TV");fireTv.setClickable(true);fireTv.setFocusable(true);
        fireTv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            android.content.res.Resources stock=getContext().getPackageManager().getResourcesForApplication("com.amazon.paladin");
            int logo=stock.getIdentifier("ic_ftv_logo","drawable","com.amazon.paladin");
            int backing=stock.getIdentifier("ic_ftv_backing","drawable","com.amazon.paladin");
            if(logo==0||backing==0)throw new IllegalStateException("Fire TV resources unavailable");
            fireTv.setImageDrawable(stock.getDrawable(logo,null));
            fireTv.setBackground(stock.getDrawable(backing,null));
        } catch(Exception unavailable) {
            fireTv.setImageDrawable(glyph("fire_tv",ink,30));
            fireTv.setPadding(d(12),d(12),d(12),d(12));
            fireTv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),shape(Color.TRANSPARENT,10),shape(Color.WHITE,10)));
        }
        fireTv.setOnClickListener(v->actions.onFireTv());
        header.addView(fireTv,new LinearLayout.LayoutParams(d(72),d(66)));
        LinearLayout body=column();addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout calendar=buildCalendar(),music=buildMusic(),shopping=buildShopping();
        if(landscape){ LinearLayout top=row();weighted(top,shopping,1f,18,true);weighted(top,buildSmartHome(),.72f,18,true);weighted(top,music,1.35f,0,true);weighted(body,top,1f,18,false);weighted(body,calendar,1.02f,0,false); }
        else { LinearLayout top=row();weighted(top,shopping,1f,18,true);
            weighted(top,buildSmartHome(),1f,0,true);
            weighted(body,top,.82f,18,false); weighted(body,music,.94f,18,false); weighted(body,calendar,1.15f,0,false); }
        footer=single("",18,0xffe5bd89,false);footer.setVisibility(GONE);addView(footer,new LinearLayout.LayoutParams(-1,-2));
        gap(this,22);addView(header,new LinearLayout.LayoutParams(-1,-2));
        updateClock();showArtwork();
    }
    private LinearLayout buildSmartHome() {
        LinearLayout entry=card(0xff443a30);entry.setGravity(Gravity.CENTER);
        int accent=0xffffd49f;
        ImageView icon=new ImageView(getContext());icon.setImageDrawable(glyph("home",accent,96));
        entry.addView(icon,new LinearLayout.LayoutParams(d(96),d(96)));gap(entry,18);
        TextView label=text("Smart Home",34,accent,true);label.setGravity(Gravity.CENTER);entry.addView(label);
        entry.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),shape(0xff443a30,24),shape(Color.WHITE,24)));
        entry.setContentDescription("Smart Home");entry.setFocusable(true);entry.setOnClickListener(v->actions.onSmartHome());return entry;
    }
    private LinearLayout buildCalendar() {
        LinearLayout outer=row();outer.setGravity(Gravity.TOP);outer.setBackground(shape(CALENDAR,24));outer.setClipToOutline(true);
        LinearLayout left=column();left.setPadding(d(24),d(22),d(24),d(22));
        agendaList=column();agendaScroll=new ScrollView(getContext()){
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
                if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){agendaTouch=true;calendarIdle();}
                if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP||e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL){agendaTouch=false;calendarIdle();}
                return super.dispatchTouchEvent(e);
            }
            @Override protected void onScrollChanged(int x,int y,int oldX,int oldY){super.onScrollChanged(x,y,oldX,oldY);if(y!=oldY&&!agendaTouch)calendarIdle();}
        };
        agendaScroll.setVerticalScrollBarEnabled(false);
        agendaScroll.addView(agendaList,new ScrollView.LayoutParams(-1,-2));left.addView(agendaScroll,new LinearLayout.LayoutParams(-1,0,1));
        outer.addView(left,new LinearLayout.LayoutParams(0,-1,1.53f));
        LinearLayout right=column();right.setBackgroundColor(MONTH);right.setPadding(d(16),d(22),d(16),d(22));
        LinearLayout nav=row(); Button prev=iconButton("chevron_left","Previous month",ink,28,()->changeMonth(-1));nav.addView(prev,new LinearLayout.LayoutParams(d(48),d(48)));
        monthTitle=single("",26,CAL_ACCENT,true);monthTitle.setGravity(Gravity.CENTER);nav.addView(monthTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button next=iconButton("chevron_right","Next month",ink,28,()->changeMonth(1));nav.addView(next,new LinearLayout.LayoutParams(d(48),d(48)));right.addView(nav);gap(right,12);
        monthGrid=column();right.addView(monthGrid,new LinearLayout.LayoutParams(-1,-2));outer.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        return outer;
    }
    private void changeMonth(int offset){displayedMonth=displayedMonth.plusMonths(offset);selectedDay=null;renderMonth();renderCalendarAgenda();agendaScroll.scrollTo(0,0);calendarIdle();}
    private LocalDate dayOf(JSONObject e){try{return Instant.parse(e.optString("start")).atZone(ZoneId.systemDefault()).toLocalDate();}catch(Exception ignored){return null;}}
    private void renderMonth(){
        if(monthGrid==null)return;monthGrid.removeAllViews();monthTitle.setText(displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        Set<LocalDate> dates=new HashSet<>();JSONObject calendar=state.optJSONObject("calendar");JSONArray events=calendar==null?null:calendar.optJSONArray("events");if(events!=null)for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null){LocalDate day=dayOf(e);if(day!=null)dates.add(day);}}
        LinearLayout week=row();for(String label:new String[]{"Sun","Mon","Tue","Wed","Thu","Fri","Sat"}){TextView t=single(label,15,0xffc9d5e7,false);t.setGravity(Gravity.CENTER);week.addView(t,new LinearLayout.LayoutParams(0,d(32),1));}monthGrid.addView(week);
        LocalDate first=displayedMonth.atDay(1), start=first.minusDays(first.getDayOfWeek().getValue()%7),today=LocalDate.now();
        for(int r=0;r<6;r++){LinearLayout line=row();for(int c=0;c<7;c++){final LocalDate day=start.plusDays(r*7+c);boolean in=YearMonth.from(day).equals(displayedMonth),sel=day.equals(selectedDay),now=day.equals(today);
            LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.setMinimumHeight(d(landscape?37:77));TextView num=single(String.valueOf(day.getDayOfMonth()),24,in?ink:0xff8b9ab3,false); num.setGravity(Gravity.CENTER);
            if(now||sel){num.setTextColor(now?MONTH:ink);num.setBackground(shape(now?CAL_ACCENT:0xff546b91,24));}cell.addView(num,new LinearLayout.LayoutParams(d(landscape?30:46),d(landscape?30:46)));
            View dot=new View(getContext());dot.setBackground(shape(dates.contains(day)?CAL_ACCENT:Color.TRANSPARENT,3));cell.addView(dot,new LinearLayout.LayoutParams(d(5),d(5)));
            cell.setContentDescription(day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")));cell.setOnClickListener(v->{displayedMonth=YearMonth.from(day);selectedDay=day.equals(selectedDay)?null:day;renderMonth();renderCalendarAgenda();agendaScroll.scrollTo(0,0);calendarIdle();});line.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}monthGrid.addView(line);}
    }
    private void renderCalendarAgenda(){JSONObject calendar=state.optJSONObject("calendar");renderAgenda(calendar,calendar==null?null:calendar.optJSONArray("events"));}
    private LinearLayout buildMusic() {
        LinearLayout c=card(MUSIC);
        LinearLayout heading=row(); heading.addView(text("Sonos",28,MUSIC_ACCENT,true)); spacer(heading);
        roomButton=button("Choose a room",ink,Color.TRANSPARENT,() -> chooseRoom());roomButton.setSingleLine(true);roomButton.setEllipsize(TextUtils.TruncateAt.END);roomButton.setMaxWidth(d(700));
        heading.addView(roomButton,new LinearLayout.LayoutParams(-2,d(56))); c.addView(heading); gap(c,18);
        LinearLayout now=row();
        FrameLayout cover=new FrameLayout(getContext()); cover.setBackground(shape(0xff4a3a5b,12)); cover.setClipToOutline(true);
        ImageView placeholder=new ImageView(getContext());placeholder.setImageDrawable(glyph("music",MUSIC_ACCENT,64));placeholder.setScaleType(ImageView.ScaleType.CENTER);artPlaceholder=placeholder;
        cover.addView(artPlaceholder,new FrameLayout.LayoutParams(-1,-1)); art=new ImageView(getContext()); art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(art,new FrameLayout.LayoutParams(-1,-1));
        int coverSize=landscape?112:190; now.addView(cover,new LinearLayout.LayoutParams(d(coverSize),d(coverSize)));
        LinearLayout track=column(); track.setPadding(d(24),0,0,0);

        musicTitle=single("Sonos unavailable",landscape?28:32,ink,true); track.addView(musicTitle); gap(track,9);
        musicArtist=single("",22,muted,false); track.addView(musicArtist);
        now.addView(track,new LinearLayout.LayoutParams(0,-2,1)); c.addView(now);
        c.addView(new View(getContext()),new LinearLayout.LayoutParams(1,0,1)); gap(c,14);
        LinearLayout controls=row();
        browseButton=button("Browse",ink,Color.TRANSPARENT,() -> actions.onMediaAction("browse")); controls.addView(browseButton,new LinearLayout.LayoutParams(-2,d(56)));
        favoritesButton=iconButton("favorite","Music favorites",ink,32,() -> actions.onMediaAction("favorites")); favoritesButton.setContentDescription("Music favorites");
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(d(56),d(56)); fp.setMarginStart(d(8)); controls.addView(favoritesButton,fp); spacer(controls);
        previousButton=iconButton("previous","Previous track",ink,38,() -> actions.onMediaAction("previous")); previousButton.setContentDescription("Previous track");
        controls.addView(previousButton,new LinearLayout.LayoutParams(d(58),d(58)));
        playButton=iconButton("play","Play or pause",ink,48,() -> actions.onMediaAction("play_pause")); playButton.setContentDescription("Play or pause");
        controls.addView(playButton,new LinearLayout.LayoutParams(d(68),d(64)));
        nextButton=iconButton("next","Next track",ink,38,() -> actions.onMediaAction("next")); nextButton.setContentDescription("Next track");
        controls.addView(nextButton,new LinearLayout.LayoutParams(d(58),d(58))); spacer(controls);
        groupButton=button("Group",ink,Color.TRANSPARENT,() -> actions.onGroup()); groupButton.setContentDescription("Group Sonos rooms");
        controls.addView(groupButton,new LinearLayout.LayoutParams(-2,d(56))); c.addView(controls); gap(c,8);
        LinearLayout volumeRow=row();
        muteButton=iconButton("volume","Mute",MUSIC_ACCENT,36,()->actions.onMediaAction("mute"));
        volumeRow.addView(muteButton,new LinearLayout.LayoutParams(d(64),d(64)));
        volume=new SeekBar(getContext()); volume.setMax(100); volume.setThumbTintList(android.content.res.ColorStateList.valueOf(MUSIC_ACCENT));
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(MUSIC_ACCENT)); volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff62526e));
        GradientDrawable thumb=shape(MUSIC_ACCENT,30);thumb.setSize(d(28),d(28));volume.setThumb(thumb);volume.setThumbOffset(d(14));
        GradientDrawable trackBackground=shape(0xff62526e,10);
        android.graphics.drawable.ClipDrawable trackFill=new android.graphics.drawable.ClipDrawable(shape(MUSIC_ACCENT,10),Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable trackDrawable=new android.graphics.drawable.LayerDrawable(new Drawable[]{trackBackground,trackFill});
        trackDrawable.setId(0,android.R.id.background);trackDrawable.setId(1,android.R.id.progress);
        for(int i=0;i<2;i++){trackDrawable.setLayerHeight(i,d(12));trackDrawable.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL);}
        volume.setProgressDrawable(trackDrawable);
        volume.setPadding(d(14),0,d(14),0);
        volume.setContentDescription("Room volume"); volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging=true; }
            public void onStopTrackingTouch(SeekBar bar) { dragging=false; volumeTouched=android.os.SystemClock.uptimeMillis(); pendingVolume=bar.getProgress();pendingVolumeRoom=state.optString("selectedPlayer","");actions.onVolume(bar.getProgress()/100f); }
            public void onProgressChanged(SeekBar bar,int value,boolean user) {  }
        }); volumeRow.addView(volume,new LinearLayout.LayoutParams(0,d(64),1)); c.addView(volumeRow); return c;
    }
    private LinearLayout buildShopping() {
        LinearLayout c=card(SHOPPING); LinearLayout heading=row();
        LinearLayout label=column(); listTitle=text("Grocery List",28,SHOP_ACCENT,true);label.addView(listTitle);

        heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button add=iconButton("add","Add item",SHOP_ACCENT,32,() -> addTodo()); heading.addView(add,new LinearLayout.LayoutParams(d(56),d(56)));
        c.addView(heading); gap(c,12); todoList=column(); 
        todoScroll=new ScrollView(getContext()){
            private final Paint indicator=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(Canvas canvas){
                super.dispatchDraw(canvas);
                int extent=getHeight(),range=computeVerticalScrollRange();
                if(extent<=0||range<=extent)return;
                float x=getWidth()-d(4),start=getScrollY()+d(4),length=extent-d(8);
                float h=Math.max(d(32),length*extent/range);
                float at=start+(length-h)*Math.min(1f,getScrollY()/(float)(range-extent));
                indicator.setColor(0x3344c6a4);canvas.drawRoundRect(x-d(3),start,x+d(3),start+length,d(3),d(3),indicator);
                indicator.setColor(SHOP_ACCENT);canvas.drawRoundRect(x-d(3),at,x+d(3),at+h,d(3),d(3),indicator);
            }
        };
        todoScroll.setVerticalScrollBarEnabled(false);todoScroll.setPadding(0,0,d(15),0);
        todoScroll.setClipToPadding(true);todoScroll.addView(todoList,new ScrollView.LayoutParams(-1,-2));
        todoScroll.setVerticalFadingEdgeEnabled(true);todoScroll.setFadingEdgeLength(d(18));
        c.addView(todoScroll,new LinearLayout.LayoutParams(-1,0,1)); return c;
    }
    public void updateClock() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> updateClock()); return; }
        Date now=new Date();
        clock.setText(new SimpleDateFormat("h:mm a",Locale.US).format(now));
        date.setText(new SimpleDateFormat("EEEE, MMMM d",Locale.getDefault()).format(now));

    }
    public void render(JSONObject next) {
        final JSONObject incoming=next==null?new JSONObject():next;
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> render(incoming)); return; }
        state=incoming; applyState();
    }
    public void setArtwork(Bitmap bitmap) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> setArtwork(bitmap)); return; }
        artwork=bitmap; showArtwork();
    }
    private void showArtwork() {
        if(art==null)return; art.setImageBitmap(artwork); art.setVisibility(artwork==null?GONE:VISIBLE);
        artPlaceholder.setVisibility(artwork==null?VISIBLE:GONE);
    }
    private String value(JSONObject o,String key,String fallback) {
        if(o==null||o.isNull(key))return fallback; String s=o.optString(key,"").trim();
        return s.isEmpty()||"unknown".equals(s)||"unavailable".equals(s)?fallback:s;
    }
    private void applyState() {
        updateClock(); JSONObject weather=state.optJSONObject("weather");
        String temp=value(weather,"temperatureValue","");String scale=value(weather,"temperatureScale","");
        if(temp.endsWith(".0"))temp=temp.substring(0,temp.length()-2);
        temperature.setText(temp.isEmpty()?"":temp+"°"+scale.replace("°",""));
        String summary=value(weather,"weatherSummary","");weatherSummary.setText(summary);
        String condition=summary.toLowerCase(Locale.ROOT);String icon=condition.contains("rain")||condition.contains("shower")?"rain":condition.contains("snow")?"snow":condition.contains("cloud")||condition.contains("fog")?"cloud":"sun";
        weatherIcon.setImageDrawable(summary.isEmpty()?null:glyph(icon,WEATHER_ACCENT,48));
        JSONObject calendar=state.optJSONObject("calendar");JSONArray events=calendar==null?null:calendar.optJSONArray("events");
        String ak=(events==null?"pending":events.toString())+LocalDate.now();
        if(!ak.equals(agendaKey)){agendaKey=ak;renderMonth();renderCalendarAgenda();}
        JSONArray items=state.optJSONArray("items"); String tk=items==null?"pending":items.toString();
        if(!tk.equals(todoKey)) {todoKey=tk; renderTodos(items);}
        JSONObject player=selectedPlayer(); JSONObject attrs=player==null?null:player.optJSONObject("attributes");
        String playerState=value(player,"state",""); boolean ready=player!=null&&!playerState.isEmpty();
        roomButton.setText(player==null?"Choose a room":groupName(player));roomButton.setCompoundDrawables(null,null,glyph("expand_more",ink,24),null);
        String title=value(attrs,"media_title","");
        musicTitle.setText(!ready?"Sonos unavailable":title.isEmpty()?"Nothing playing":title);
        musicArtist.setText(!ready?"":value(attrs,"media_artist",value(attrs,"media_album_name","")));

        setGlyph(playButton,"playing".equals(playerState)?"pause":"play",MUSIC_ACCENT,48); playButton.setContentDescription("playing".equals(playerState)?"Pause":"Play");
        for(Button b:new Button[]{playButton,previousButton,nextButton,groupButton,browseButton,favoritesButton}) { b.setEnabled(ready); b.setAlpha(ready?1f:.45f); }
        boolean canVolume=ready&&attrs!=null&&attrs.has("volume_level")&&!attrs.isNull("volume_level"); volume.setEnabled(canVolume);
        boolean canMute=ready&&attrs!=null&&attrs.has("is_volume_muted")&&!attrs.isNull("is_volume_muted");
        boolean isMuted=canMute&&attrs.optBoolean("is_volume_muted");
        setGlyph(muteButton,isMuted?"volume_off":"volume",isMuted?0xffffaaa9:MUSIC_ACCENT,36);
        muteButton.setContentDescription(!canMute?"Mute status unavailable":isMuted?"Muted — tap to unmute":"Unmuted — tap to mute");
        muteButton.setEnabled(canMute);muteButton.setAlpha(canMute?1f:.45f);
        if(!dragging) {
            int v=canVolume?(int)Math.round(Math.max(0,Math.min(1,attrs.optDouble("volume_level",0)))*100):0;
            String room=state.optString("selectedPlayer","");
            if(!pendingVolumeRoom.equals(room)||v==pendingVolume||!canVolume||android.os.SystemClock.uptimeMillis()-volumeTouched>=5000)pendingVolume=-1;
            if(pendingVolume<0)volume.setProgress(v);
        }
        String connection=value(state,"status","");footer.setText(connection);footer.setVisibility(connection.isEmpty()||connection.startsWith("Connected")?GONE:VISIBLE);
    }
    private JSONObject selectedPlayer() {
        JSONArray players=state.optJSONArray("players"); if(players==null)return null;
        String id=state.optString("selectedPlayer","");
        for(int i=0;i<players.length();i++) {JSONObject p=players.optJSONObject(i); if(p!=null&&id.equals(p.optString("entity_id")))return p;}
        return null;
    }
    private String roomName(String id){
        JSONArray players=state.optJSONArray("players");
        if(players!=null)for(int i=0;i<players.length();i++){
            JSONObject p=players.optJSONObject(i);
            if(p!=null&&id.equals(p.optString("entity_id")))return value(p.optJSONObject("attributes"),"friendly_name",id);
        }
        return id.startsWith("media_player.")?id.substring(13).replace('_',' '):id;
    }
    private JSONArray members(JSONObject p){
        JSONObject attributes=p.optJSONObject("attributes");
        return attributes==null?null:attributes.optJSONArray("group_members");
    }
    private String coordinator(JSONObject p){
        JSONArray m=members(p);
        return m!=null&&m.length()>0?m.optString(0,p.optString("entity_id")):p.optString("entity_id");
    }
    private String groupName(JSONObject p){
        JSONArray m=members(p);
        if(m==null||m.length()<2)return roomName(p.optString("entity_id"));
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(int i=0;i<m.length();i++){String id=m.optString(i);if(!id.isEmpty())names.add(roomName(id));}
        return names.isEmpty()?roomName(p.optString("entity_id")):TextUtils.join(" + ",names);
    }
    private void chooseRoom() {
        JSONArray players=state.optJSONArray("players");
        DashboardPopup popup=new DashboardPopup(getContext(),"Sonos rooms",MUSIC,MUSIC_ACCENT);
        LinearLayout rows=popup.column();
        ScrollView list=new ScrollView(getContext());list.addView(rows,new ScrollView.LayoutParams(-1,-2));
        list.setVerticalScrollBarEnabled(false);popup.body.addView(list,new LinearLayout.LayoutParams(-1,-1));
        LinkedHashMap<String,JSONObject> groups=new LinkedHashMap<>();
        if(players!=null)for(int i=0;i<players.length();i++){
            JSONObject p=players.optJSONObject(i);if(p==null||p.optString("entity_id").isEmpty())continue;
            String id=coordinator(p);JSONObject existing=groups.get(id);
            // Prefer a complete group record and then the actual coordinator.
            if(existing==null||(members(p)!=null&&(members(existing)==null||members(p).length()>members(existing).length())))groups.put(id,p);
        }
        JSONObject selected=selectedPlayer();String current=selected==null?"":coordinator(selected);
        for(Map.Entry<String,JSONObject> entry:groups.entrySet()){
            String id=entry.getKey();boolean active=id.equals(current);
            Button b=popup.button(groupName(entry.getValue())+(active?"   ✓":""),active?MUSIC_ACCENT:ink,active?0xff554267:0xff443651,()->{actions.onPlayerSelected(id);popup.dismiss();});
            b.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);b.setSingleLine(false);b.setMaxLines(3);b.setPadding(popup.dp(20),popup.dp(12),popup.dp(20),popup.dp(12));
            b.setContentDescription(groupName(entry.getValue())+(active?", selected":""));
            rows.addView(b,new LinearLayout.LayoutParams(-1,-2));popup.gap(rows,8);
        }
        if(groups.isEmpty())rows.addView(popup.label("Waiting for Sonos rooms",24,muted,false));
        popup.actions.setVisibility(GONE);popup.open(false,Math.min(800,140+Math.max(1,groups.size())*90));
    }
    private void empty(LinearLayout list,String title,String subtitle,int color) {
        TextView t=text(title,21,color,true); t.setPadding(0,d(12),0,d(10)); list.addView(t);
        TextView s=text(subtitle,16,color,false); s.setLineSpacing(d(4),1); list.addView(s);
    }
    private String dayHeading(LocalDate day){LocalDate today=LocalDate.now();return day.equals(today)?"Today":day.equals(today.plusDays(1))?"Tomorrow":day.format(DateTimeFormatter.ofPattern("EEEE"));}
    private void addDayHeader(LocalDate day, boolean first){
        if(!first) gap(agendaList,22);
        LinearLayout header=row();header.setPadding(d(16),d(10),d(16),d(10));header.setBackground(shape(0xff3b5071,10));
        TextView weekday=text(dayHeading(day),26,CAL_ACCENT,true);header.addView(weekday,new LinearLayout.LayoutParams(0,-2,1));
        TextView number=single(day.format(DateTimeFormatter.ofPattern("MMM d")),22,ink,false); header.addView(number);
        agendaList.addView(header,new LinearLayout.LayoutParams(-1,-2));gap(agendaList,6);
    }
    private void renderAgenda(JSONObject calendar,JSONArray events){
        int oldScroll=agendaScroll==null?0:agendaScroll.getScrollY();agendaList.removeAllViews();if(events==null){agendaList.addView(text("Calendar unavailable",22,ink,false));return;}
        LocalDate from=selectedDay!=null?selectedDay:(displayedMonth.equals(YearMonth.now())?LocalDate.now():displayedMonth.atDay(1));LocalDate previous=null;int added=0;
        boolean showToday=from.equals(LocalDate.now()); boolean todayHasEvents=false;
        if(showToday){addDayHeader(from,true);previous=from;}
        for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event==null)continue;LocalDate day=dayOf(event);if(day==null||day.isBefore(from)||(selectedDay!=null&&!day.equals(selectedDay)))continue;
            if(selectedDay==null&&day.isAfter(displayedMonth.atEndOfMonth()))break;
            if(showToday && day.equals(from))todayHasEvents=true;
            if(!day.equals(previous)){
                if(showToday && from.equals(previous) && !todayHasEvents) addTodayEmpty();
                addDayHeader(day,previous==null);previous=day;
            }
            LinearLayout entry=row();entry.setGravity(Gravity.TOP);entry.setPadding(d(9),d(12),d(4),d(12));
            View marker=new View(getContext());marker.setBackground(shape(event.optBoolean("allDay")?0xfff0ba9e:CAL_ACCENT,2));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(d(4),d(58));mp.setMarginEnd(d(14));entry.addView(marker,mp);
            LinearLayout detail=column();String start=event.optString("start"),end=event.optString("end");String when=event.optBoolean("allDay")?"All day":displayTime(start)+" – "+displayTime(end);
            TextView name=text(value(event,"title","Untitled event"),26,ink,false);name.setMaxLines(2);detail.addView(name);gap(detail,4);detail.addView(text(when,19,0xffc2cee1,false));entry.addView(detail,new LinearLayout.LayoutParams(0,-2,1));agendaList.addView(entry);
            added++;
        }
        if(showToday && from.equals(previous) && !todayHasEvents) addTodayEmpty();
        if(added==0&&!showToday){if(selectedDay==null&&!displayedMonth.equals(YearMonth.now())) agendaList.addView(text(displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),26,CAL_ACCENT,true));else addDayHeader(from,true);gap(agendaList,12);agendaList.addView(text("No events",25,ink,false));}
        if(agendaScroll!=null&&oldScroll>0)agendaScroll.scrollTo(0,oldScroll);
    }
    private void addTodayEmpty(){
        TextView empty=text("No more events today",22,0xffc2cee1,false);
        empty.setPadding(d(16),d(12),d(8),d(12));agendaList.addView(empty);
    }
    private void renderTodos(JSONArray items) {
        int oldScroll=todoScroll==null?0:todoScroll.getScrollY();todoList.removeAllViews(); listTitle.setText(state.optString("todoName","Grocery List")); if(items==null) { empty(todoList,"List unavailable", "",muted);return;}
        if(items.length()==0) {empty(todoList,"No items", "",muted);return;}
        for(int pass=0;pass<2;pass++)for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);if(item==null)continue;
            final boolean done="completed".equals(item.optString("status")); if((pass==1)!=done)continue;
            final String uid=item.optString("uid",""); CheckBox check=new CheckBox(getContext());
            check.setText(value(item,"summary","Untitled item")); check.setTextSize(26*unit);check.setTextColor(done?muted:ink);
            check.setTypeface(regular); check.setButtonTintList(android.content.res.ColorStateList.valueOf(SHOP_ACCENT));
            check.setPadding(d(2),d(6),d(8),d(6));check.setMinHeight(d(58));check.setChecked(done);check.setEnabled(!uid.isEmpty());
            if(done)check.setPaintFlags(check.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
            check.setOnCheckedChangeListener((button,checked)->actions.onTodoToggle(uid,checked));
            todoList.addView(check,new LinearLayout.LayoutParams(-1,-2));
        }
        if(todoScroll!=null&&oldScroll>0)todoScroll.scrollTo(0,oldScroll);
    }
    private void addTodo() {
        DashboardPopup popup=new DashboardPopup(getContext(),"Add to "+state.optString("todoName","Grocery List"),SHOPPING,SHOP_ACCENT);
        EditText input=new EditText(getContext());input.setHint("What do you need?");input.setSingleLine(true);
        input.setTextSize(26);input.setTextColor(ink);input.setHintTextColor(muted);input.setTypeface(regular);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setPadding(popup.dp(18),popup.dp(16),popup.dp(18),popup.dp(16));
        input.setBackground(popup.shape(0xff344e47,12));popup.body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        Runnable submit=()->{String title=input.getText().toString().trim();if(!title.isEmpty()){actions.onTodoAdd(title);popup.dismiss();}};
        popup.actions.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
        popup.actions.addView(popup.button("Cancel",muted,Color.TRANSPARENT,()->popup.dismiss()));
        Button add=popup.button("Add item",SHOPPING,SHOP_ACCENT,submit);add.setEnabled(false);add.setAlpha(.45f);popup.actions.addView(add);
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){} public void onTextChanged(CharSequence s,int st,int before,int count){boolean valid=!s.toString().trim().isEmpty();add.setEnabled(valid);add.setAlpha(valid?1f:.45f);}public void afterTextChanged(android.text.Editable e){}});
        input.setOnEditorActionListener((v,id,event)->{if(id==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){submit.run();return true;}return false;});
        input.requestFocus();popup.open(true,260);
    }
    private Date parseDate(String raw) {
        if(raw==null||raw.isEmpty())return null;
        String normalized=raw.replaceAll("\\.\\d+(?=Z|[+-]\\d\\d:?\\d\\d$)","");
        String[] patterns={"yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mmXXX","yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd"};
        for(String pattern:patterns)try{SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setLenient(false);return f.parse(normalized);}catch(Exception ignored){}
        return null;
    }
    private String displayTime(String raw) {
        Date parsed; try { parsed=new Date(Long.parseLong(raw)); } catch(NumberFormatException ex) { parsed=parseDate(raw); } if(parsed==null)return raw;
        return new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext())?"HH:mm":"h:mm a",Locale.getDefault()).format(parsed);
    }
    private String allDayTime(String start) { Date parsed=parseDate(start); return parsed==null?"All day":new SimpleDateFormat("EEE, MMM d · ",Locale.getDefault()).format(parsed)+"All day"; }
    private String eventTime(String start,String end) {
        Date parsed=parseDate(start); if(parsed==null)return start.isEmpty()?"Time pending":start;
        String day=new SimpleDateFormat("yyyyMMdd",Locale.US).format(parsed);
        String today=new SimpleDateFormat("yyyyMMdd",Locale.US).format(new Date());
        return (day.equals(today)?"":new SimpleDateFormat("EEE, MMM d · ",Locale.getDefault()).format(parsed))+displayTime(start)+(end.isEmpty()?"":" – "+displayTime(end));
    }
}
