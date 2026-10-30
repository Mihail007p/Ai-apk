package ai.arena.qwenchat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Локальная библиотека одиночных GGUF и локальный сервер llama.cpp. */
public final class ModelEngine implements LocalServer.Backend {
    public interface Listener {
        void onError(String message);
        void onOpenModels();
    }
    public interface InputOpener { InputStream open() throws Exception; }
    private interface Task { File prepare() throws Exception; }

    private final File modelsDir, marker, libraryDir, logFile;
    private final String executableName;
    private final int threads;
    private final Listener listener;
    private final AtomicBoolean generating = new AtomicBoolean(false);
    private volatile String state = "waiting";
    private volatile String detail = "Выбери или скачай модель GGUF";
    private volatile String modelName = "";
    private volatile int progress = -1;
    private volatile boolean cancelled, disposed, cancelling;
    private volatile HttpURLConnection downloadConnection;
    private volatile InputStream activeInput;
    private volatile Process process;
    private volatile File activeModel;
    private volatile String url = "", token = "";
    private Thread worker;

    public ModelEngine(File filesDir, File cacheDir, File libraryDir, String executableName, int threads, Listener listener) throws IOException {
        this.modelsDir = new File(filesDir, "models");
        if (!modelsDir.isDirectory() && !modelsDir.mkdirs()) throw new IOException("Не удалось создать папку моделей.");
        this.marker = new File(filesDir, "selected-model.txt");
        this.logFile = new File(cacheDir, "model-engine.log");
        this.libraryDir = libraryDir;
        this.executableName = executableName;
        this.threads = Math.max(1, Math.min(4, threads));
        this.listener = listener;
    }

    public List<File> listModels() {
        File[] files = modelsDir.listFiles();
        ArrayList<File> result = new ArrayList<File>();
        if (files != null) for (File f : files)
            if (f.isFile() && !f.getName().startsWith(".") && f.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")) result.add(f);
        java.util.Collections.sort(result, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        return result;
    }

    public synchronized boolean isBusy() { return cancelling || (worker != null && worker.isAlive()); }
    public File getActiveModel() { return activeModel; }
    public String getState() { return state; }

    public File savedModel() {
        try (InputStream in = new FileInputStream(marker)) {
            String name = new String(readLimited(in, 512), StandardCharsets.UTF_8).trim();
            if (name.isEmpty() || !new File(name).getName().equals(name)) return null;
            File model = new File(modelsDir, name);
            return model.isFile() ? model : null;
        } catch (Exception ignored) { return null; }
    }

    private void saveSelection(File model) throws IOException {
        File tmp = new File(marker.getParentFile(), "selected-model.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(model.getName().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(marker)) throw new IOException("Не удалось сохранить выбор модели.");
    }

    private boolean isLibraryFile(File model) {
        try { return model != null && model.isFile() && model.getCanonicalFile().getParentFile().equals(modelsDir.getCanonicalFile()); }
        catch (IOException ignored) { return false; }
    }

    public boolean startModel(final File model) {
        if (!isLibraryFile(model)) return false;
        return submit("Запускаю модель на телефоне…", new Task() {
            @Override public File prepare() { return model; }
        });
    }

    public boolean importModel(final InputOpener opener, final String displayName, final long expectedBytes) {
        return submit("Открываю выбранный файл…", new Task() {
            @Override public File prepare() throws Exception {
                InputStream input = opener.open();
                if (input == null) throw new IOException("Не получилось открыть выбранный файл.");
                return copyToLibrary(input, displayName, expectedBytes, null, "Копирую модель");
            }
        });
    }

    public boolean downloadModel(final String address, final String displayName, final long expectedBytes, final String sha256) {
        return submit("Подключаюсь к источнику модели…", new Task() {
            @Override public File prepare() throws Exception {
                // Возобновляем только когда известны и размер, и хеш полного файла:
                // иначе изменение файла на сервере может незаметно смешать две версии.
                if (expectedBytes > 0 && sha256 != null && sha256.matches("(?i)[a-f0-9]{64}"))
                    return downloadResumable(address, displayName, expectedBytes, sha256.toLowerCase(Locale.ROOT));
                HttpURLConnection connection = openDownload(address, 0);
                try {
                    int code = connection.getResponseCode();
                    if (code == 401 || code == 403) throw new IOException("Источник отказал в доступе. Возможно, модель требует входа.");
                    if (code != 200) throw new IOException("Источник модели ответил: HTTP " + code + ".");
                    long length = connection.getContentLengthLong();
                    if (expectedBytes > 0 && length > 0 && expectedBytes != length)
                        throw new IOException("Размер файла не совпадает с данными источника.");
                    return copyToLibrary(connection.getInputStream(), displayName, expectedBytes > 0 ? expectedBytes : length, sha256, "Скачиваю модель");
                } finally {
                    connection.disconnect();downloadConnection = null;
                }
            }
        });
    }

    private synchronized boolean submit(final String initial, final Task operation) {
        if (disposed || cancelling || (worker != null && worker.isAlive())) return false;
        cancelled = false;
        setProgress(initial, -1);
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    stopProcess();
                    if (cancelled || disposed) return;
                    File model = operation.prepare();
                    if (cancelled || disposed) return;
                    launchModel(model);
                } catch (Exception error) {
                    if (cancelled || disposed) return;
                    stopProcess();
                    state = "error";progress = -1;detail = readableError(error);
                    System.err.println("Model operation failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                    if (listener != null) listener.onError(detail);
                } finally {
                    activeInput = null;
                    HttpURLConnection pending = downloadConnection;
                    if (pending != null) { pending.disconnect();downloadConnection = null; }
                    if (cancelled && !disposed) {
                        state = "waiting";progress = -1;detail = "Операция отменена. Выбери модель.";
                    }
                }
            }
        }, "model-operation");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        if (message != null && !message.isEmpty() && message.length() < 240 &&
            (message.startsWith("Не ") || message.startsWith("Источник") || message.startsWith("Файл") ||
             message.startsWith("Размер") || message.startsWith("Недостаточно") || message.startsWith("Ссылка") ||
             message.startsWith("Модель") || message.startsWith("Поддерживаются") || message.startsWith("Загрузка"))) return message;
        return "Не получилось добавить или запустить модель. Проверь интернет и свободное место или выбери другой файл GGUF.";
    }

