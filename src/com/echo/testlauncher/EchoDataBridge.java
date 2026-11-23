package com.echo.testlauncher;

import android.system.Os;
import android.system.StructStat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privileged read-only Echo source adapter. Runs as its own process and exports a snapshot
 * file that the dashboard reads; it never talks to Home Assistant.
 */
public final class EchoDataBridge {

    private static final long POLL_INTERVAL_MS = 30000;
    private static final long WEATHER_REFRESH_MS = 300000;
    private static final int MAX_INPUT_BYTES = 4_000_000;

    private static final String DEFAULT_DIRECTORY = "/data/user/0/com.echo.testlauncher/files";
    private static final String WEATHER_URI =
            "content://com.amazon.paladin.weather.provider/weather";
    private static final String CALENDAR_SOURCE =
            "/data/user/0/com.amazon.paladin/files/dashboard-calendar.json";
    private static final String OUTPUT_NAME = "echo-data.json";

    private static final Pattern WEATHER_FIELD = Pattern.compile(
            "(?:Row: \\d+ |, )"
                    + "(weatherConditionCode|weatherSummary|location|temperatureScale|temperatureValue)"
                    + "=(.*?)(?=, [A-Za-z]+=|$)",
            Pattern.DOTALL);

    private EchoDataBridge() {
    }

    public static void main(String[] args) throws Exception {
        String directory = args.length > 0 ? args[0] : DEFAULT_DIRECTORY;
        JSONObject snapshot = new JSONObject();
        long lastWeatherRead = 0;

        while (true) {
            try {
                if (System.currentTimeMillis() - lastWeatherRead > WEATHER_REFRESH_MS) {
                    try {
                        snapshot.put("weather", readWeather());
                        lastWeatherRead = System.currentTimeMillis();
                    } catch (Exception e) {
                        System.err.println("Echo weather read pending");
                    }
                }
                snapshot.put("calendar", readCalendar());
                writeSnapshot(new File(directory), snapshot);
            } catch (Exception e) {
                System.err.println(
                        "Echo data export pending: " + e.getClass().getSimpleName());
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private static JSONObject readWeather()
            throws IOException, JSONException, InterruptedException {
        Process process = new ProcessBuilder(
                "/system/bin/runcon",
                "u:r:shell:s0",
                "/system/bin/content",
                "query",
                "--uri",
                WEATHER_URI)
                .redirectErrorStream(true)
                .start();
        String output = readText(process.getInputStream());
        process.waitFor();
        if (!output.startsWith("Row:")) {
            throw new IOException("Echo weather is unavailable");
        }

        JSONObject weather = new JSONObject();
        Matcher matcher = WEATHER_FIELD.matcher(output.trim());
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            if ("temperatureValue".equals(key) || "weatherConditionCode".equals(key)) {
                weather.put(key, Integer.parseInt(value));
            } else {
                weather.put(key, value);
            }
        }
        if (!weather.has("temperatureValue")) {
            throw new IOException("No cached weather");
        }
        weather.put("updatedAt", System.currentTimeMillis());
        return weather;
    }

    private static JSONObject readCalendar() throws IOException, JSONException {
        File source = new File(CALENDAR_SOURCE);
        if (!source.isFile()) {
            return new JSONObject()
                    .put("events", new JSONArray())
                    .put("status", "Connecting to Echo calendar");
        }
        try (InputStream input = new FileInputStream(source)) {
            return CalendarNormalizer.normalize(
                    new JSONArray(readText(input)), source.lastModified());
        }
    }

    private static void writeSnapshot(File directory, JSONObject snapshot) throws Exception {
        if (!directory.isDirectory()) {
            return;
        }
        StructStat directoryStat = Os.stat(directory.getAbsolutePath());
        File temporary = new File(directory, "echo-data.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
        }

        String path = temporary.getAbsolutePath();
        Os.chown(path, directoryStat.st_uid, directoryStat.st_gid);
        Os.chmod(path, 0600);
        new ProcessBuilder("/system/bin/restorecon", path).start().waitFor();
        Os.rename(path, new File(directory, OUTPUT_NAME).getAbsolutePath());
    }

    private static String readText(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_INPUT_BYTES) {
                    throw new IOException("Input too large");
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
