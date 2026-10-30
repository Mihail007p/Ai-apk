package ai.arena.qwenchat;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Минимальный локальный HTTP-мост; доступен только на 127.0.0.1. */
public final class LocalServer implements Closeable {
    public interface Backend {
        String statusJson();
        Reply chat(String body);
        void openModels();
    }
    public static final class Reply {
        public final int code;
        public final String json;
        public Reply(int code, String json) { this.code = code; this.json = json; }
    }

    private final byte[] index;
    private final Backend backend;
    private final ServerSocket listener;
    private final ExecutorService workers;
    private volatile boolean closed;
    private final Thread acceptThread;

    public LocalServer(byte[] html, Backend backend) throws IOException { this(html, backend, 0); }

    public LocalServer(byte[] html, Backend backend, int port) throws IOException {
        this.index = html.clone();
        this.backend = backend;
        this.listener = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        this.workers = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override public Thread newThread(Runnable job) {
                Thread thread = new Thread(job, "qwen-http-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "qwen-http-listener");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() { return listener.getLocalPort(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket socket = listener.accept();
                workers.execute(new Runnable() {
                    @Override public void run() { serve(socket); }
                });
            } catch (IOException error) {
                if (!closed) System.err.println("Local server: " + error.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                if (!closed) System.err.println("Local server: worker unavailable");
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') buffer.write(value);
            if (buffer.size() > 8192) throw new IOException("Header too long");
        }
        if (value == -1 && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ", 3);
            if (parts.length != 3) { json(out, 400, "{\"error\":\"Неверный запрос.\"}"); return; }
            String method = parts[0];
            String path = parts[1].split("\\?", 2)[0];
            int length = 0;
            String host = "", contentType = "";
            int headerCount = 0;
            for (;;) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                if (++headerCount > 100) throw new IOException("Too many headers");
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("host")) host = value;
                else if (name.equals("content-type")) contentType = value.toLowerCase(Locale.ROOT);
                else if (name.equals("content-length")) {
                    try { length = Integer.parseInt(value); }
                    catch (NumberFormatException badLength) { length = -1; }
                }
            }
            // Защита от DNS-rebinding: сайт открывается только по loopback-адресу.
            if (!host.equals("127.0.0.1:" + getPort())) {
                json(out, 403, "{\"error\":\"Доступ только с этого телефона.\"}");
                return;
            }
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                write(out, 200, "text/html; charset=utf-8", index);
            } else if (method.equals("GET") && path.equals("/api/status")) {
                json(out, 200, backend.statusJson());
            } else if (method.equals("GET") && path.equals("/favicon.ico")) {
                write(out, 204, "image/x-icon", new byte[0]);
            } else if (method.equals("POST") && path.equals("/api/open-models")) {
                if (!contentType.startsWith("application/json") || length <= 0 || length > 200) {
                    json(out, 400, "{\"error\":\"Неверный запрос.\"}"); return;
                }
                for (int i = 0; i < length; i++) if (in.read() < 0) throw new IOException("Incomplete body");
                backend.openModels();
                json(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/chat")) {
                if (!contentType.startsWith("application/json")) {
                    json(out, 415, "{\"error\":\"Неверный формат запроса.\"}"); return;
                }
                if (length <= 0 || length > 40000) {
                    json(out, 400, "{\"error\":\"Сообщение пустое или слишком длинное.\"}"); return;
                }
                byte[] bytes = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int count = in.read(bytes, offset, length - offset);
                    if (count < 0) throw new IOException("Incomplete body");
                    offset += count;
                }
                Reply reply = backend.chat(new String(bytes, StandardCharsets.UTF_8));
                json(out, reply.code, reply.json);
            } else {
                json(out, 404, "{\"error\":\"Страница не найдена.\"}");
            }
        } catch (IOException ignored) {
            // Например, пользователь закрыл экран до завершения ответа.
        } catch (RuntimeException error) {
            System.err.println("Local server request failed: " + error.getClass().getSimpleName());
        }
    }

    private static void json(OutputStream out, int code, String value) throws IOException {
        write(out, code, "application/json; charset=utf-8", value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" :
            code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 415 ? "Unsupported Media Type" :
            code == 429 ? "Too Many Requests" : code == 503 ? "Service Unavailable" : "Bad Gateway";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'\r\n" +
            "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
