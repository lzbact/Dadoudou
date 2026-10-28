package com.example.dyparse;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private EditText input;
    private TextView info;
    private Button download;
    private DyParser.Result result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int p = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 3, p, p);

        input = new EditText(this);
        input.setHint("粘贴抖音分享链接（或整段分享文字）");
        input.setMinLines(3);
        root.addView(input);

        final Button parse = new Button(this);
        parse.setText("解析");
        root.addView(parse);

        info = new TextView(this);
        info.setPadding(0, p, 0, p);
        root.addView(info);

        download = new Button(this);
        download.setVisibility(View.GONE);
        root.addView(download);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        parse.setOnClickListener(v -> {
            final String text = input.getText().toString();
            info.setText("解析中…");
            download.setVisibility(View.GONE);
            new Thread(() -> {
                try {
                    final DyParser.Result r = DyParser.parse(text);
                    runOnUiThread(() -> show(r));
                } catch (final Exception e) {
                    runOnUiThread(() -> info.setText("解析失败：" + e.getMessage()));
                }
            }).start();
        });

        download.setOnClickListener(v -> {
            if (result == null) return;
            if (result.videoUrl != null) {
                enqueue(result.videoUrl, result.id + ".mp4", Environment.DIRECTORY_MOVIES);
            } else {
                for (int i = 0; i < result.images.size(); i++) {
                    String u = result.images.get(i);
                    String ext = u.contains(".webp") ? ".webp" : ".jpg";
                    enqueue(u, result.id + "_" + (i + 1) + ext, Environment.DIRECTORY_PICTURES);
                }
            }
            Toast.makeText(this, "已加入下载队列，进度见通知栏", Toast.LENGTH_SHORT).show();
        });

        // 从抖音「分享」直接发过来的文字：自动解析
        String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null) {
            input.setText(shared);
            parse.performClick();
        }
    }

    private void show(DyParser.Result r) {
        result = r;
        boolean isVideo = r.videoUrl != null;
        info.setText(r.title + "\n作者：" + r.author + "\n"
                + (isVideo ? "类型：视频" : "类型：图集，共 " + r.images.size() + " 张"));
        download.setText(isVideo ? "下载无水印视频" : "下载全部图片（无水印）");
        download.setVisibility(View.VISIBLE);
    }

    private void enqueue(String url, String name, String dir) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.addRequestHeader("User-Agent", DyParser.UA);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(dir, "DyParse/" + name);
        ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
    }

    // 回到前台时，输入框为空且剪贴板里有抖音链接，就自动填入
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || input == null || input.getText().length() > 0) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData d = cm.getPrimaryClip();
        if (d != null && d.getItemCount() > 0) {
            CharSequence t = d.getItemAt(0).coerceToText(this);
            if (t != null && t.toString().contains("douyin.com")) input.setText(t);
        }
    }
}

class DyParser {

    public static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    public static class Result {
        public String id, title, author, cover;
        public String videoUrl;                       // 图集时为 null
        public List<String> images = new ArrayList<>();
    }

    public static Result parse(String shareText) throws Exception {
        Matcher m = Pattern.compile("https?://[\\w\\-./?%&=#@:+~]+").matcher(shareText);
        if (!m.find()) throw new Exception("没有找到链接");
        String url = m.group();

        // 1) 跟随短链跳转，拿到作品 ID
        String id = null, kind = "video";
        Pattern idPat = Pattern.compile("/(video|note|slides)/(\\d+)");
        Pattern modalPat = Pattern.compile("modal_id=(\\d+)");
        for (int i = 0; i < 6 && id == null; i++) {
            Matcher a = idPat.matcher(url);
            if (a.find()) { kind = a.group(1); id = a.group(2); break; }
            Matcher b = modalPat.matcher(url);
            if (b.find()) { id = b.group(1); break; }
            HttpURLConnection c = open(url);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code / 100 != 3 || loc == null) break;
            url = new URL(new URL(url), loc).toString();
        }
        if (id == null) throw new Exception("无法识别作品 ID，请确认是抖音分享链接");

        // 2) 请求分享页，读出页面内嵌的 _ROUTER_DATA
        Exception last = null;
        for (String k : new String[]{kind, "video", "note"}) {
            try {
                Result r = fromHtml(get("https://www.iesdouyin.com/share/" + k + "/" + id + "/"), id);
                if (r != null) return r;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("没有解析到作品数据（作品可能已删除，或页面已改版）");
    }

    private static Result fromHtml(String html, String id) throws Exception {
        Matcher dm = Pattern.compile("window\\._ROUTER_DATA\\s*=\\s*(.*?)</script>", Pattern.DOTALL).matcher(html);
        if (!dm.find()) return null;
        String js = dm.group(1).trim();
        if (js.endsWith(";")) js = js.substring(0, js.length() - 1);
        JSONObject loader = new JSONObject(js).getJSONObject("loaderData");

        JSONObject info = null;
        Iterator<String> it = loader.keys();
        while (it.hasNext()) {
            JSONObject page = loader.optJSONObject(it.next());
            if (page != null && page.optJSONObject("videoInfoRes") != null) {
                info = page.getJSONObject("videoInfoRes");
                break;
            }
        }
        if (info == null) return null;
        JSONArray list = info.optJSONArray("item_list");
        if (list == null || list.length() == 0) return null;
        JSONObject item = list.getJSONObject(0);

        Result r = new Result();
        r.id = id;
        r.title = item.optString("desc", "").trim();
        if (r.title.isEmpty()) r.title = "douyin_" + id;
        JSONObject author = item.optJSONObject("author");
        r.author = author == null ? "" : author.optString("nickname", "");

        JSONArray imgs = item.optJSONArray("images");
        if (imgs != null && imgs.length() > 0) {
            // 图集：url_list 是无水印原图，download_url_list 通常带水印，仅作兜底
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject im = imgs.getJSONObject(i);
                String u = firstUrl(im.optJSONArray("url_list"));
                if (u == null) u = firstUrl(im.optJSONArray("download_url_list"));
                if (u != null) r.images.add(u);
            }
            if (r.images.isEmpty()) throw new Exception("图集里没有找到图片地址");
        } else {
            JSONObject video = item.optJSONObject("video");
            if (video == null) throw new Exception("作品里没有视频或图片");
            JSONObject play = video.optJSONObject("play_addr");
            String u = play == null ? null : firstUrl(play.optJSONArray("url_list"));
            if (u == null) throw new Exception("没有找到播放地址");
            r.videoUrl = u.replace("playwm", "play");   // 带水印接口 -> 无水印接口
            JSONObject cover = video.optJSONObject("cover");
            r.cover = cover == null ? null : firstUrl(cover.optJSONArray("url_list"));
        }
        return r;
    }

    private static String firstUrl(JSONArray a) {
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) return s.startsWith("http://") ? "https://" + s.substring(7) : s;
        }
        return null;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
