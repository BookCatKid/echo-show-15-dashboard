package com.echo.testlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Blocking, dependency-free HA REST client. Call every network method off the UI thread.
 * No automatic retries (a timed-out mutation may already have succeeded).
 * JSON/schema failures throw JSONException; transport/HTTP failures throw IOException.
 */
public final class HaClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 12 * 1024 * 1024;
    private static final String SONOS_TEMPLATE =
            "{{ integration_entities('sonos') | select('match', '^media_player\\\\.')"
            + " | list | to_json }}";

    private final URL base;
    private final String token;

    /** Base is an HTTP(S) instance URL, optionally with a reverse-proxy path prefix. */
    public HaClient(String base, String token) {
        if (base == null || token == null || token.trim().isEmpty()
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HA base URL and token are required");
        }
        try {
            URL parsed = new URL(base.trim());
            validateUrl(parsed);
            if (parsed.getQuery() != null || parsed.getRef() != null) {
                throw new IllegalArgumentException("HA base URL must not have query or fragment");
            }
            String normalized = parsed.toExternalForm();
            this.base = new URL(normalized.endsWith("/") ? normalized : normalized + "/");
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid HA base URL");
        }
        this.token = token;
    }

    HaStateFeed stateFeed() {
        try {
            URL endpoint=new URL(base,"api/websocket");
            return new HaStateFeed(new java.net.URI("https".equals(endpoint.getProtocol())?"wss":"ws",null,endpoint.getHost(),endpoint.getPort(),endpoint.getPath(),null,null),token);
        }catch(Exception e){throw new IllegalArgumentException("Invalid HA WebSocket URL");}
    }

    public JSONArray getStates() throws IOException, JSONException {
        return new JSONArray(jsonRequest("GET", "api/states", null));
    }

    public JSONObject getPlayerState(String entity) throws IOException, JSONException {
        requireEntity(entity, "media_player");
        return new JSONObject(jsonRequest("GET", "api/states/" + entity, null));
    }

    /** Actual Sonos integration membership, not names. Requires template API permission.
     * https://www.home-assistant.io/template-functions/integration_entities/
     * https://developers.home-assistant.io/docs/api/rest/#templates
     */
    public JSONArray getSonosIds() throws IOException, JSONException {
        JSONObject body = new JSONObject().put("template", SONOS_TEMPLATE);
        JSONArray ids = new JSONArray(jsonRequest("POST", "api/template", body));
        JSONArray result = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String entity = ids.getString(i);
            if (!entity.startsWith("media_player.")) {
                throw new JSONException("Unexpected entity domain in Sonos template response");
            }
            if (seen.add(entity)) result.put(entity);
        }
        return result;
    }

    /** Includes checked and unchecked items, with summary/uid/status fields. */
    public JSONArray getItems(String todo) throws IOException, JSONException {
        requireEntity(todo, "todo");
        JSONObject data = new JSONObject().put("entity_id", todo)
                .put("status", new JSONArray().put("needs_action").put("completed"));
        return call("todo", "get_items", data, true)
                .getJSONObject("service_response").getJSONObject(todo).getJSONArray("items");
    }

    /**
     * Data is the flat REST body, including entity_id; it is not mutated.
     * response=true: returns {changed_states: [...], service_response: {...}}.
     * response=false: wraps HA's state array as {changed_states: [...]}.
     */
    public JSONObject call(String domain, String service, JSONObject data, boolean response)
            throws IOException, JSONException {
        requireIdentifier(domain);
        requireIdentifier(service);
        String text = jsonRequest("POST", "api/services/" + domain + "/" + service
                + (response ? "?return_response" : ""), data == null ? new JSONObject() : data);
        if (!response) return new JSONObject().put("changed_states", new JSONArray(text));
        JSONObject envelope = new JSONObject(text);
        envelope.getJSONArray("changed_states");
        envelope.getJSONObject("service_response");
        return envelope;
    }

    /** Returns children of the requested browse level; null/empty type and id mean root. */
    public JSONArray browse(String player, String type, String id) throws IOException, JSONException {
        JSONObject tree = browseObject(player, type, id);
        if (!tree.has("children") || tree.isNull("children")) return new JSONArray();
        return tree.getJSONArray("children");
    }

    /** Full browse level, including title, capability flags, thumbnail and children. */
    public JSONObject browseObject(String player, String type, String id)
            throws IOException, JSONException {
        requireEntity(player, "media_player");
        JSONObject data = new JSONObject().put("entity_id", player);
        if (type != null && !type.isEmpty()) data.put("media_content_type", type);
        if (id != null) data.put("media_content_id", id);
        return call("media_player", "browse_media", data, true)
                .getJSONObject("service_response").getJSONObject(player);
    }

    /**
     * Null/blank path means no artwork and returns null. Relative URLs resolve against base.
     * External HTTP(S) images are allowed WITHOUT the token. Redirects are never followed.
     * Decoding is sampled to at most 2048 pixels per dimension.
     */
    public Bitmap artwork(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) return null;
        URL url;
        try {
            url = new URL(base, path);
            validateUrl(url);
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IOException("Invalid artwork URL");
        }
        byte[] bytes = request("GET", url, null, IMAGE_LIMIT, sameOrigin(base, url));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Artwork is not a decodable image");
        }
        options.inSampleSize = 1;
        while ((long) options.outWidth > 2048L * options.inSampleSize
                || (long) options.outHeight > 2048L * options.inSampleSize) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Artwork decode failed");
        return bitmap;
    }

    private String jsonRequest(String method, String path, JSONObject data) throws IOException {
        byte[] body = data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = request(method, new URL(base, path), body, JSON_LIMIT, true);
        return new String(result, StandardCharsets.UTF_8);
    }

    private byte[] request(String method, URL url, byte[] body, int limit, boolean authenticated)
            throws IOException {
        if (authenticated && !sameOrigin(base, url)) {
            throw new IOException("Refusing credentials for an external origin");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", limit == IMAGE_LIMIT ? "image/*" : "application/json, text/plain");
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                // Do not echo URLs, tokens, server bodies, or redirect locations into logs/errors.
                InputStream error = connection.getErrorStream();
                if (error != null) error.close();
                throw new HttpException(status);
            }
            if (connection.getContentLengthLong() > limit) {
                throw new IOException("HA response exceeds size limit");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > limit - output.size()) throw new IOException("HA response exceeds size limit");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    public static final class HttpException extends IOException {
        public final int statusCode;

        private HttpException(int statusCode) {
            super("HA HTTP " + statusCode + (statusCode >= 300 && statusCode < 400
                    ? " (redirect refused)" : ""));
            this.statusCode = statusCode;
        }
    }

    private static void validateUrl(URL url) {
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost().isEmpty() || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Expected HTTP(S) URL without embedded credentials");
        }
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URL url) {
        return url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
    }

    private static void requireIdentifier(String value) {
        if (value == null || !value.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid HA domain or service");
        }
    }

    private static void requireEntity(String value, String domain) {
        if (value == null || !value.matches(domain + "\\.[a-z0-9_]+")) {
            throw new IllegalArgumentException("Expected " + domain + " entity ID");
        }
    }
}