    private void setProgress(String text, int percent) { detail = text;progress = percent;state = "loading"; }

    private File downloadResumable(String address, String suggestedName, long expected, String checksum) throws Exception {
        String name = safeName(suggestedName);
        if (name.matches("(?i).*-[0-9]{5}-of-[0-9]{5}\\.gguf"))
            throw new IOException("Поддерживаются модели одним файлом GGUF, а не разбитые на части.");
        File partial = new File(modelsDir, ".download-" + checksum + ".part");
        long existing = partial.isFile() ? partial.length() : 0;
        if (existing > expected || (existing > 0 && existing < 24)) { partial.delete();existing = 0; }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (existing > 0) {
            try {
                validateFile(partial);
                setProgress("Проверяю ранее скачанную часть…", (int)Math.min(99L, existing * 100L / expected));
                try (InputStream old = new BufferedInputStream(new FileInputStream(partial))) {
                    byte[] block = new byte[128 * 1024];int n;
                    while ((n = old.read(block)) != -1) {
                        if (cancelled || disposed) throw new InterruptedIOException("cancelled");
                        digest.update(block, 0, n);
                    }
                }
            } catch (InterruptedIOException cancelledError) { throw cancelledError; }
            catch (IOException invalid) { partial.delete();existing = 0;digest = MessageDigest.getInstance("SHA-256"); }
        }
        long free = modelsDir.getUsableSpace(), reserve = 150L * 1024 * 1024;
        if (free > 0 && (free < reserve || expected - existing > free - reserve))
            throw new IOException("Недостаточно свободного места. Освободи место по размеру модели и ещё около 150 МБ.");
        if (existing < expected) {
            HttpURLConnection connection = openDownload(address, existing);
            try {
                int code = connection.getResponseCode();
                if (existing > 0 && (code != 206 || !validRange(connection.getHeaderField("Content-Range"), existing, expected))) {
                    // Источник не поддерживает корректную докачку — начинаем сначала.
                    connection.disconnect();existing = 0;digest = MessageDigest.getInstance("SHA-256");
                    connection = openDownload(address, 0);code = connection.getResponseCode();
                }
                if (code == 401 || code == 403) throw new IOException("Источник отказал в доступе. Возможно, модель требует входа.");
                if (code != 200 && !(code == 206 && validRange(connection.getHeaderField("Content-Range"), existing, expected)))
                    throw new IOException("Источник модели ответил: HTTP " + code + ".");
                long length = connection.getContentLengthLong();
                if (length > 0 && length != expected - existing) throw new IOException("Размер файла не совпадает с данными источника.");
                activeInput = connection.getInputStream();
                try (InputStream in = new BufferedInputStream(activeInput);RandomAccessFile out = new RandomAccessFile(partial, "rw")) {
                    out.setLength(existing);out.seek(existing);
                    long done = existing, last = 0;
                    if (existing == 0) {
                        byte[] header = new byte[24];int pos = 0;
                        while (pos < header.length) {
                            if (cancelled || disposed) throw new InterruptedIOException("cancelled");
                            int n = in.read(header, pos, header.length - pos);
                            if (n < 0) throw new IOException("Файл слишком короткий или это не модель GGUF.");pos += n;
                        }
                        try { validateHeader(header); }
                        catch (IOException invalid) { partial.delete();throw invalid; }
                        out.write(header);digest.update(header);done = header.length;
                    }
                    byte[] block = new byte[128 * 1024];int n;
                    while ((n = in.read(block)) != -1) {
                        if (cancelled || disposed) throw new InterruptedIOException("cancelled");
                        out.write(block, 0, n);digest.update(block, 0, n);done += n;
                        if (done > expected) { partial.delete();throw new IOException("Размер файла больше заявленного источником."); }
                        long now = System.currentTimeMillis();
                        if (now - last > 300) {
                            int percent = (int)Math.min(99L, done * 100L / expected);
                            setProgress("Скачиваю модель: " + percent + "%", percent);last = now;
                        }
                    }
                    out.getFD().sync();
                } finally { activeInput = null; }
            } finally { connection.disconnect();downloadConnection = null; }
        }
        if (cancelled || disposed) throw new InterruptedIOException("cancelled");
        if (partial.length() < expected) throw new IOException("Загрузка прервалась. Выбери ту же модель ещё раз, чтобы продолжить.");
        setProgress("Проверяю SHA-256 модели…", 100);
        if (partial.length() != expected || !hex(digest.digest()).equalsIgnoreCase(checksum)) {
            partial.delete();throw new IOException("Файл не прошёл проверку SHA-256. Попробуй скачать ещё раз.");
        }
        File destination = uniqueFile(name);
        if (!partial.renameTo(destination)) throw new IOException("Не удалось сохранить модель на телефоне.");
        return destination;
    }

