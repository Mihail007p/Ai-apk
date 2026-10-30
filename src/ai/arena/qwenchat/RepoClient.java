package ai.arena.qwenchat;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Список файлов из публичного репозитория Hugging Face; без входа и токенов. */
public final class RepoClient {
    public static final class Entry {
        public final String filename, url, sha256;
        public final long size;
        Entry(String filename, String url, long size, String sha256) {
            this.filename = filename;this.url = url;this.size = size;this.sha256 = sha256;
        }
    }

    public static String repositoryId(String text) {
        try {
            URL url = new URL(text.trim());
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null ||
                !(url.getHost().equalsIgnoreCase("huggingface.co") || url.getHost().equalsIgnoreCase("www.huggingface.co"))) return null;
            String[] raw = url.getPath().split("/");
            ArrayList<String> parts = new ArrayList<String>();
            for (String p : raw) if (!p.isEmpty()) parts.add(URLDecoder.decode(p, "UTF-8"));
            if (parts.size() < 2 || (parts.size() > 2 && !parts.get(2).equals("tree"))) return null;
            String owner = parts.get(0), repo = parts.get(1);
            if (!owner.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}") || !repo.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) return null;
            return owner + "/" + repo;
        } catch (Exception ignored) { return null; }
    }

    public static String fileUrl(String text) throws IOException {
        String address = text == null ? "" : text.trim();
        final URL parsed;
        try { parsed = new URL(address); } catch (Exception bad) { throw new IOException("Ссылка не распознана. Вставь ссылку https:// на файл GGUF или репозиторий Hugging Face."); }
        if (!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getUserInfo() != null || parsed.getHost().isEmpty())
            throw new IOException("Ссылка должна начинаться с https:// и не содержать логин и пароль.");
        if (parsed.getHost().equalsIgnoreCase("huggingface.co") || parsed.getHost().equalsIgnoreCase("www.huggingface.co"))
            address = address.replaceFirst("/blob/", "/resolve/");
        return address;
    }

    public static String nameFromUrl(String text) {
        try {
            String path = new URL(text).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return URLDecoder.decode(last, "UTF-8");
        } catch (Exception ignored) { return "model.gguf"; }
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
    private static String encodePath(String s) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Некорректное имя файла в репозитории.");
            if (out.length() > 0) out.append('/');out.append(enc(part));
        }
        return out.toString();
    }

    public static List<Entry> list(String repo) throws Exception {
        String[] parts = repo.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}") || !parts[1].matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))
            throw new IOException("Ссылка на репозиторий не распознана.");
        String api = "https://huggingface.co/api/models/" + enc(parts[0]) + "/" + enc(parts[1]) + "?blobs=true";
        HttpURLConnection connection = (HttpURLConnection)new URL(api).openConnection();
        connection.setInstanceFollowRedirects(true);connection.setConnectTimeout(30000);connection.setReadTimeout(45000);
        connection.setRequestProperty("User-Agent", "AI-Chat-Android/2.0");
        try {
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("Репозиторий требует входа или разрешения. Выбери публичную модель.");
            if (code != 200) throw new IOException("Не удалось получить список моделей: HTTP " + code + ".");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] block = new byte[16384];int n;
                while ((n = in.read(block)) != -1) {
                    if (data.size() + n > 4 * 1024 * 1024) throw new IOException("Список файлов слишком большой.");
                    data.write(block, 0, n);
                }
            }
            JSONObject root = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
            String commit = root.optString("sha", "");
            if (!commit.matches("[a-fA-F0-9]{40}")) throw new IOException("Не удалось определить версию репозитория.");
            JSONArray files = root.optJSONArray("siblings");
            List<Entry> result = new ArrayList<Entry>();
            if (files != null) for (int i = 0; i < files.length(); i++) {
                JSONObject object = files.optJSONObject(i);if (object == null) continue;
                String name = object.optString("rfilename", ""), lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".gguf") || lower.contains("mmproj") || lower.matches(".*-[0-9]{5}-of-[0-9]{5}\\.gguf")) continue;
                if (name.length() > 400 || name.startsWith("/") || name.contains("../")) continue;
                JSONObject lfs = object.optJSONObject("lfs");
                String hash = lfs == null ? "" : lfs.optString("sha256", "");
                if (!hash.matches("[a-fA-F0-9]{64}")) hash = "";
                long size = object.optLong("size", -1);
                String resolved = "https://huggingface.co/" + enc(parts[0]) + "/" + enc(parts[1]) + "/resolve/" + commit + "/" + encodePath(name);
                result.add(new Entry(name, resolved, size, hash));
            }
            Collections.sort(result, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    if (a.size > 0 && b.size > 0 && a.size != b.size) return a.size < b.size ? -1 : 1;
                    if (a.size > 0 && b.size <= 0) return -1;
                    if (a.size <= 0 && b.size > 0) return 1;
                    return a.filename.compareToIgnoreCase(b.filename);
                }
            });
            return result;
        } finally { connection.disconnect(); }
    }
}
