package dev.notune.transcribe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency-light client for an OpenAI-compatible
 * {@code /chat/completions} endpoint.
 *
 * <p>Only the recognised text is sent. There is no audio parameter anywhere in
 * this class, and neither the text nor the API key is logged. Plain HTTP is
 * allowed because local inference servers commonly use it; the settings screen
 * warns about it.
 */
final class OpenAiCompatibleClient {

    /** Lets the caller cancel the request by disconnecting the socket. */
    interface ConnectionSink {
        void onOpen(HttpURLConnection connection);

        boolean isCancelled();
    }

    static final class Result {
        final String text;
        final String error;
        final boolean cancelled;

        private Result(String text, String error, boolean cancelled) {
            this.text = text;
            this.error = error;
            this.cancelled = cancelled;
        }

        static Result ok(String text) {
            return new Result(text, null, false);
        }

        static Result error(String error) {
            return new Result(null, error, false);
        }

        static Result cancelled() {
            return new Result(null, "cancelled", true);
        }
    }

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_CHARS = 300;

    private OpenAiCompatibleClient() {
    }

    static Result complete(String endpoint,
                           String model,
                           String apiKey,
                           String transcript,
                           String promptTemplate,
                           int timeoutMs,
                           ConnectionSink sink) {
        final String url;
        final String body;
        try {
            url = PostProcessingLogic.buildChatCompletionsUrl(endpoint);
            String prompt = PostProcessingLogic.fillPrompt(promptTemplate, transcript);
            body = PostProcessingLogic.buildRequestJson(model, prompt);
        } catch (RuntimeException e) {
            return Result.error("invalid settings");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Math.max(1000, Math.min(10000, timeoutMs)));
            connection.setReadTimeout(Math.max(1000, timeoutMs));
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            // A redirect must not carry the API key to another host.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            // Validate the raw value first: trim() would silently strip a
            // leading or trailing control character (including NUL), and such
            // a key would then be sent as if it were valid.
            String rawKey = apiKey == null ? "" : apiKey;
            if (!rawKey.isEmpty() && !PostProcessingLogic.isValidHeaderValue(rawKey)) {
                // Reject before setRequestProperty: its exception repeats the
                // header value, which here is the key itself.
                return Result.error("invalid API key");
            }
            String key = rawKey.trim();
            if (!key.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            }
            if (sink != null) {
                sink.onOpen(connection);
                if (sink.isCancelled()) {
                    // Cancellation may already have disconnected the socket in
                    // onOpen; do not open the request body or send the text.
                    return Result.cancelled();
                }
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                if (sink != null && sink.isCancelled()) {
                    return Result.cancelled();
                }
                out.write(payload);
            }

            if (sink != null && sink.isCancelled()) {
                return Result.cancelled();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readLimited(stream);
            if (response == null) {
                return Result.error("response too large");
            }

            if (status < 200 || status >= 300) {
                return Result.error(httpError(connection, status, response));
            }

            PostProcessingLogic.Response parsed = PostProcessingLogic.parseResponse(response);
            if (parsed.truncated) {
                // A cut-off answer would silently drop the end of a dictation.
                return Result.error("response truncated by the model");
            }
            return Result.ok(PostProcessingLogic.sanitizeOutput(parsed.content));
        } catch (SocketTimeoutException e) {
            return Result.error("timeout");
        } catch (PostProcessingLogic.ApiException e) {
            return Result.error(PostProcessingLogic.shortError(e.getMessage()));
        } catch (IllegalArgumentException e) {
            // The message of such an exception can repeat a header value or a
            // URL, so it is neither returned nor logged: fixed text only.
            return Result.error("invalid settings");
        } catch (IOException e) {
            if (sink != null && sink.isCancelled()) {
                return Result.cancelled();
            }
            return Result.error("network error");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String httpError(HttpURLConnection connection, int status, String body) {
        String detail = PostProcessingLogic.errorMessageOrNull(body);
        if (detail == null) detail = "";
        detail = detail.trim().replace('\n', ' ');
        if (detail.length() > MAX_ERROR_CHARS) {
            detail = detail.substring(0, MAX_ERROR_CHARS) + "...";
        }
        if (!detail.isEmpty()) {
            return "HTTP " + status + ": " + detail;
        }
        // A 401 body is often unreadable to HttpURLConnection: it reserves
        // authentication failures for itself and exposes no error stream.
        if (status == 401) return "HTTP 401: authentication failed";
        if (status == 403) return "HTTP 403: access denied";
        try {
            String reason = connection.getResponseMessage();
            if (reason != null && !reason.trim().isEmpty()
                    && !"ok".equalsIgnoreCase(reason.trim())) {
                return "HTTP " + status + ": " + reason.trim();
            }
        } catch (IOException ignored) {
            // Fall through to the bare status.
        }
        return "HTTP " + status;
    }

    /** Reads a response body, refusing oversized ones. Null means too large. */
    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
