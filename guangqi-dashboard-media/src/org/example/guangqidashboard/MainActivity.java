package org.example.guangqidashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private static final int PICK_DOCUMENT = 10;
    private static final int PICK_FILE = 11;
    private static final int STORAGE = 12;
    private final ArrayList<MediaItem> items = new ArrayList<MediaItem>();
    private final ArrayList<Display> displays = new ArrayList<Display>();
    private final Handler handler = new Handler();
    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> waitingUris = new ArrayList<Uri>();
    private PlaylistStore store;
    private DisplayManager displayManager;
    private Spinner displaySpinner;
    private Spinner modeSpinner;
    private Spinner durationSpinner;
    private CheckBox loop;
    private LinearLayout list;
    private TextView status;
    private TextView listTitle;
    private MediaPresentation presentation;
    private boolean importing;
    private boolean destroyed;
    private boolean foreground;
    private int playing = -1;
    private String message = "请选择媒体文件";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new PlaylistStore(this);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        buildUi();
        try { items.addAll(store.load()); }
        catch (Exception error) { message = "读取上次列表失败：" + error.getMessage(); }
        refreshList();
        displayManager.registerDisplayListener(this, handler);
        scanDisplays();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("副屏媒体播放", 24));
        root.addView(text("仅供停车测试，可能遮挡仪表。主屏无媒体预览；退到后台或断开副屏即停止。", 14));
        status = text("", 14);
        root.addView(status);
        root.addView(button("选择媒体（文件 / 媒体管理器，可多选）", new View.OnClickListener() {
            @Override public void onClick(View view) { pick(false); }
        }));
        root.addView(button("系统文档选择器（备用，可多选）", new View.OnClickListener() {
            @Override public void onClick(View view) { pick(true); }
        }));
        root.addView(text("播放顺序以返回的文件列表为准，可在下方上移/下移。多选由所用管理器决定，通常需长按文件。", 12));
        root.addView(text("目标副屏（禁止选择主屏）", 14));
        displaySpinner = new Spinner(this);
        root.addView(displaySpinner);
        root.addView(button("刷新副屏", new View.OnClickListener() {
            @Override public void onClick(View view) { scanDisplays(); }
        }));
        modeSpinner = spinner(new String[] {"标准 Presentation", "广汽兼容 TYPE_PHONE / 2002", "腾讯兼容 TYPE_SYSTEM_ALERT / 2003"});
        modeSpinner.setSelection(getPreferences(0).getInt("mode", 0));
        root.addView(modeSpinner);
        root.addView(button("授权悬浮窗（仅兼容模式需要）", new View.OnClickListener() {
            @Override public void onClick(View view) {
                stopPlayback("打开权限设置，已停止播放");
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))); }
                catch (ActivityNotFoundException error) { toast("请在系统设置中为本应用开启悬浮窗权限"); }
            }
        }));
        root.addView(text("图片 / GIF 每项停留时间（视频播放至结束）", 14));
        durationSpinner = spinner(new String[] {"5 秒", "10 秒", "15 秒", "30 秒", "60 秒"});
        durationSpinner.setSelection(getPreferences(0).getInt("duration", 0));
        root.addView(durationSpinner);
        loop = new CheckBox(this);
        loop.setText("按列表顺序循环播放");
        loop.setChecked(getPreferences(0).getBoolean("loop", true));
        root.addView(loop);
        LinearLayout controls = row();
        controls.addView(button("开始播放", new View.OnClickListener() {
            @Override public void onClick(View view) { confirmPlay(); }
        }), weighted());
        controls.addView(button("下一项", new View.OnClickListener() {
            @Override public void onClick(View view) { if (presentation != null) presentation.next(); }
        }), weighted());
        controls.addView(button("停止", new View.OnClickListener() {
            @Override public void onClick(View view) { stopPlayback("已停止"); }
        }), weighted());
        root.addView(controls);
        listTitle = text("", 18);
        root.addView(listTitle);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        root.addView(button("清空文件列表与内部副本", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (importing || items.isEmpty()) return;
                new AlertDialog.Builder(MainActivity.this).setTitle("清空列表？")
                        .setMessage("只删除本应用导入的内部副本，不修改原始文件。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                stopPlayback("已清空列表");
                                ArrayList<MediaItem> old = new ArrayList<MediaItem>(items);
                                items.clear();
                                if (saveList()) for (MediaItem item : old) item.file.delete();
                                else items.addAll(old);
                                refreshList();
                            }
                        }).show();
            }
        }));
    }

    private void pick(boolean document) {
        if (importing) { toast("正在导入，请稍候"); return; }
        stopPlayback("选择媒体，已停止播放");
        Intent intent = new Intent(document ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图片、GIF 或视频（可多选）"),
                    document ? PICK_DOCUMENT : PICK_FILE);
        } catch (ActivityNotFoundException error) {
            toast(document ? "系统没有文档选择器，请使用文件管理器入口" : "未找到可用文件管理器，请尝试系统文档选择器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != PICK_DOCUMENT && request != PICK_FILE) || result != RESULT_OK || data == null) return;
        Set<Uri> selected = new LinkedHashSet<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) { toast("选择器未返回文件"); return; }
        waitingUris.clear();
        waitingUris.addAll(selected);
        for (Uri uri : selected) {
            if ("file".equals(uri.getScheme())
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
                return;
            }
        }
        importWaiting();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) importWaiting();
        else {
            waitingUris.clear();
            toast("文件路径读取权限未授权，请改用系统文档选择器");
        }
    }

    private void importWaiting() {
        if (waitingUris.isEmpty() || destroyed) return;
        final ArrayList<Uri> uris = new ArrayList<Uri>(waitingUris);
        waitingUris.clear();
        importing = true;
        message = "正在复制文件到应用内部存储…";
        updateStatus();
        importer.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<MediaItem> added = new ArrayList<MediaItem>();
                final ArrayList<String> errors = new ArrayList<String>();
                for (int i = 0; i < uris.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try { added.add(store.copy(uris.get(i))); }
                    catch (Exception error) { errors.add("第 " + (i + 1) + " 项：" + error.getMessage()); }
                    final int done = i + 1;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!destroyed) { message = "正在导入 " + done + " / " + uris.size(); updateStatus(); }
                        }
                    });
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) {
                            for (MediaItem item : added) item.file.delete();
                            return;
                        }
                        importing = false;
                        items.addAll(added);
                        boolean saved = saveList();
                        message = "导入 " + added.size() + " 项，失败 " + errors.size() + " 项"
                                + (saved ? "；点击开始播放" : "；列表保存失败");
                        refreshList();
                        updateStatus();
                        if (!errors.isEmpty() && foreground) {
                            StringBuilder detail = new StringBuilder();
                            for (int i = 0; i < Math.min(10, errors.size()); i++) detail.append(errors.get(i)).append('\n');
                            new AlertDialog.Builder(MainActivity.this).setTitle("部分文件导入失败")
                                    .setMessage(detail + "\n如权限不足，请改用系统文档选择器。")
                                    .setPositiveButton("知道了", null).show();
                        }
                    }
                });
            }
        });
    }

    private void scanDisplays() {
        int oldId = getPreferences(0).getInt("display", -1);
        int selected = displaySpinner.getSelectedItemPosition();
        if (selected >= 0 && selected < displays.size()) oldId = displays.get(selected).getDisplayId();
        displays.clear();
        ArrayList<String> labels = new ArrayList<String>();
        int next = -1;
        Set<Integer> preferred = new java.util.HashSet<Integer>();
        for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            preferred.add(display.getDisplayId());
        }
        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY || !display.isValid()) continue;
            Point size = new Point();
            display.getRealSize(size);
            if (display.getDisplayId() == oldId) next = displays.size();
            else if (next == -1 && preferred.contains(display.getDisplayId())) next = displays.size();
            displays.add(display);
            labels.add("ID " + display.getDisplayId() + " · " + display.getName() + " · " + size.x + "×" + size.y);
        }
        if (labels.isEmpty()) labels.add("未发现副屏，请切换原车仪表页面后刷新");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySpinner.setAdapter(adapter);
        if (!displays.isEmpty()) displaySpinner.setSelection(Math.max(0, next));
        updateStatus();
    }

    private void confirmPlay() {
        if (importing) { toast("请等待导入完成"); return; }
        if (items.isEmpty()) { toast("请先选择媒体文件"); return; }
        int selected = displaySpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= displays.size()) { toast("未发现可用副屏"); return; }
        final int id = displays.get(selected).getDisplayId();
        final int mode = modeSpinner.getSelectedItemPosition();
        if (mode != 0 && !Settings.canDrawOverlays(this)) { toast("兼容模式需要先授权悬浮窗"); return; }
        new AlertDialog.Builder(this).setTitle("确认车辆已停稳")
                .setMessage("将在副屏 ID " + id + " 全屏播放，可能遮挡仪表信息。\n禁止行车观看。主屏不播放媒体。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已停车，开始播放", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { startPlayback(id, mode); }
                }).show();
    }

    private void startPlayback(int id, int mode) {
        if (!foreground || importing || items.isEmpty()) return;
        stopPlayback("准备播放");
        Display display = displayManager.getDisplay(id);
        if (display == null || !display.isValid() || id == Display.DEFAULT_DISPLAY) { toast("目标副屏已失效"); return; }
        final int[] durations = {5, 10, 15, 30, 60};
        int duration = Math.max(0, Math.min(durations.length - 1, durationSpinner.getSelectedItemPosition()));
        getPreferences(0).edit().putInt("display", id).putInt("mode", mode)
                .putInt("duration", duration).putBoolean("loop", loop.isChecked()).apply();
        try {
            final MediaPresentation candidate = new MediaPresentation(this, display, items, loop.isChecked(),
                    durations[duration], new MediaPresentation.Listener() {
                        @Override public void onPlaying(int index) {
                            playing = index;
                            message = "副屏播放 " + (index + 1) + " / " + items.size() + "：" + items.get(index).name;
                            refreshList();
                            updateStatus();
                        }
                        @Override public void onError(int index, String error) {
                            message = "跳过「" + items.get(index).name + "」：" + error;
                            android.util.Log.w("DashboardPlayer", message);
                            toast(message);
                            updateStatus();
                        }
                        @Override public void onFinished(String reason) { message = reason; }
                    });
            if (mode != 0) candidate.getWindow().setType(mode == 1
                    ? WindowManager.LayoutParams.TYPE_PHONE : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            candidate.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dialog) {
                    if (presentation == candidate) {
                        presentation = null;
                        playing = -1;
                        if (message.startsWith("副屏播放")) message = "副屏窗口已关闭";
                        refreshList();
                        updateStatus();
                    }
                }
            });
            presentation = candidate;
            candidate.show();
        } catch (RuntimeException error) {
            stopPlayback("副屏建窗失败：" + error.getMessage());
            toast("副屏建窗失败，请检查悬浮窗权限或切换窗口模式");
        }
    }

    private void stopPlayback(String reason) {
        MediaPresentation old = presentation;
        presentation = null;
        if (old != null) old.dismiss();
        playing = -1;
        message = reason;
        refreshList();
        updateStatus();
    }

    private boolean saveList() {
        try { store.save(items); return true; }
        catch (Exception error) { toast("保存列表失败：" + error.getMessage()); return false; }
    }

    private void refreshList() {
        listTitle.setText("已选文件（" + items.size() + "）");
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            MediaItem item = items.get(i);
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.VERTICAL);
            if (i == playing) entry.setBackgroundColor(Color.rgb(218, 237, 248));
            TextView name = text((i == playing ? "▶ " : "") + (i + 1) + ". " + item.name
                    + "\n" + item.mime + " · " + android.text.format.Formatter.formatFileSize(this, item.file.length()), 15);
            entry.addView(name);
            LinearLayout actions = row();
            Button up = button("上移", new View.OnClickListener() {
                @Override public void onClick(View view) { move(index, -1); }
            });
            up.setEnabled(!importing && i > 0);
            actions.addView(up, weighted());
            Button down = button("下移", new View.OnClickListener() {
                @Override public void onClick(View view) { move(index, 1); }
            });
            down.setEnabled(!importing && i + 1 < items.size());
            actions.addView(down, weighted());
            Button remove = button("移除", new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (importing || index >= items.size()) return;
                    stopPlayback("列表已修改，请重新开始播放");
                    MediaItem removed = items.remove(index);
                    if (saveList()) removed.file.delete();
                    else items.add(index, removed);
                    refreshList();
                }
            });
            remove.setEnabled(!importing);
            actions.addView(remove, weighted());
            entry.addView(actions);
            list.addView(entry);
        }
    }

    private void move(int index, int offset) {
        if (importing || index < 0 || index >= items.size() || index + offset < 0 || index + offset >= items.size()) return;
        stopPlayback("顺序已修改，请重新开始播放");
        Collections.swap(items, index, index + offset);
        if (!saveList()) Collections.swap(items, index, index + offset);
        refreshList();
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText("副屏 " + displays.size() + " 个 · 悬浮窗 "
                + (Settings.canDrawOverlays(this) ? "已授权" : "未授权") + "\n" + message);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        scanDisplays();
    }

    @Override protected void onStop() {
        foreground = false;
        if (presentation != null) stopPlayback("应用已退到后台，停止播放");
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopPlayback("界面已关闭");
        importer.shutdownNow();
        displayManager.unregisterDisplayListener(this);
        super.onDestroy();
    }

    @Override public void onDisplayAdded(int id) { scanDisplays(); }
    @Override public void onDisplayRemoved(int id) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == id) stopPlayback("副屏已断开");
        scanDisplays();
    }
    @Override public void onDisplayChanged(int id) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == id) {
            Display display = displayManager.getDisplay(id);
            if (display == null || !display.isValid() || display.getState() == Display.STATE_OFF) stopPlayback("副屏已不可用");
        }
        scanDisplays();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(listener);
        return view;
    }
    private Spinner spinner(String[] labels) {
        Spinner view = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
