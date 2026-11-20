package org.nx2a;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The launcher when the APK was built with --data-import: a free-use notice on the first run, then the game's data folder checked, and a data-pack ZIP
 * (made by `porter pack`, or any ZIP holding RomFS/ and nx2a-data.json at its root or under one folder) extracted into the app's files folder with the
 * system picker. Without this activity a port needs `porter install` or a manual copy to get its data across; the game itself starts either way.
 */
public class DataActivity extends Activity {
    private static final int PICK_ZIP = 0x2a01;
    private static final String PREFS = "nx2a", ACCEPTED = "notice_accepted";
    private static final String NOTICE_ZH =
            "本移植版完全免费，仅供学习与技术研究使用。\n"
          + "严禁销售、收费分发、捆绑收费或用于其他盈利活动。\n"
          + "游戏相关内容的权利归原权利人所有，请使用合法取得的游戏数据。\n\n"
          + "Free-use notice\n\n"
          + "This port is free of charge and is meant for study and technical research only.\n"
          + "Selling it, charging for it, bundling it with a paid service or any other commercial use is prohibited.\n"
          + "All game content belongs to its rights holders. Use game data you legally own.";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, detail;
    private ProgressBar bar;
    private Button pick, quit;
    private volatile boolean importing;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(ACCEPTED, false)) proceed();
        else showNotice(p);
    }

    /** The notice, on a first run only; "exit" finishes before anything of the game is loaded. */
    private void showNotice(final SharedPreferences p) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("免费使用声明 / Free-use notice")
                .setMessage(NOTICE_ZH)
                .setCancelable(false)
                .setNegativeButton("退出 / Exit", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { finishAffinity(); }
                })
                .setPositiveButton("知悉并继续 / I understand", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        p.edit().putBoolean(ACCEPTED, true).apply();
                        proceed();
                    }
                })
                .create();
        d.show();
    }

    private void proceed() {
        if (hasData()) { startGame(); return; }
        say("正在检查游戏数据… / Checking game data…", "没有找到游戏数据，请选择数据包 ZIP。\nNo game data found — pick the data-pack ZIP.");
        pick.setEnabled(true);
        quit.setEnabled(true);
    }

    private File filesDir() {
        File f = getExternalFilesDir(null);
        return f != null ? f : new File(getFilesDir(), "files");
    }

    private boolean hasData() {
        File d = filesDir();
        return new File(d, "RomFS").isDirectory() || new File(d, "nx2a-data.json").isFile();
    }

    private void say(final String s, final String d) {
        ui.post(new Runnable() { @Override public void run() {
            status.setText(s);
            if (detail != null) detail.setText(d);
        }});
    }

    private View buildUi() {
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(24, 26, 30));
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(20f);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setText("正在检查游戏数据… / Checking game data…");
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        detail = new TextView(this);
        detail.setTextSize(14f);
        detail.setTextColor(Color.rgb(190, 195, 205));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        bar.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = pad / 2; bl.bottomMargin = pad;
        root.addView(bar, bl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        pick = new Button(this);
        pick.setText("选择游戏数据 ZIP / Select data ZIP");
        pick.setEnabled(false);
        pick.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { choose(); } });
        quit = new Button(this);
        quit.setText("取消 / Cancel");
        quit.setEnabled(false);
        quit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finishAffinity(); } });
        row.addView(pick);
        row.addView(quit);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void choose() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(i, PICK_ZIP); }
        catch (ActivityNotFoundException e) { say("没有文件选择器 / No file picker", "请先安装一个文件管理器。\nInstall a file manager first."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_ZIP || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        importing = true; pick.setEnabled(false); quit.setEnabled(false);
        bar.setIndeterminate(false); bar.setProgress(0);
        say("正在解压游戏数据… / Extracting…", "");
        new Thread(new Runnable() { @Override public void run() { importZip(uri); } }, "nx2a-data").start();
    }

    /** Extracts the ZIP under the app's files folder; one leading folder in the archive (the pack's own name) is dropped. */
    private void importZip(Uri uri) {
        File dir = filesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) { fail("建不了目录 / cannot create " + dir); return; }
        String prefix = null;
        long bytes = 0; int entries = 0;
        try {
            InputStream raw = getContentResolver().openInputStream(uri);
            if (raw == null) { fail("打不开这个 ZIP / cannot open the ZIP"); return; }
            ZipInputStream zin = new ZipInputStream(raw);
            byte[] buf = new byte[1 << 20];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (prefix == null) {
                    int slash = name.indexOf('/');
                    prefix = slash > 0 ? name.substring(0, slash + 1) : "";
                }
                if (!prefix.isEmpty() && name.startsWith(prefix)) name = name.substring(prefix.length());
                if (name.isEmpty()) { zin.closeEntry(); continue; }
                File out = new File(dir, name);
                if (e.isDirectory()) { out.mkdirs(); zin.closeEntry(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) { fail("建不了目录 / cannot create " + parent); return; }
                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) > 0) { os.write(buf, 0, n); bytes += n; }
                os.close();
                zin.closeEntry();
                entries++;
                if ((entries & 63) == 0) {
                    final int en = entries; final long mb = bytes >> 20;
                    ui.post(new Runnable() { @Override public void run() {
                        detail.setText(en + " 个文件 / files, " + mb + " MB");
                    }});
                }
            }
            zin.close();
        } catch (IOException ex) {
            fail("解压失败 / extraction failed: " + ex.getMessage());
            return;
        }
        importing = false;
        if (!hasData()) { fail("ZIP 里没有 RomFS 或 nx2a-data.json\nNo RomFS or nx2a-data.json in the ZIP"); return; }
        say("完成，正在启动游戏… / Done, starting…", entries + " 个文件 / files, " + (bytes >> 20) + " MB");
        ui.post(new Runnable() { @Override public void run() { startGame(); } });
    }

    private void fail(final String why) {
        importing = false;
        ui.post(new Runnable() { @Override public void run() {
            bar.setIndeterminate(false); bar.setProgress(0);
            pick.setEnabled(true); quit.setEnabled(true);
            say("导入失败 / Import failed", why);
        }});
    }

    /** The manifest's activity that carries the native library: android.app.lib_name names it as the game's. */
    private String nativeActivity() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            if (pi.activities != null) for (ActivityInfo a : pi.activities) {
                if (a.metaData != null && a.metaData.containsKey("android.app.lib_name")) return a.name;
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        return "org.nx2a.GameActivity";
    }

    private void startGame() {
        Intent i = new Intent();
        i.setClassName(this, nativeActivity());
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        finish();
    }

    @Override public void onBackPressed() {
        if (importing) { say("正在解压，请稍候… / Extracting, please wait…", ""); return; }
        super.onBackPressed();
    }
}
