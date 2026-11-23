package com.echo.testlauncher;

import org.json.*;
import java.time.Instant;
import java.util.*;

/** Read-only mapping of a bounded calendar-widget snapshot; Java 8, Android API 28. */
public final class CalendarNormalizer {
    private CalendarNormalizer() {}

    /** Only use for a fresh fetch; cached files must supply their actual refresh time. */
    public static JSONObject normalize(JSONArray raw) throws JSONException {
        return normalize(raw, System.currentTimeMillis());
    }

    /** Refresh time is epoch milliseconds; output start/end are UTC ISO-8601 strings. */
    public static JSONObject normalize(JSONArray raw, long refreshedAt) throws JSONException {
        return normalizeAt(raw, refreshedAt, System.currentTimeMillis());
    }

    static JSONObject normalizeAt(JSONArray raw, long refreshedAt, long now) throws JSONException {
        List<JSONObject> events = new ArrayList<JSONObject>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject r = raw.optJSONObject(i);
            if (r == null || !"CALENDAR".equals(r.optString("itemType"))
                    || r.optBoolean("isNoEventsItem") || r.optBoolean("isEventHidden")) continue;
            String title = r.optString("primaryText", "").trim();
            if (title.isEmpty()) continue;
            try {
                long start = millis(r, "utcStartTimeStamp"), end = millis(r, "utcEndTimeStamp");
                if (start <= 0 || end <= start || end <= now) continue;
                boolean allDay = r.optBoolean("allDay");
                String id = r.optString("itemIdentifier", "");
                String identity = new JSONArray().put(id).put(start).put(end).put(title).put(allDay).toString();
                // Real snapshots reuse IDs on distinct dates; never deduplicate by ID alone.
                if (!id.isEmpty() && !seen.add(identity)) continue;
                events.add(new JSONObject().put("title", title)
                        .put("start", Instant.ofEpochMilli(start).toString())
                        .put("end", Instant.ofEpochMilli(end).toString()).put("allDay", allDay));
            } catch (NumberFormatException | java.time.DateTimeException invalid) {
                // Do not invent dates from localized display text or missing UTC fields.
            }
        }
        Collections.sort(events, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                int n = Instant.parse(a.optString("start")).compareTo(Instant.parse(b.optString("start")));
                if (n == 0) n = Instant.parse(a.optString("end")).compareTo(Instant.parse(b.optString("end")));
                return n != 0 ? n : a.optString("title").compareTo(b.optString("title"));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject event : events) out.put(event);
        return new JSONObject().put("events", out)
                .put("updatedAt", refreshedAt > 0 ? refreshedAt : JSONObject.NULL)
                .put("boundedSnapshot", true);
    }

    private static long millis(JSONObject r, String key) {
        Object v = r.opt(key);
        if (v instanceof String) return Long.parseLong(((String)v).trim());
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long)
            return ((Number)v).longValue();
        throw new NumberFormatException("Missing or non-integral UTC timestamp");
    }
}
