package client.auth;

import common.auth.AuthWire;
import common.chat.*;
import client.network.NetworkMonitor;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.ssl.SSLSocket;

/** Duc: a cancellable background reader for server-pushed TLS chat frames. */
public final class ChatConnection implements AutoCloseable {
    @FunctionalInterface interface Connector { SSLSocket open() throws IOException; }
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SSLSocket socket;
    private final Thread reader;

    ChatConnection(Connector connector, ChatRequest request, Consumer<ChatResponse> response, Consumer<Throwable> failure,
            NetworkMonitor monitor) {
        reader = Thread.ofPlatform().daemon().name("mini-meeting-chat").unstarted(() -> {
            try (SSLSocket connection = connector.open()) {
                socket = connection;
                if (closed.get()) return;
                var output = connection.getOutputStream(); var inbound = connection.getInputStream();
                if (monitor != null) { output = monitor.output(output); inbound = monitor.input(inbound); }
                AuthWire.write(new DataOutputStream(output), request);
                DataInputStream input = new DataInputStream(inbound);
                while (!closed.get()) {
                    ChatResponse event = AuthWire.read(input, ChatResponse.class);
                    AuthClient.verifyChat(event, request.roomId());
                    if (!closed.get()) response.accept(event);
                }
            } catch (Exception error) {
                if (!closed.get()) failure.accept(error);
            }
        });
        reader.start();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        SSLSocket connection = socket;
        if (connection != null) try { connection.close(); } catch (IOException ignored) { }
        reader.interrupt();
    }
}
