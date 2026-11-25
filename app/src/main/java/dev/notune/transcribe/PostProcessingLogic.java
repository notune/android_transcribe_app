package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-Java helpers for the optional OpenAI-compatible text post-processing.
 *
 * <p>Kept free of Android classes so it can be covered by fast JVM unit tests
 * (see {@code app/src/test}). The network call itself lives in
 * {@link OpenAiCompatibleClient}; settings and threading in
 * {@link PostProcessor}.
 *
 * <p>Privacy: this class only ever sees the recognised text. Audio never
 * reaches it, and nothing here logs the text or the API key.
 */
final class PostProcessingLogic {

    static final String PLACEHOLDER = "${output}";
    private static final String THINK_OPEN = "\u003cthink\u003e";
    private static final String THINK_CLOSE = "\u003c/think\u003e";

    private static final String CHAT_COMPLETIONS = "/chat/completions";
    private static final String V1 = "/v1";

    private PostProcessingLogic() {
    }

    /**
     * Turns what the user typed into a full chat-completions URL.
     *
     * <ul>
     *   <li>{@code https://api.example.com} becomes
     *       {@code https://api.example.com/v1/chat/completions}</li>
     *   <li>{@code https://api.example.com/v1} becomes
     *       {@code https://api.example.com/v1/chat/completions}</li>
     *   <li>a URL that already ends in {@code /chat/completions} is kept,
     *       so gateways with custom paths work</li>
     * </ul>
     */
    static String buildChatCompletionsUrl(String endpoint) {
        String url = endpoint == null ? "" : endpoint.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            throw new IllegalArgumentException("empty endpoint");
        }
        if (url.endsWith(CHAT_COMPLETIONS)) {
            return url;
        }
        if (url.endsWith(V1)) {
            return url + CHAT_COMPLETIONS;
        }
        return url + V1 + CHAT_COMPLETIONS;
    }

    /** True for http:// addresses, which send the text without transport encryption. */
    static boolean isCleartextEndpoint(String endpoint) {
        String url = endpoint == null ? "" : endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        return url.startsWith("http://");
    }

    /**
     * Fills the editable instruction with the transcript.
     *
     * <p>The default instruction is a Handy-style template whose first line is
     * {@code <transcript>${output}</transcript>}. If the user removes the
     * placeholder, the transcript is appended on a new line instead of being
     * lost.
     */
    static String fillPrompt(String template, String transcript) {
        String prompt = template == null ? "" : template;
        String text = transcript == null ? "" : transcript;
        if (prompt.contains(PLACEHOLDER)) {
            return prompt.replace(PLACEHOLDER, text);
        }
        if (prompt.trim().isEmpty()) {
            return text;
        }
        return prompt + "\n\n" + text;
    }

    /**
     * Builds the smallest broadly compatible chat-completions request. No
     * temperature, max_tokens, response_format or reasoning fields: local
     * servers and gateways disagree about them, and the instruction already
     * asks for plain text only.
     */
    static String buildRequestJson(String model, String prompt) {
        JSONObject message = new JSONObject();
        try {
            message.put("role", "user");
            message.put("content", prompt == null ? "" : prompt);

            JSONArray messages = new JSONArray();
            messages.put(message);

            JSONObject root = new JSONObject();
            root.put("model", model == null ? "" : model);
            root.put("messages", messages);
            root.put("stream", false);
            return root.toString();
        } catch (JSONException e) {
            // Strings and literals only; this cannot fail in practice.
            throw new IllegalStateException("could not build request", e);
        }
    }

    /** Parsed piece of a chat-completions response. */
    static final class Response {
        final String content;
        final boolean truncated;

        Response(String content, boolean truncated) {
            this.content = content;
            this.truncated = truncated;
        }
    }

    /**
     * Extracts {@code choices[0].message.content}. Throws with a short,
     * user-safe message when the server returned an error object or no choice.
     * Error text is never taken from the transcript.
     */
    static Response parseResponse(String body) throws ApiException {
        if (body == null || body.trim().isEmpty()) {
            throw new ApiException("empty response");
        }
        final JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new ApiException("invalid JSON response");
        }

        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "");
            throw new ApiException(message.isEmpty() ? "server error" : message);
        }

        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new ApiException("no choices in response");
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            throw new ApiException("invalid choice in response");
        }
        String finishReason = choice.optString("finish_reason", "");
        JSONObject message = choice.optJSONObject("message");
        String content = message == null ? null : message.optString("content", null);
        if (content == null) {
            // Some servers put the answer in "text" for the legacy completions shape.
            content = choice.optString("text", null);
        }
        boolean truncated = "length".equals(finishReason);
        return new Response(content, truncated);
    }

    /**
     * Removes transport-level noise some servers add: invisible characters,
     * a leading chain-of-thought block (local models often put reasoning into
     * content), and a Markdown code fence wrapped around the whole answer.
     * The instruction itself forbids these; this is a safety net, not a licence
     * to rewrite the model's wording.
     */
    static String sanitizeOutput(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "");

        text = text.trim();
        if (text.startsWith(THINK_OPEN)) {
            int end = text.indexOf(THINK_CLOSE);
            if (end >= 0) {
                text = text.substring(end + THINK_CLOSE.length()).trim();
            }
        }

        if (text.startsWith("```") && text.endsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1, text.length() - 3).trim();
            }
        }
        return text;
    }

    /**
     * True when the transcript consists only of hesitation sounds, so an empty
     * cleaned result can be treated as "nothing was said" rather than as a
     * failed request that must fall back to the raw text.
     */
    static boolean isFillerOnly(String text) {
        if (text == null) return true;
        String normalised = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{Punct}\u2026\u2014\u2013]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalised.isEmpty()) return true;

        java.util.Set<String> fillers = new java.util.HashSet<>();
        java.util.Collections.addAll(fillers,
                "э", "ээ", "эээ", "ээээ", "м", "мм", "ммм", "мммм",
                "а", "аа", "ааа", "о", "оо", "ооо", "у", "уу", "ууу",
                "ы", "ыы", "ыыы", "хм", "хмм", "хммм", "кхм", "эм", "ээм",
                "uh", "um", "umm", "mmm", "hmm", "er", "erm");
        for (String token : normalised.split(" ")) {
            if (!fillers.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code error.message} from an error body, or null when the body
     * is empty, not JSON, or carries no error object. Used to explain a failed
     * HTTP status without inventing a message.
     */
    static String errorMessageOrNull(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error == null) return null;
            String message = error.optString("message", "").trim();
            return message.isEmpty() ? null : message;
        } catch (JSONException ignored) {
            return null;
        }
    }

    /** Short, user-facing reason. Never includes the transcript or key. */
    static String shortError(String error) {
        if (error == null || error.trim().isEmpty()) {
            return "unknown error";
        }
        String text = error.trim().replace('\n', ' ');
        if (text.length() > 160) {
            text = text.substring(0, 157) + "...";
        }
        return text;
    }

    static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}
