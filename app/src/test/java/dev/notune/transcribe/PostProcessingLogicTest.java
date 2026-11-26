package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class PostProcessingLogicTest {

    @Test
    public void appendsV1ChatCompletionsToBareHost() {
        assertEquals("https://api.example.com/v1/chat/completions",
                PostProcessingLogic.buildChatCompletionsUrl("https://api.example.com"));
        assertEquals("https://api.example.com/v1/chat/completions",
                PostProcessingLogic.buildChatCompletionsUrl("https://api.example.com/"));
    }

    @Test
    public void keepsV1AndFullPaths() {
        assertEquals("https://api.example.com/v1/chat/completions",
                PostProcessingLogic.buildChatCompletionsUrl("https://api.example.com/v1"));
        assertEquals("https://api.example.com/openai/v1/chat/completions",
                PostProcessingLogic.buildChatCompletionsUrl("https://api.example.com/openai/v1/"));
        assertEquals("https://gateway.example.com/custom/chat/completions",
                PostProcessingLogic.buildChatCompletionsUrl(
                        "https://gateway.example.com/custom/chat/completions"));
    }

    @Test
    public void recognisesLocalAndCleartextAddresses() {
        assertTrue(PostProcessingLogic.isCleartextEndpoint("http://192.168.1.5:1234"));
        assertFalse(PostProcessingLogic.isCleartextEndpoint("https://api.example.com"));
    }

    @Test
    public void fillsPlaceholderEverywhere() {
        String prompt = "<transcript>\n${output}\n</transcript>\nClean it.";
        String filled = PostProcessingLogic.fillPrompt(prompt, "привет");
        assertTrue(filled.contains("привет"));
        assertFalse(filled.contains("${output}"));
    }

    @Test
    public void appendsTranscriptWhenPlaceholderWasRemoved() {
        String filled = PostProcessingLogic.fillPrompt("Очисти текст.", "привет");
        assertTrue(filled.startsWith("Очисти текст."));
        assertTrue(filled.endsWith("привет"));
    }

    @Test
    public void buildsCompatibleJsonAndEscapesQuotes() throws Exception {
        String json = PostProcessingLogic.buildRequestJson("qwen", "он сказал \"да\"\nи ушёл");
        JSONObject root = new JSONObject(json);
        assertEquals("qwen", root.getString("model"));
        assertFalse(root.getBoolean("stream"));
        assertEquals("он сказал \"да\"\nи ушёл",
                root.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("user",
                root.getJSONArray("messages").getJSONObject(0).getString("role"));
    }

    @Test
    public void parsesContentAndTruncation() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Готово.\"},"
                + "\"finish_reason\":\"stop\"}]}";
        PostProcessingLogic.Response response = PostProcessingLogic.parseResponse(body);
        assertEquals("Готово.", response.content);
        assertFalse(response.truncated);

        String truncated = "{\"choices\":[{\"message\":{\"content\":\"Обрезано\"},"
                + "\"finish_reason\":\"length\"}]}";
        assertTrue(PostProcessingLogic.parseResponse(truncated).truncated);
    }

    @Test
    public void reportsServerErrorsWithoutLeakingTheTranscript() {
        String body = "{\"error\":{\"message\":\"invalid api key\"}}";
        try {
            PostProcessingLogic.parseResponse(body);
            fail("expected ApiException");
        } catch (PostProcessingLogic.ApiException e) {
            assertEquals("invalid api key", e.getMessage());
        }
    }

    @Test
    public void rejectsResponsesWithoutChoices() {
        try {
            PostProcessingLogic.parseResponse("{\"choices\":[]}");
            fail("expected ApiException");
        } catch (PostProcessingLogic.ApiException e) {
            assertTrue(e.getMessage().contains("choices"));
        }
    }

    @Test
    public void extractsOnlyRealServerErrorMessages() {
        assertEquals("invalid api key", PostProcessingLogic.errorMessageOrNull(
                "{\"error\":{\"message\":\"invalid api key\"}}"));
        assertEquals(null, PostProcessingLogic.errorMessageOrNull(""));
        assertEquals(null, PostProcessingLogic.errorMessageOrNull("<html>gateway</html>"));
        assertEquals(null, PostProcessingLogic.errorMessageOrNull("{\"choices\":[]}"));
    }

    @Test
    public void stripsInvisibleCharsThinkBlockAndCodeFence() {
        String withThink = "\u200BГотовый текст.";
        assertEquals("Готовый текст.", PostProcessingLogic.sanitizeOutput(withThink));

        String fenced = "```text\nГотовый текст.\n```";
        assertEquals("Готовый текст.", PostProcessingLogic.sanitizeOutput(fenced));

        assertEquals("", PostProcessingLogic.sanitizeOutput(null));
    }

    @Test
    public void detectsFillerOnlyText() {
        assertTrue(PostProcessingLogic.isFillerOnly("Эээ, ммм, эээ."));
        assertTrue(PostProcessingLogic.isFillerOnly("uh, um..."));
        assertTrue(PostProcessingLogic.isFillerOnly("   "));
        assertFalse(PostProcessingLogic.isFillerOnly("Эээ, проверь логи."));
        assertFalse(PostProcessingLogic.isFillerOnly("Нужно два сервера."));
    }

    @Test
    public void shortErrorIsBoundedAndSingleLine() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) longText.append("очень длинная ошибка ");
        String shortError = PostProcessingLogic.shortError(longText + "\nвторая строка");
        assertTrue(shortError.length() <= 160);
        assertFalse(shortError.contains("\n"));
    }

    @Test
    public void rejectsHeaderValuesWithControlCharacters() {
        assertTrue(PostProcessingLogic.isValidHeaderValue("sk-synthetic-key-123"));
        assertTrue(PostProcessingLogic.isValidHeaderValue("   "));
        assertTrue(PostProcessingLogic.isValidHeaderValue(""));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\nnext"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\rnext"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\0next"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\tnext"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\n"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("\rkey"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("key\0"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("\0"));
        assertFalse(PostProcessingLogic.isValidHeaderValue("ключ"));
    }
}