    private static boolean validRange(String value, long offset, long total) {
        if (value == null || !value.startsWith("bytes " + offset + "-")) return false;
        int slash = value.lastIndexOf('/');
        if (slash < 0) return false;
        try { return Long.parseLong(value.substring(slash + 1).trim()) == total; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static boolean isPartial(File file) {
        return file.isFile() && file.getName().matches("\\.(download-[a-f0-9]{64}|incoming-[a-f0-9]+)\\.part");
    }

    public long incompleteBytes() {
        long total = 0;File[] files = modelsDir.listFiles();
        if (files != null) for (File f : files) if (isPartial(f)) total += f.length();
        return total;
    }

    public boolean clearIncompleteDownloads() {
        if (isBusy()) return false;
        boolean ok = true;File[] files = modelsDir.listFiles();
        if (files != null) for (File f : files) if (isPartial(f)) ok &= f.delete();
        return ok;
    }

    private File copyToLibrary(InputStream source, String suggestedName, long expected, String checksum, String verb) throws Exception {
        if (expected > 0 && modelsDir.getUsableSpace() > 0 && modelsDir.getUsableSpace() < expected + 150L * 1024 * 1024)
            throw new IOException("Недостаточно свободного места. Освободи место по размеру модели и ещё около 150 МБ.");
        String name = safeName(suggestedName);
        if (name.matches("(?i).*-[0-9]{5}-of-[0-9]{5}\\.gguf"))
            throw new IOException("Поддерживаются модели одним файлом GGUF, а не разбитые на части.");
        File temporary = new File(modelsDir, ".incoming-" + Long.toHexString(new SecureRandom().nextLong()) + ".part");
        boolean finished = false;
        activeInput = source;
        MessageDigest digest = checksum == null || checksum.isEmpty() ? null : MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(source); FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] header = new byte[24];int offset = 0;
            while (offset < header.length) {
                if (cancelled || disposed) throw new InterruptedIOException("cancelled");
                int n = in.read(header, offset, header.length - offset);
                if (n < 0) throw new IOException("Файл слишком короткий или это не модель GGUF.");
                offset += n;
            }
            validateHeader(header);
            out.write(header);if (digest != null) digest.update(header);
            long done = header.length, last = 0;
            byte[] block = new byte[128 * 1024];int count;
            while ((count = in.read(block)) != -1) {
                if (cancelled || disposed) throw new InterruptedIOException("cancelled");
                out.write(block, 0, count);
                if (digest != null) digest.update(block, 0, count);
                done += count;
                if (expected > 0 && done > expected) throw new IOException("Размер файла больше заявленного источником.");
                long now = System.currentTimeMillis();
                if (now - last > 300) {
                    int percent = expected > 0 ? (int)Math.min(99L, done * 100L / expected) : -1;
                    setProgress(verb + (percent >= 0 ? ": " + percent + "%" : ": " + formatBytes(done)), percent);
                    long free = modelsDir.getUsableSpace();
                    if (free > 0 && free < 50L * 1024 * 1024) throw new IOException("Недостаточно свободного места для модели.");
                    last = now;
                }
            }
            if (expected > 0 && done != expected) throw new IOException("Загрузка прервалась: файл получен не полностью. Попробуй ещё раз.");
            if (digest != null && !hex(digest.digest()).equalsIgnoreCase(checksum))
                throw new IOException("Файл не прошёл проверку SHA-256. Попробуй скачать ещё раз.");
            if (cancelled || disposed) throw new InterruptedIOException("cancelled");
            setProgress("Сохраняю модель…", 100);
            out.getFD().sync();
            File destination = uniqueFile(name);
            if (!temporary.renameTo(destination)) throw new IOException("Не удалось сохранить модель на телефоне.");
            finished = true;
            return destination;
        } finally {
            activeInput = null;
            if (!finished) temporary.delete();
        }
    }

