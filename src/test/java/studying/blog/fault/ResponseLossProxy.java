package studying.blog.fault;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only RESP/MySQL packet relay. The server really executes the command;
 * its completed reply is read here, then discarded and that connection closed. */
public final class ResponseLossProxy implements AutoCloseable {
    public enum Protocol { MYSQL, REDIS }
    private final Protocol protocol;
    private final String host;
    private final int port;
    private final ServerSocket listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fault-wire-proxy"); t.setDaemon(true); return t;
    });
    private final AtomicReference<String> target = new AtomicReference<>();
    private volatile boolean blackhole;
    private volatile CountDownLatch dropped = new CountDownLatch(1);
    private final AtomicInteger dropCount = new AtomicInteger();

    public ResponseLossProxy(Protocol protocol, String host, int port) throws IOException {
        this.protocol = protocol; this.host = host; this.port = port;
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            while (!listener.isClosed()) {
                try { connect(listener.accept()); }
                catch (IOException e) { if (!listener.isClosed()) throw new UncheckedIOException(e); }
            }
        });
    }
    public int port() { return listener.getLocalPort(); }
    public void loseNextCommitReply() { arm("COMMIT"); }
    public void loseNextLuaReply(String script) throws Exception {
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest(script.getBytes(StandardCharsets.UTF_8)));
        arm(sha);
    }
    public void blackholeNextLuaReply(String script) throws Exception {
        loseNextLuaReply(script);
        blackhole = true;
    }
    private void arm(String command) {
        blackhole = false;
        dropped = new CountDownLatch(1);
        if (!target.compareAndSet(null, command)) throw new IllegalStateException("A fault is already armed");
    }
    public boolean awaitDrop(Duration duration) throws InterruptedException {
        return dropped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    }
    public int dropCount() { return dropCount.get(); }
    public void disarm() { target.set(null); }

    private void connect(Socket client) throws IOException {
        Socket upstream = new Socket(host, port);
        sockets.add(client); sockets.add(upstream);
        AtomicBoolean loseReply = new AtomicBoolean();
        threads.submit(() -> relay(client, upstream, true, loseReply, client, upstream));
        threads.submit(() -> relay(upstream, client, false, loseReply, client, upstream));
    }
    private void relay(Socket from, Socket to, boolean request, AtomicBoolean loseReply, Socket client, Socket upstream) {
        try {
            InputStream in = new BufferedInputStream(from.getInputStream());
            OutputStream out = to.getOutputStream();
            while (true) {
                Frame frame = protocol == Protocol.MYSQL ? mysql(in) : resp(in);
                if (frame == null) return;
                if (request && matches(frame)) loseReply.set(true);
                if (!request && loseReply.compareAndSet(true, false)) {
                    // NOSCRIPT is not a completed Lua mutation. Allow EVAL fallback instead.
                    String text = new String(frame.bytes, StandardCharsets.UTF_8);
                    if (protocol == Protocol.REDIS && text.startsWith("-NOSCRIPT")) {
                        target.set(frameTarget.get());
                    } else {
                        dropCount.incrementAndGet(); dropped.countDown();
                        // Keep TCP open without replies so the driver observes a real timeout,
                        // instead of transparently replaying on reconnect. resetConnection releases it.
                        if (blackhole) {
                            while (!client.isClosed()) {
                                try { Thread.sleep(10); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                            }
                        }
                        return;
                    }
                }
                out.write(frame.bytes); out.flush();
            }
        } catch (IOException ignored) {
            // Disconnects are the fault under test; application state assertions determine success.
        } finally {
            close(client); close(upstream); sockets.remove(client); sockets.remove(upstream);
        }
    }
    // Matched SHA is retained only for NOSCRIPT fallback. Tests arm one fault at a time.
    private final AtomicReference<String> frameTarget = new AtomicReference<>();
    private boolean matches(Frame f) {
        String wanted = target.get();
        if (wanted == null) return false;
        boolean match;
        if (protocol == Protocol.MYSQL) {
            match = f.bytes.length > 5 && f.bytes[4] == 3
                    && new String(f.bytes, 5, f.bytes.length - 5, StandardCharsets.UTF_8).trim().equalsIgnoreCase(wanted);
        } else {
            List<String> args = f.args;
            match = args.size() >= 2 && ((args.get(0).equalsIgnoreCase("EVALSHA") && args.get(1).equals(wanted))
                    || (args.get(0).equalsIgnoreCase("EVAL") && sha(args.get(1)).equals(wanted)));
        }
        if (match && target.compareAndSet(wanted, null)) { frameTarget.set(wanted); return true; }
        return false;
    }
    private static String sha(String script) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest(script.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record Frame(byte[] bytes, List<String> args) {}
    private static Frame mysql(InputStream in) throws IOException {
        int first = in.read(); if (first < 0) return null;
        byte[] rest = read(in, 3);
        int size = first | ((rest[0] & 255) << 8) | ((rest[1] & 255) << 16);
        ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(first); b.write(rest); b.write(read(in, size));
        return new Frame(b.toByteArray(), List.of());
    }
    private static Frame resp(InputStream in) throws IOException {
        int first = in.read(); if (first < 0) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(); readResp(in, first, bytes, args);
        return new Frame(bytes.toByteArray(), args);
    }
    private static void readResp(InputStream in, int type, ByteArrayOutputStream out, List<String> args) throws IOException {
        out.write(type);
        String line = line(in, out);
        switch (type) {
            case '$', '!', '=' -> {
                int size = Integer.parseInt(line);
                if (size >= 0) {
                    byte[] data = read(in, size); out.write(data); out.write(read(in, 2));
                    args.add(new String(data, StandardCharsets.UTF_8));
                }
            }
            case '*', '~', '>', '%', '|' -> {
                int count = Integer.parseInt(line);
                if (type == '%' || type == '|') count *= 2;
                for (int i = 0; i < count; i++) {
                    int next = in.read(); if (next < 0) throw new EOFException();
                    readResp(in, next, out, args);
                }
            }
            default -> { } // simple string/error, integer, null, boolean, double, big number
        }
    }
    private static String line(InputStream in, ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int c = in.read(); if (c < 0) throw new EOFException(); out.write(c);
            if (prev == '\r' && c == '\n') break;
            if (prev >= 0) text.write(prev); prev = c;
        }
        return text.toString(StandardCharsets.UTF_8);
    }
    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] data = in.readNBytes(n); if (data.length != n) throw new EOFException(); return data;
    }
    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }
    @Override public void close() throws IOException {
        listener.close(); sockets.forEach(ResponseLossProxy::close); threads.shutdownNow();
    }
}
