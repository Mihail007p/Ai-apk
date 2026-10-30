package ai.arena.qwenchat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Интерфейс, выбор файла через системный проводник и управление локальными моделями. */
public final class MainActivity extends Activity {
    private static final int PICK_GGUF = 7301;
    private static final String QWEN_FILE = "Qwen2.5-1.5B-Instruct-uncensored.IQ4_XS.gguf";
    private static final String QWEN_URL = "https://huggingface.co/mradermacher/Qwen2.5-1.5B-Instruct-uncensored-GGUF/resolve/ecee937554b3b66f89e7eb4fc432b8d8445208e7/" + QWEN_FILE;
    private static final String QWEN_HASH = "c906c108eb51acacfb1c3553700e043ea6716e48e9b1f5ae7394b7715b51cce5";
    private WebView web;
    private LocalServer server;
    private ModelEngine engine;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 250, 247));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        try {
            engine = new ModelEngine(getFilesDir(), getCacheDir(), new File(getApplicationInfo().nativeLibraryDir),
                "libqwen_server.so", Math.min(4, Runtime.getRuntime().availableProcessors()), new ModelEngine.Listener() {
                    @Override public void onError(final String message) { runOnUiThread(new Runnable() {
                        @Override public void run() { if (canShow()) showError(message); }
                    }); }
                    @Override public void onOpenModels() { runOnUiThread(new Runnable() {
                        @Override public void run() { if (canShow()) showModelMenu(); }
                    }); }
                });
            // Другой пакет и постоянный порт: новая версия не затрагивает старое приложение и хранит свою историю.
            server = new LocalServer(readAsset("index.html"), engine, 41240);
            final int localPort = server.getPort();
            web = new WebView(this);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == localPort) return false;
                    if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                    }
                    return true;
                }
            });
            setContentView(web);
            web.loadUrl("http://127.0.0.1:" + localPort + "/");
            File saved = engine.savedModel();
            if (saved != null) engine.startModel(saved);
            else showWelcome();
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("Не удалось открыть чат")
                .setMessage("Не получилось запустить приложение. Сделай скриншот и отправь помощнику.\n\n" + error.getClass().getSimpleName())
                .setPositiveButton("Закрыть", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { finish(); }
                }).setCancelable(false).show();
        }
    }

    private boolean canShow() { return !destroyed && !isFinishing(); }
    private void toast(String text) { if (canShow()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name);ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];int n;while ((n = in.read(b)) != -1) out.write(b, 0, n);return out.toByteArray();
        }
    }

    private void showWelcome() {
        new AlertDialog.Builder(this).setTitle("Добавь модель")
            .setMessage("Выбери совместимую текстовую модель GGUF из файлов телефона или скачай по ссылке.\n\n" +
                "Большие модели могут не запуститься из-за нехватки памяти. Начни с модели примерно до 1–1,5 ГБ.")
            .setPositiveButton("Выбрать модель", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { showModelMenu(); }
            })
            .setNegativeButton("Позже", null).show();
    }

    private void showModelMenu() {
        final boolean busy = engine.isBusy();
        ArrayList<String> options = new ArrayList<String>();
        options.add("Выбрать GGUF из файлов телефона");
        options.add("Скачать по ссылке / Hugging Face");
        options.add("Скачать исходную Qwen 1.5B (~1 ГБ)");
        options.add("Мои модели (" + engine.listModels().size() + ")");
        long incomplete = engine.incompleteBytes();
        final int clearIndex;
        if (incomplete > 0 && !busy) { clearIndex = options.size();options.add("Удалить незавершённые загрузки (" + ModelEngine.formatBytes(incomplete) + ")"); }
        else clearIndex = -1;
        final int cancelIndex;
        if (busy) { cancelIndex = options.size();options.add("Отменить текущую операцию"); }
        else cancelIndex = -1;
        new AlertDialog.Builder(this).setTitle("Модели на телефоне")
            .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which == cancelIndex) { engine.cancelOperation();toast("Отменяю операцию.");return; }
                    if (which == clearIndex) { confirmClearIncomplete();return; }
                    if (which == 3) { showMyModels();return; }
                    if (busy) { toast("Сначала дождись завершения операции или отмени её в меню моделей.");return; }
                    if (which == 0) chooseFile();
                    else if (which == 1) showLinkDialog();
                    else if (which == 2) confirmDownload(QWEN_URL, QWEN_FILE, 1026163008L, QWEN_HASH);
                }
            }).setNegativeButton("Закрыть", null).show();
    }

    private void confirmClearIncomplete() {
        new AlertDialog.Builder(this).setTitle("Удалить незавершённые загрузки?")
            .setMessage("Это освободит " + ModelEngine.formatBytes(engine.incompleteBytes()) + ". Готовые модели не удалятся, но прерванные загрузки придётся начинать заново.")
            .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    toast(engine.clearIncompleteDownloads() ? "Незавершённые загрузки удалены." : "Не удалось удалить файлы. Сначала отмени загрузку.");
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showMyModels() {
        final List<File> models = engine.listModels();
        if (models.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Мои модели").setMessage("Пока нет моделей. Добавь файл GGUF или скачай модель по ссылке.")
                .setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showModelMenu(); }
                }).setNegativeButton("Закрыть", null).show();
            return;
        }
        String[] names = new String[models.size()];File active = "ready".equals(engine.getState()) ? engine.getActiveModel() : null;
        for (int i = 0; i < models.size(); i++) {
            File f = models.get(i);
            names[i] = (f.equals(active) ? "● " : "") + f.getName() + "\n" + ModelEngine.formatBytes(f.length());
        }
        new AlertDialog.Builder(this).setTitle("Мои модели")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { showModelActions(models.get(which)); }
            }).setNegativeButton("Назад", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { showModelMenu(); }
            }).show();
    }

    private void showModelActions(final File model) {
        new AlertDialog.Builder(this).setTitle(model.getName())
            .setItems(new String[]{"Запустить эту модель", "Удалить модель из приложения"}, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (engine.isBusy()) { toast("Сначала дождись завершения текущей операции.");return; }
                    if (which == 0) {
                        if (!engine.startModel(model)) toast("Не получилось запустить модель. Попробуй ещё раз.");
                    } else confirmDelete(model);
                }
            }).setNegativeButton("Назад", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { showMyModels(); }
            }).show();
    }

    private void confirmDelete(final File model) {
        new AlertDialog.Builder(this).setTitle("Удалить модель?")
            .setMessage("Удалится копия из этого приложения: " + model.getName() + ".\n\nИсходный файл в «Загрузках», если он есть, и история чатов не удалятся.")
            .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    new Thread(new Runnable() { @Override public void run() {
                        final boolean ok = engine.deleteModel(model);
                        runOnUiThread(new Runnable() { @Override public void run() { toast(ok ? "Модель удалена." : "Не получилось удалить модель."); } });
                    } }, "delete-model").start();
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_GGUF); }
        catch (Exception error) { toast("Не удалось открыть файлы на телефоне."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GGUF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();String name = "model.gguf";long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) { }
        final String selectedName = name;final long selectedSize = size;
        String text = "Файл: " + name + (size > 0 ? "\nРазмер: " + ModelEngine.formatBytes(size) : "") +
            "\n\nФайл будет скопирован в приложение, поэтому нужно ещё столько же свободного места. Исходный файл не удалится.";
        new AlertDialog.Builder(this).setTitle("Добавить модель?").setMessage(text)
            .setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    boolean started = engine.importModel(new ModelEngine.InputOpener() {
                        @Override public InputStream open() throws Exception { return getContentResolver().openInputStream(uri); }
                    }, selectedName, selectedSize);
                    if (!started) toast("Сначала дождись завершения текущей операции.");
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showLinkDialog() {
        LinearLayout layout = new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(24);layout.setPadding(horizontal, dp(8), horizontal, 0);
        TextView explanation = new TextView(this);
        explanation.setText("Вставь ссылку на файл .gguf или на страницу публичного репозитория Hugging Face.");
        explanation.setTextSize(14);layout.addView(explanation);
        final EditText input = new EditText(this);input.setSingleLine(true);input.setHint("https://huggingface.co/…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(input);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Скачать модель").setView(layout)
            .setPositiveButton("Продолжить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String value = input.getText().toString().trim();
                        try {
                            String normalized = RepoClient.fileUrl(value);
                            String repo = RepoClient.repositoryId(value);
                            dialog.dismiss();
                            if (repo != null) fetchRepository(repo);
                            else confirmDownload(normalized, RepoClient.nameFromUrl(normalized), -1, "");
                        } catch (Exception error) { input.setError("Нужна ссылка, начинающаяся с https://"); }
                    }
                });
            }
        });
        dialog.show();
    }

    private void fetchRepository(final String repo) {
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        final AlertDialog search = new AlertDialog.Builder(this).setTitle("Ищу файлы GGUF")
            .setMessage("Загружаю список из " + repo + "…")
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { abandoned.set(true); }
            }).create();
        search.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) { abandoned.set(true); }
        });
        search.show();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<RepoClient.Entry> entries = RepoClient.list(repo);
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (!canShow() || abandoned.get()) return;search.dismiss();showRepositoryEntries(repo, entries);
                    } });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (!canShow() || abandoned.get()) return;search.dismiss();
                        String msg = error.getMessage();
                        if (msg == null || msg.length() > 240 || msg.contains("https://")) msg = "Не удалось получить список. Проверь интернет и ссылку.";
                        showError(msg);
                    } });
                }
            }
        }, "model-repository");t.setDaemon(true);t.start();
    }

    private void showRepositoryEntries(String repo, final List<RepoClient.Entry> entries) {
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("GGUF не найден")
                .setMessage("В этом репозитории нет доступных одиночных файлов .gguf. Попробуй репозиторий с GGUF-версией чат-модели.")
                .setPositiveButton("ОК", null).show();return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            RepoClient.Entry e = entries.get(i);
            labels[i] = e.filename + "\n" + (e.size > 0 ? ModelEngine.formatBytes(e.size) : "размер неизвестен") +
                (e.size > 2L * 1024 * 1024 * 1024 ? " · может не хватить памяти" : "");
        }
        new AlertDialog.Builder(this).setTitle("Файлы модели: " + repo)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    RepoClient.Entry e = entries.get(which);confirmDownload(e.url, e.filename, e.size, e.sha256);
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void confirmDownload(final String url, final String name, final long size, final String hash) {
        String message = "Модель: " + name + "\nРазмер: " + (size > 0 ? ModelEngine.formatBytes(size) : "неизвестен") +
            "\n\nНужны интернет и свободное место не меньше размера модели. Лучше использовать Wi-Fi и оставить приложение открытым." +
            "\n\nСкачивай файлы только из источников, которым доверяешь. Не каждая GGUF-модель поддерживается телефоном." +
            (hash == null || hash.isEmpty() ? "\n\nКонтрольная сумма не предоставлена; при обрыве загрузку придётся начать заново." :
                "\n\nФайл будет проверен по SHA-256. При обрыве выбери ту же модель снова, чтобы попытаться продолжить загрузку.");
        new AlertDialog.Builder(this).setTitle("Скачать модель?").setMessage(message)
            .setPositiveButton("Скачать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (!engine.downloadModel(url, name, size, hash)) toast("Сначала дождись завершения текущей операции.");
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("Не получилось использовать модель")
            .setMessage(message + "\n\nЕсли ошибка повторится, сделай скриншот и отправь помощнику.")
            .setPositiveButton("Модели", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { showModelMenu(); }
            }).setNegativeButton("Закрыть", null).show();
    }

    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override public void onDestroy() {
        destroyed = true;
        if (server != null) server.close();
        if (web != null) { web.stopLoading();web.destroy();web = null; }
        final ModelEngine old = engine;
        if (old != null) new Thread(new Runnable() { @Override public void run() { old.shutdown(); } }, "model-stop").start();
        super.onDestroy();
    }
}