    private static void validateHeader(byte[] head) throws IOException {
        if (head.length < 24 || head[0] != 'G' || head[1] != 'G' || head[2] != 'U' || head[3] != 'F')
            throw new IOException("Файл не похож на GGUF. Выбери ссылку именно на файл .gguf, а не на страницу сайта.");
        long version = ((long)head[4] & 255) | (((long)head[5] & 255) << 8) | (((long)head[6] & 255) << 16) | (((long)head[7] & 255) << 24);
        if (version != 2 && version != 3) throw new IOException("Файл GGUF имеет неподдерживаемую версию формата.");
    }

    private static void validateFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[24];int n = 0;
            while (n < header.length) { int count = in.read(header, n, header.length - n);if (count < 0) break;n += count; }
            if (n < header.length) throw new IOException("Файл модели повреждён или слишком короткий.");
            validateHeader(header);
        }
    }

    private static String safeName(String input) {
        if (input == null) input = "model.gguf";
        input = input.replace('\\', '/');
        input = input.substring(input.lastIndexOf('/') + 1).trim();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < input.length() && name.length() < 100; i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ' || c == '(' || c == ')') name.append(c);
            else name.append('_');
        }
        String result = name.toString().trim();
        while (result.startsWith(".")) result = result.substring(1);
        if (result.isEmpty()) result = "model";
        if (!result.toLowerCase(Locale.ROOT).endsWith(".gguf")) result += ".gguf";
        return result;
    }

    private File uniqueFile(String name) {
        File result = new File(modelsDir, name);
        if (!result.exists()) return result;
        String base = name.substring(0, name.length() - 5);
        for (int n = 2; n < 10000; n++) {
            result = new File(modelsDir, base + " (" + n + ").gguf");
            if (!result.exists()) return result;
        }
        return new File(modelsDir, base + "-" + Long.toHexString(System.nanoTime()) + ".gguf");
    }

    public static String formatBytes(long value) {
        if (value >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f ГБ", value / (1024.0 * 1024 * 1024));
        if (value >= 1024L * 1024) return String.format(Locale.ROOT, "%.0f МБ", value / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.0f КБ", value / 1024.0);
    }

    private HttpURLConnection openDownload(String address, long offset) throws IOException {
        URL current;
        try { current = new URL(address); } catch (Exception bad) { throw new IOException("Ссылка на файл не распознана."); }
        for (int attempt = 0; attempt < 8; attempt++) {
            if (cancelled || disposed) throw new InterruptedIOException("cancelled");
            if (!"https".equalsIgnoreCase(current.getProtocol()) || current.getUserInfo() != null)
                throw new IOException("Ссылка должна начинаться с https:// и не содержать логин и пароль.");
            HttpURLConnection connection = (HttpURLConnection)current.openConnection();
            downloadConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30000);connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "AI-Chat-Android/2.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");connection.disconnect();
                if (location == null) throw new IOException("Источник вернул пустое перенаправление.");
                current = new URL(current, location);
            } else return connection;
        }
        throw new IOException("Источник слишком много раз перенаправил загрузку.");
    }

    private static String hex(byte[] digest) {
        char[] chars = "0123456789abcdef".toCharArray(), result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) { int n = digest[i] & 255;result[2*i] = chars[n >>> 4];result[2*i+1] = chars[n & 15]; }
        return new String(result);
    }

    private void launchModel(File model) throws Exception {
        validateFile(model);
        if (cancelled || disposed) return;
        activeModel = model;
        modelName = model.getName();
        setProgress("Запускаю модель на телефоне…", -1);
        File executable = new File(libraryDir, executableName);
        if (!executable.isFile()) throw new IOException("Не удалось найти движок модели.");
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = s.getLocalPort(); }
        byte[] random = new byte[32];new SecureRandom().nextBytes(random);token = hex(random);
        url = "http://127.0.0.1:" + port;
        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--model", model.getAbsolutePath(),
            "--host", "127.0.0.1", "--port", String.valueOf(port), "--ctx-size", "2048", "--threads", String.valueOf(threads),
            "--parallel", "1", "--batch-size", "128", "--ubatch-size", "64", "--n-gpu-layers", "0",
            "--no-webui", "--no-warmup", "--cors-origins", "localhost", "--api-key", token);
        builder.directory(libraryDir);
        builder.environment().put("LD_LIBRARY_PATH", libraryDir.getAbsolutePath() + ":/system/lib64");
        builder.redirectErrorStream(true);builder.redirectOutput(logFile);
        synchronized (this) {
            if (cancelled || disposed) return;
            process = builder.start();
        }
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled || disposed) return;
            Process p = process;
            if (p == null || !p.isAlive()) throw new IOException("Не удалось запустить модель. Возможно, она не поддерживается или не хватает оперативной памяти.");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url + "/health").openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(1500);connection.setReadTimeout(1500);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                if (connection.getResponseCode() == 200) {
                    String data = new String(readLimited(connection.getInputStream(), 4096), StandardCharsets.UTF_8);
                    if (new JSONObject(data).optString("status").equals("ok")) {
                        saveSelection(model);state = "ready";detail = "Готова к общению";progress = -1;return;
                    }
                }
            } catch (Exception ignored) { } finally { if (connection != null) connection.disconnect(); }
            Thread.sleep(500);
        }
        throw new IOException("Модель запускается слишком долго. Попробуй модель меньшего размера.");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream in = input;ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];int n;
            while ((n = in.read(buf)) != -1) { if (out.size() + n > limit) throw new IOException("Response too large");out.write(buf,0,n); }
            return out.toByteArray();
        }
    }

    private static LocalServer.Reply error(int code, String message) {
        try { return new LocalServer.Reply(code, new JSONObject().put("error", message).toString()); }
        catch (Exception ignored) { return new LocalServer.Reply(code, "{\"error\":\"Ошибка.\"}"); }
    }

    @Override public String statusJson() {
        Process p = process;
        if (state.equals("ready") && (p == null || !p.isAlive())) {
            state = "error";detail = "Модель остановилась. Закрой другие приложения или выбери модель поменьше.";progress = -1;
        }
        try { return new JSONObject().put("status", state).put("detail", detail).put("progress", progress).put("model", modelName).toString(); }
        catch (Exception ignored) { return "{\"status\":\"error\",\"detail\":\"Ошибка модели\"}"; }
    }

    @Override public void openModels() { if (listener != null) listener.onOpenModels(); }

    @Override public LocalServer.Reply chat(String body) {
        Process p = process;
        if (!state.equals("ready") || p == null || !p.isAlive()) return error(503, "Сначала выбери и запусти модель.");
        if (!generating.compareAndSet(false, true)) return error(429, "Модель уже отвечает. Подожди немного.");
        HttpURLConnection connection = null;
        try {
            JSONObject request = new JSONObject(body);
            Object input = request.opt("message");
            if (!(input instanceof String)) return error(400, "Напиши сообщение.");
            String message = ((String)input).trim();
            if (message.isEmpty() || message.length() > 2000) return error(400, "Сообщение пустое или слишком длинное.");
            JSONArray messages = new JSONArray();
            JSONArray history = request.optJSONArray("history");
            if (history != null) for (int i = Math.max(0, history.length() - 4); i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);if (item == null) continue;
                String role = item.optString("role", ""), text = item.optString("content", "");
                if ((role.equals("user") || role.equals("assistant")) && !text.isEmpty()) {
                    if (text.length() > 2000) text = text.substring(0, 2000);
                    messages.put(new JSONObject().put("role", role).put("content", text));
                }
            }
            messages.put(new JSONObject().put("role", "user").put("content", message));
            JSONObject payload = new JSONObject().put("messages", messages).put("max_tokens", 256).put("temperature", 0.7).put("stream", false);
            byte[] encoded = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection)new URL(url + "/v1/chat/completions").openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");connection.setConnectTimeout(5000);connection.setReadTimeout(600000);connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(encoded); }
            int code = connection.getResponseCode();
            if (code == 400) return error(400, "Модель не приняла запрос: попробуй короткое сообщение или другую чат-модель GGUF.");
            if (code != 200) return error(502, "Модель не смогла ответить. Попробуй ещё раз или выбери другую.");
            String data = new String(readLimited(connection.getInputStream(), 1024 * 1024), StandardCharsets.UTF_8);
            JSONObject reply = new JSONObject(data).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String answer = reply.optString("content", "");
            if (answer.isEmpty()) answer = reply.optString("reasoning_content", "");
            if (answer.isEmpty()) answer = "Не получилось получить ответ. Попробуй другую чат-модель или спроси иначе.";
            return new LocalServer.Reply(200, new JSONObject().put("answer", answer).toString());
        } catch (Exception ignored) {
            return error(502, "Модель не смогла ответить. Попробуй ещё раз или выбери другую.");
        } finally { if (connection != null) connection.disconnect();generating.set(false); }
    }

    public synchronized void cancelOperation() {
        if (cancelling) return;
        cancelled = true;cancelling = true;
        detail = "Отменяю операцию…";progress = -1;
        Thread t = worker;if (t != null) t.interrupt();
        new Thread(new Runnable() {
            @Override public void run() {
                try { interruptIO();stopProcess(); }
                finally { synchronized (ModelEngine.this) { cancelling = false; } }
            }
        }, "model-cancel").start();
    }

    private void interruptIO() {
        HttpURLConnection connection = downloadConnection;if (connection != null) connection.disconnect();
        InputStream input = activeInput;if (input != null) try { input.close(); } catch (IOException ignored) { }
    }

    private void stopProcess() {
        Process p;
        synchronized (this) { p = process;process = null; }
        if (p != null && p.isAlive()) {
            p.destroy();
            try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt();p.destroyForcibly(); }
        }
    }

    public boolean deleteModel(File model) {
        if (!isLibraryFile(model) || isBusy()) return false;
        if (model.equals(activeModel)) {
            stopProcess();activeModel = null;modelName = "";marker.delete();
            state = "waiting";detail = "Выбери или скачай модель GGUF";progress = -1;
        }
        return model.delete();
    }

    public void shutdown() {
        disposed = true;cancelled = true;
        Thread t = worker;if (t != null) t.interrupt();
        interruptIO();stopProcess();
    }
}
