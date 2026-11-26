package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the client against a tiny loopback HTTP server. A plain
 * {@link ServerSocket} is used instead of {@code com.sun.net.httpserver},
 * which Android unit tests cannot see.
 */
public class OpenAiCompatibleClientTest {

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = true;
    private String baseUrl;

    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int status = 200;
    private volatile String response = "";
    private volatile long delayMs = 0;

    @Before
    public void setUp() throws IOException {
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        baseUrl = "http://127.0.0.1:" + serverSocket.getLocalPort();
        serverThread = new Thread(this::serve, "test-http");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @After
    public void tearDown() throws IOException {
        running = false;
        serverSocket.close();
    }

    private void serve() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                handle(socket);
            } catch (IOException e) {
                if (running) {
                    // Keep serving; only the assertion will fail if nothing arrives.
                    continue;
                }
                return;
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        requestCount.incrementAndGet();
        String[] parts = requestLine.split(" ");
        lastPath.set(parts.length > 1 ? parts[1] : "");

        lastAuth.set(null);
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("authorization")) lastAuth.set(value);
            if (name.equals("content-length")) {
                contentLength = Integer.parseInt(value);
            }
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = in.read(body, read, contentLength - read);
            if (count < 0) break;
            read += count;
        }
        lastBody.set(new String(body, 0, read, StandardCharsets.UTF_8));

        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        if (c == -1 && out.size() == 0) return null;
        return out.toString(StandardCharsets.ISO_8859_1.name());
    }

    private OpenAiCompatibleClient.Result complete(String transcript, int timeoutMs) {
        return OpenAiCompatibleClient.complete(
                baseUrl, "test-model", "secret-key", transcript,
                "<transcript>${output}</transcript>", timeoutMs, null);
    }

    @Test
    public void sendsOnlyTextToV1ChatCompletions() {
        response = "{\"choices\":[{\"message\":{\"content\":\"Привет, мир.\"},"
                + "\"finish_reason\":\"stop\"}]}";
        OpenAiCompatibleClient.Result result = complete("привет мир", 5000);

        assertNull(result.error);
        assertEquals("Привет, мир.", result.text);
        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer secret-key", lastAuth.get());

        // The request contains the transcript, model and nothing audio-like.
        assertTrue(lastBody.get().contains("привет мир"));
        assertTrue(lastBody.get().contains("test-model"));
        assertFalse(lastBody.get().toLowerCase(Locale.ROOT).contains("audio"));
        assertFalse(lastBody.get().toLowerCase(Locale.ROOT).contains("base64"));
    }

    @Test
    public void omitsAuthorizationWithoutKey() {
        response = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        OpenAiCompatibleClient.Result result = OpenAiCompatibleClient.complete(
                baseUrl, "m", "", "текст", "${output}", 5000, null);
        assertNull(result.error);
        assertNull(lastAuth.get());
    }

    @Test
    public void reportsHttpErrorStatus() {
        // HttpURLConnection hides the body of a 401 from the caller, so only
        // the status and a clear reason can be reported.
        status = 401;
        response = "{\"error\":{\"message\":\"invalid api key\"}}";
        OpenAiCompatibleClient.Result result = complete("текст", 5000);
        assertNull(result.text);
        assertEquals("HTTP 401: authentication failed", result.error);
    }

    @Test
    public void reportsServerErrorDetail() {
        status = 500;
        response = "{\"error\":{\"message\":\"model not loaded\"}}";
        OpenAiCompatibleClient.Result result = complete("текст", 5000);
        assertNull(result.text);
        assertEquals("HTTP 500: model not loaded", result.error);
    }

    @Test
    public void reportsMalformedJson() {
        response = "not json";
        OpenAiCompatibleClient.Result result = complete("текст", 5000);
        assertEquals("invalid JSON response", result.error);
    }

    @Test
    public void refusesTruncatedAnswers() {
        response = "{\"choices\":[{\"message\":{\"content\":\"half\"},"
                + "\"finish_reason\":\"length\"}]}";
        OpenAiCompatibleClient.Result result = complete("текст", 5000);
        assertNull(result.text);
        assertTrue(result.error.contains("truncated"));
    }

    @Test
    public void timesOutInsteadOfWaitingForever() {
        delayMs = 3000;
        response = "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}";
        long started = System.currentTimeMillis();
        OpenAiCompatibleClient.Result result = complete("текст", 1000);
        long elapsed = System.currentTimeMillis() - started;

        assertNull(result.text);
        assertEquals("timeout", result.error);
        assertTrue("should return near the timeout, took " + elapsed,
                elapsed < 2500);
    }

    @Test
    public void cancellationSuppressesTheResult() {
        response = "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}";
        OpenAiCompatibleClient.ConnectionSink cancelled = new OpenAiCompatibleClient.ConnectionSink() {
            @Override
            public void onOpen(HttpURLConnection connection) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        OpenAiCompatibleClient.Result result = OpenAiCompatibleClient.complete(
                baseUrl, "m", "", "текст", "${output}", 5000, cancelled);
        assertTrue(result.cancelled);
    }

    @Test
    public void cancellationBeforeBodyIsSentDoesNotReachTheServer() throws Exception {
        OpenAiCompatibleClient.ConnectionSink cancelled = new OpenAiCompatibleClient.ConnectionSink() {
            @Override
            public void onOpen(HttpURLConnection connection) {
                connection.disconnect();
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        OpenAiCompatibleClient.Result result = OpenAiCompatibleClient.complete(
                baseUrl, "m", "", "секретный текст", "${output}", 5000, cancelled);
        assertTrue(result.cancelled);
        // Give a racing write a moment to arrive before asserting.
        Thread.sleep(150);
        assertEquals("cancelled request must not send the body", 0, requestCount.get());
    }

    @Test
    public void rejectsAKeyWithControlCharactersWithoutLeakingIt() {
        String secret = "SYNTHETIC_SECRET_MARKER\nextra";
        OpenAiCompatibleClient.Result result = OpenAiCompatibleClient.complete(
                baseUrl, "m", secret, "текст", "${output}", 5000, null);
        assertEquals("invalid API key", result.error);
        assertFalse(result.error.contains("SYNTHETIC_SECRET_MARKER"));
        // The value never reached the HTTP layer.
        assertEquals(0, requestCount.get());
    }
}
