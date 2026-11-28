package dev.notune.transcribe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one optional text-cleaning request off the main thread.
 *
 * <p>Callers get a {@link Task} handle. Cancelling it disconnects the request
 * and suppresses the callback, so a cancelled or replaced dictation can never
 * insert a late result. A watchdog enforces a single bounded wait: the request
 * is disconnected after the configured timeout even if the server trickles
 * bytes.
 *
 * <p>Only the recognised text is passed in. The class never touches audio and
 * never logs the text or the API key.
 */
final class PostProcessor {

    interface Callback {
        /** {@code text} is null when {@code error} is set. Runs on the main thread. */
        void onResult(String text, String error);
    }

    interface ModelsCallback {
        /** {@code models} is null when {@code error} is set. Runs on the main thread. */
        void onResult(java.util.List<String> models, String error);
    }

    private static final String TAG = "OfflineVoiceInput";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, daemonFactory("post-processing"));
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("post-processing-watchdog"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PostProcessor() {
    }

    static Task run(Context context, String transcript, Callback callback) {
        Task task = new Task();
        EXECUTOR.execute(() -> execute(task, Config.freeze(context), transcript, callback));
        return task;
    }

    /**
     * Runs with a configuration captured earlier. A caller that shows the
     * result later uses this so a change made in the meantime cannot mix an
     * address saved now with a key saved before.
     */
    static Task run(String transcript, Config config, Callback callback) {
        Task task = new Task();
        EXECUTOR.execute(() -> execute(task, config, transcript, callback));
        return task;
    }

    private static void execute(Task task, Config config, String transcript,
                                Callback callback) {
        if (task.isUserCancelled()) return;

        String endpoint = config.endpoint;
        String model = config.model;
        String apiKey = config.apiKey;
        String prompt = config.prompt;
        int timeoutMs = config.timeoutMs;

        if (endpoint.isEmpty() || model.isEmpty()) {
            deliver(task, callback, null, "not configured");
            return;
        }

        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            task.timedOut = true;
            task.disconnect();
            // The blocked socket may not notice the disconnect promptly.
            // Report the timeout here so the caller never waits forever.
            deliver(task, callback, null, "timeout");
        }, timeoutMs, TimeUnit.MILLISECONDS);
        task.watchdog = watchdog;

        OpenAiCompatibleClient.Result result;
        try {
            result = OpenAiCompatibleClient.complete(
                    endpoint, model, apiKey, transcript, prompt, timeoutMs, task);
        } catch (RuntimeException e) {
            // The exception text is not logged: it can echo a header value,
            // and the key must never reach the journal.
            Log.w(TAG, "Post-processing failed unexpectedly");
            result = OpenAiCompatibleClient.Result.error("request failed");
        } finally {
            watchdog.cancel(false);
        }

        if (task.isUserCancelled()) return;

        if (task.timedOut) {
            deliver(task, callback, null, "timeout");
            return;
        }
        if (result.cancelled) {
            return;
        }
        if (result.error != null) {
            deliver(task, callback, null, result.error);
            return;
        }
        deliver(task, callback, result.text, null);
    }

    private static void deliver(Task task, Callback callback, String text, String error) {
        // One result per task: a late answer after a timeout or cancel is dropped.
        if (!task.delivered.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            if (task.isUserCancelled()) return;
            callback.onResult(text, error);
        });
    }

    /**
     * Fetches the server's model list off the main thread.
     *
     * <p>Same cancellation and timeout contract as {@link #run}: a late answer
     * after a timeout or a screen exit is dropped. No dictation text is sent.
     */
    static Task listModels(Context context, ModelsCallback callback) {
        Context app = context.getApplicationContext();
        Task task = new Task();
        EXECUTOR.execute(() -> {
            if (task.isUserCancelled()) return;

            String endpoint = PostProcessingSettings.getEndpoint(app);
            String apiKey = PostProcessingSettings.getApiKey(app);
            int timeoutMs = PostProcessingSettings.getTimeoutMs(app);

            if (endpoint.isEmpty()) {
                deliverModels(task, callback, null, "not configured");
                return;
            }

            ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
                task.timedOut = true;
                task.disconnect();
                deliverModels(task, callback, null, "timeout");
            }, timeoutMs, TimeUnit.MILLISECONDS);
            task.watchdog = watchdog;

            OpenAiCompatibleClient.Models result;
            try {
                result = OpenAiCompatibleClient.listModels(endpoint, apiKey, timeoutMs, task);
            } catch (RuntimeException e) {
                // The exception text is not logged: it can echo a header value.
                Log.w(TAG, "Model list failed unexpectedly");
                result = OpenAiCompatibleClient.Models.error("request failed");
            } finally {
                watchdog.cancel(false);
            }

            if (task.isUserCancelled()) return;
            if (task.timedOut) {
                deliverModels(task, callback, null, "timeout");
                return;
            }
            if (result.cancelled) {
                return;
            }
            if (result.error != null) {
                deliverModels(task, callback, null, result.error);
                return;
            }
            deliverModels(task, callback, result.ids, null);
        });
        return task;
    }

    private static void deliverModels(Task task, ModelsCallback callback,
                                      java.util.List<String> models, String error) {
        if (!task.delivered.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            if (task.isUserCancelled()) return;
            callback.onResult(models, error);
        });
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * One immutable copy of the settings a request runs with. Endpoint, model
     * and key are otherwise read separately, so a save that fails half way
     * could pair a new address with an old key.
     */
    static final class Config {
        final boolean enabled;
        final String endpoint;
        final String model;
        final String apiKey;
        final String prompt;
        final int timeoutMs;

        private Config(boolean enabled, String endpoint, String model, String apiKey,
                       String prompt, int timeoutMs) {
            this.enabled = enabled;
            this.endpoint = endpoint;
            this.model = model;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.prompt = prompt;
            this.timeoutMs = timeoutMs;
        }

        static Config freeze(Context context) {
            Context app = context.getApplicationContext();
            return new Config(
                    PostProcessingSettings.isEnabled(app),
                    PostProcessingSettings.getEndpoint(app),
                    PostProcessingSettings.getModel(app),
                    PostProcessingSettings.getApiKey(app),
                    PostProcessingSettings.getPrompt(app),
                    PostProcessingSettings.getTimeoutMs(app));
        }
    }

    static final class Task implements OpenAiCompatibleClient.ConnectionSink {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean delivered = new AtomicBoolean(false);
        private volatile HttpURLConnection connection;
        private volatile ScheduledFuture<?> watchdog;
        private volatile boolean timedOut;

        @Override
        public void onOpen(HttpURLConnection opened) {
            connection = opened;
            if (cancelled.get() || timedOut) {
                disconnect();
            }
        }

        @Override
        public boolean isCancelled() {
            // Also true after a timeout: the request must stop, but the caller
            // still gets a timeout callback.
            return cancelled.get() || timedOut;
        }

        boolean isUserCancelled() {
            return cancelled.get();
        }

        void cancel() {
            cancelled.set(true);
            ScheduledFuture<?> w = watchdog;
            if (w != null) w.cancel(false);
            disconnect();
        }

        private void disconnect() {
            HttpURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (RuntimeException ignored) {
                    // Already closed.
                }
            }
        }
    }
}
