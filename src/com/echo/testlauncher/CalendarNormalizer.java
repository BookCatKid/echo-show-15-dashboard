package com.echo.testlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only mapping of a bounded calendar-widget snapshot. Java 8, Android API 28. */
public final class CalendarNormalizer {

    private CalendarNormalizer() {
    }

    /** Only use for a fresh fetch; cached files must supply their actual refresh time. */
    public static JSONObject normalize(JSONArray raw) throws JSONException {
        return normalize(raw, System.currentTimeMillis());
    }

    /** Refresh time is epoch milliseconds; output start and end are UTC ISO-8601 strings. */
    public static JSONObject normalize(JSONArray raw, long refreshedAt) throws JSONException {
        return normalizeAt(raw, refreshedAt, System.currentTimeMillis());
    }

    static JSONObject normalizeAt(JSONArray raw, long refreshedAt, long now)
            throws JSONException {
        List<JSONObject> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null
                    || !"CALENDAR".equals(item.optString("itemType"))
                    || item.optBoolean("isNoEventsItem")
                    || item.optBoolean("isEventHidden")) {
                continue;
            }
            String title = item.optString("primaryText", "").trim();
            if (title.isEmpty()) {
                continue;
            }
            try {
                long start = timestampMillis(item, "utcStartTimeStamp");
                long end = timestampMillis(item, "utcEndTimeStamp");
                if (start <= 0 || end <= start || end <= now) {
                    continue;
                }
                boolean allDay = item.optBoolean("allDay");
                String id = item.optString("itemIdentifier", "");

                // Snapshots reuse identifiers on distinct dates, so identity includes the
                // event's own times and title rather than the identifier alone.
                String identity = new JSONArray()
                        .put(id)
                        .put(start)
                        .put(end)
                        .put(title)
                        .put(allDay)
                        .toString();
                if (!id.isEmpty() && !seen.add(identity)) {
                    continue;
                }

                events.add(new JSONObject()
                        .put("title", title)
                        .put("start", Instant.ofEpochMilli(start).toString())
                        .put("end", Instant.ofEpochMilli(end).toString())
                        .put("allDay", allDay));
            } catch (NumberFormatException | DateTimeException invalid) {
                // Never invent dates from localized display text or missing UTC fields.
            }
        }

        Collections.sort(events, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject first, JSONObject second) {
                int order = Instant.parse(first.optString("start"))
                        .compareTo(Instant.parse(second.optString("start")));
                if (order == 0) {
                    order = Instant.parse(first.optString("end"))
                            .compareTo(Instant.parse(second.optString("end")));
                }
                return order != 0
                        ? order
                        : first.optString("title").compareTo(second.optString("title"));
            }
        });

        JSONArray output = new JSONArray();
        for (JSONObject event : events) {
            output.put(event);
        }
        return new JSONObject()
                .put("events", output)
                .put("updatedAt", refreshedAt > 0 ? refreshedAt : JSONObject.NULL)
                .put("boundedSnapshot", true);
    }

    private static long timestampMillis(JSONObject item, String key) {
        Object value = item.opt(key);
        if (value instanceof String) {
            return Long.parseLong(((String) value).trim());
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new NumberFormatException("Missing or non-integral UTC timestamp");
    }
}
