package com.termux.app;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local session controls for processes running under the TermuxPP Android UID. */
final class SessionCommandServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 4096;
    /** How long a tmux helper's wait for a chosen window is held open, well inside the helper's own timeout. */
    static final long WAIT_MS = 750;
    private final LocalServerSocket listener;
    private final ExecutorService connections = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "TermuxPP session command");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    SessionCommandServer(TermuxService service) throws IOException {
        listener = new LocalServerSocket("dev.pschmitt.termux.sessions");
        Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            while (!closed) {
                LocalSocket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    break;
                }
                // Each connection gets its own thread, so a helper waiting for a tmux window choice does
                // not hold up other commands.
                try {
                    connections.execute(() -> serve(service, main, client));
                } catch (RuntimeException e) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // Nothing more to do with a connection that could not be served.
                    }
                }
            }
        }, "TermuxPP session commands");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(TermuxService service, Handler main, LocalSocket client) {
        try (LocalSocket connection = client) {
            if (connection.getPeerCredentials().getUid() != Process.myUid()) return;
            connection.setSoTimeout(2000);
            JSONObject response = new JSONObject();
            try {
                JSONArray arguments = new JSONArray(readRequest(connection));
                if ("tmux-wait".equals(arguments.optString(0))) {
                    // Waits on this thread, never on the main thread.
                    response.put("result", service.awaitTmuxSelection(arguments));
                    connection.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                    return;
                }
                FutureTask<Object> command = new FutureTask<>(() -> {
                    if (closed) throw new IllegalStateException("TermuxPP service is stopping");
                    return service.runSessionCommand(arguments);
                });
                if (!main.post(command)) throw new IllegalStateException("TermuxPP service is stopping");
                try {
                    response.put("result", command.get(2, TimeUnit.SECONDS));
                } catch (TimeoutException | InterruptedException e) {
                    command.cancel(false);
                    throw new IllegalStateException("TermuxPP did not respond in time");
                }
            } catch (Exception e) {
                Throwable error = e instanceof ExecutionException ? e.getCause() : e;
                response.put("error", error.getMessage() == null ? "Session command failed" : error.getMessage());
            }
            connection.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // A disconnected or unauthorized client does not stop the listener.
        }
    }

    private static String readRequest(LocalSocket connection) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = new BufferedInputStream(connection.getInputStream());
        for (int i = 0; i < MAX_REQUEST_BYTES; i++) {
            int value = input.read();
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (value == -1) break;
            bytes.write(value);
        }
        throw new IOException("Incomplete or oversized session command");
    }

    @Override
    public void close() throws IOException {
        closed = true;
        connections.shutdownNow();
        listener.close();
    }
}
