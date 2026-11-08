package app.y700.ubuntu;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 平板上的 Ubuntu 开关。通过 Magisk su 调用 /data/adb/y700-ubuntu/ctl.sh。
 */
public class MainActivity extends Activity {
    private static final String CTL = "/data/adb/y700-ubuntu/ctl.sh";
    private static final int BG = 0xFFF3EEE6;
    private static final int INK = 0xFF1C1917;
    private static final int MUTED = 0xFF6F675F;
    private static final int ORANGE = 0xFFE95420;
    private static final int GREEN = 0xFF1F7A4D;
    private static final int LINE = 0xFFE6E0D6;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView prettyView;
    private TextView autoView;
    private TextView errorView;
    private TextView memBig;
    private TextView memSub;
    private TextView procView;
    private TextView netView;
    private TextView footView;
    private Button primary;
    private Button secondary;
    private View dot;
    private boolean busy;
    private boolean alive = true;
    private String state = "off";
    private String pending;
    private int pollLeft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(32));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(720));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = row();
        TextView mark = text("U", 22, 0xFFFFFFFF);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setShape(GradientDrawable.OVAL);
        bubble.setColor(ORANGE);
        mark.setBackground(bubble);
        head.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = text("Ubuntu", 30, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        prettyView = text("这台平板上的系统", 15, MUTED);
        titles.addView(title);
        titles.addView(prettyView);
        head.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        col.addView(head);

        LinearLayout statusRow = row();
        statusRow.setPadding(0, dp(22), 0, dp(6));
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        paintDot(MUTED);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusView = text("正在读取", 22, INK);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(dp(10), 0, 0, 0);
        statusRow.addView(statusView);
        col.addView(statusRow);

        autoView = text(" ", 14, MUTED);
        col.addView(autoView);

        errorView = text("", 14, 0xFF9B2C2C);
        errorView.setPadding(0, dp(8), 0, 0);
        errorView.setVisibility(View.GONE);
        col.addView(errorView);

        primary = new Button(this);
        styleButton(primary, ORANGE, 0xFFFFFFFF, false);
        primary.setText("开启 Ubuntu");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        bp.topMargin = dp(18);
        col.addView(primary, bp);
        primary.setOnClickListener(v -> onPrimary());

        secondary = new Button(this);
        styleButton(secondary, 0xFFFFFFFF, INK, true);
        secondary.setText("重启");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        sp.topMargin = dp(10);
        secondary.setVisibility(View.GONE);
        col.addView(secondary, sp);
        secondary.setOnClickListener(v -> confirm(
                "重启 Ubuntu",
                "会先关掉再打开，正在用的终端会断一下。系统文件还在。",
                "重启",
                () -> runAction("stop-start")));

        memBig = text("—", 28, INK);
        memBig.setTypeface(Typeface.DEFAULT_BOLD);
        memBig.setPadding(0, dp(8), 0, 0);
        memSub = text(" ", 14, MUTED);
        procView = text(" ", 14, INK);
        procView.setPadding(0, dp(10), 0, 0);
        LinearLayout memCard = card("内存");
        memCard.addView(memBig);
        memCard.addView(memSub);
        memCard.addView(procView);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(18);
        col.addView(memCard, mp);

        netView = text(" ", 15, INK);
        netView.setPadding(0, dp(8), 0, 0);
        LinearLayout netCard = card("网络");
        netCard.addView(netView);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        col.addView(netCard, np);

        footView = text("关闭只停掉服务，不删除系统。开机要不要自动打开，跟这个开关走。", 13, MUTED);
        footView.setPadding(0, dp(16), 0, 0);
        col.addView(footView);

        TextView refresh = text("刷新", 15, ORANGE);
        refresh.setPadding(0, dp(14), 0, 0);
        refresh.setOnClickListener(v -> refresh());
        col.addView(refresh);

        page.addView(col, new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(page);
        return scroll;
    }

    private void onPrimary() {
        if (busy) {
            return;
        }
        if ("on".equals(state) || "starting".equals(state)) {
            confirm(
                    "关闭 Ubuntu",
                    "服务会退出，占用的内存会释放。系统文件还留在平板里。正在用的终端会断开。",
                    "关闭",
                    () -> runAction("stop"));
        } else {
            runAction("start");
        }
    }

    private void confirm(String title, String message, String ok, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(ok, (d, w) -> action.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void refresh() {
        io.execute(() -> {
            String out = su(CTL + " status");
            if (!alive) {
                return;
            }
            main.post(() -> render(out));
        });
    }

    private void runAction(String action) {
        setBusy(true);
        io.execute(() -> {
            String out;
            if ("stop-start".equals(action)) {
                su(CTL + " stop");
                out = su(CTL + " start");
            } else {
                out = su(CTL + " " + action);
            }
            if (!alive) {
                return;
            }
            String shown = out;
            String waitFor = "stop".equals(action) ? "off" : "on";
            main.post(() -> {
                render(shown);
                setBusy(false);
                pending = waitFor;
                pollLeft = 15;
                schedulePoll();
            });
        });
    }

    private void schedulePoll() {
        if (!alive || pending == null || pollLeft <= 0) {
            return;
        }
        main.postDelayed(() -> {
            if (!alive || pending == null || pollLeft <= 0) {
                return;
            }
            pollLeft -= 1;
            refresh();
            schedulePoll();
        }, 2000);
    }

    private void render(String raw) {
        if (raw == null) {
            showError("没有读到状态");
            return;
        }
        String body = raw.trim();
        int brace = body.indexOf('{');
        if (brace > 0) {
            body = body.substring(brace);
        }
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Exception e) {
            showError(body.length() == 0 ? "root 没有返回内容" : body);
            return;
        }
        if (!o.optBoolean("ok", false)) {
            showError(o.optString("error", "读取失败"));
            return;
        }
        errorView.setVisibility(View.GONE);
        state = o.optString("state", "off");
        String pretty = o.optString("pretty", "Ubuntu");
        prettyView.setText(pretty + " · 这台平板上的系统");
        boolean disabled = o.optBoolean("disabled", true);
        if ("on".equals(state)) {
            statusView.setText("运行中");
            paintDot(GREEN);
            autoView.setText(disabled ? "开机不会自动打开" : "开机后会自动打开");
            primary.setText("关闭 Ubuntu");
            styleButton(primary, 0xFF2A2623, 0xFFFFFFFF, false);
            secondary.setVisibility(View.VISIBLE);
        } else if ("starting".equals(state)) {
            statusView.setText("正在启动");
            paintDot(ORANGE);
            autoView.setText("服务还在起来");
            primary.setText("关闭 Ubuntu");
            styleButton(primary, 0xFF2A2623, 0xFFFFFFFF, false);
            secondary.setVisibility(View.GONE);
        } else {
            statusView.setText("已关闭");
            paintDot(MUTED);
            autoView.setText("开机不会自动打开");
            primary.setText("开启 Ubuntu");
            styleButton(primary, ORANGE, 0xFFFFFFFF, false);
            secondary.setVisibility(View.GONE);
        }

        long pss = o.optLong("pss_kb");
        long total = o.optLong("mem_total_kb");
        long avail = o.optLong("mem_available_kb");
        memBig.setText("服务占用 " + mb(pss));
        memSub.setText("平板还可以用 " + gb(avail) + "，一共 " + gb(total));
        procView.setText(procLines(o.optJSONArray("procs")));
        netView.setText(netLines(o));
        if (pending != null && pending.equals(state)) {
            pending = null;
            pollLeft = 0;
        }
    }

    private String procLines(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "没有在跑的 Ubuntu 服务";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(roleName(p.optString("role")))
                    .append("  ")
                    .append(mb(p.optLong("pss_kb")));
        }
        return sb.toString();
    }

    private String netLines(JSONObject o) {
        if (!"on".equals(o.optString("state"))) {
            return "服务停了，没有在监听。\n开启后会使用 192.168.50.161，并连上 Headscale 和 Tailscale。";
        }
        StringBuilder sb = new StringBuilder();
        JSONObject lan = o.optJSONObject("lan");
        if (lan != null && lan.optBoolean("up")) {
            sb.append("局域网  ").append(lan.optString("addr"));
        } else {
            sb.append("局域网  还没有 192.168.50.161");
        }
        JSONObject hs = o.optJSONObject("headscale");
        sb.append("\nHeadscale  ");
        if (hs != null && hs.optBoolean("up")) {
            sb.append(hs.optString("ip"));
            long rx = hs.optLong("rx");
            long tx = hs.optLong("tx");
            if (rx > 0 || tx > 0) {
                sb.append("\n流量  收 ").append(bytes(rx)).append("  发 ").append(bytes(tx));
            }
        } else {
            sb.append("未连接");
        }
        JSONObject ts = o.optJSONObject("tailscale");
        sb.append("\nTailscale  ");
        if (ts != null && ts.optBoolean("up")) {
            sb.append(ts.optString("ip")).append("  用户态网络");
        } else {
            sb.append("未连接");
        }
        JSONArray listeners = o.optJSONArray("listeners");
        sb.append("\nSSH  ");
        if (listeners == null || listeners.length() == 0) {
            sb.append("没有在听 22 端口");
        } else {
            for (int i = 0; i < listeners.length(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(listeners.optString(i));
            }
        }
        return sb.toString();
    }

    private static String roleName(String role) {
        if ("headscale".equals(role)) return "Headscale";
        if ("tailscale".equals(role)) return "Tailscale";
        if ("sshd".equals(role)) return "SSH";
        if ("session".equals(role)) return "SSH 会话";
        if ("shell".equals(role)) return "终端";
        if ("grok".equals(role)) return "Grok";
        if ("keeper".equals(role)) return "守护";
        return "其他";
    }

    private void showError(String message) {
        String text = message == null ? "" : message.trim();
        if (text.contains("Connection refused") || text.contains("SIGTRAP") || text.length() == 0) {
            text = "拿不到 root。请在 Magisk 里允许 Ubuntu。";
        }
        if (text.length() > 280) {
            text = text.substring(0, 280);
        }
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy = value;
        primary.setEnabled(!value);
        secondary.setEnabled(!value);
        if (value) {
            primary.setText("请稍候");
        }
    }

    private void paintDot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
    }

    private String su(String command) {
        String[] bins = new String[]{"/product/bin/su", "/debug_ramdisk/su", "su"};
        String last = "";
        for (String bin : bins) {
            try {
                Process p = new ProcessBuilder(bin, "-c", command)
                        .redirectErrorStream(true)
                        .start();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                Thread reader = new Thread(() -> copy(p.getInputStream(), buf));
                reader.start();
                boolean done = p.waitFor(20, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                }
                reader.join(1000);
                last = buf.toString(StandardCharsets.UTF_8.name());
                if (done && p.exitValue() == 0 && last.contains("{")) {
                    return last;
                }
                if (last.contains("{")) {
                    return last;
                }
            } catch (Exception e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }
        return last == null ? "" : last;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] b = new byte[4096];
        try {
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
        } catch (Exception ignored) {
            // 进程结束时读流会断，调用方看退出码。
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setElevation(dp(2));
        TextView label = text(title, 13, MUTED);
        card.addView(label);
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private void styleButton(Button button, int fill, int fg, boolean stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(16));
        if (stroke) {
            bg.setStroke(dp(1), LINE);
        }
        button.setBackground(bg);
        button.setTextColor(fg);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        button.setStateListAnimator(null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String mb(long kb) {
        if (kb >= 1024 * 1024) {
            return gb(kb);
        }
        return Math.round(kb / 1024.0) + " MB";
    }

    private static String gb(long kb) {
        if (kb >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", kb / 1024.0 / 1024.0);
        }
        return mb(kb);
    }

    private static String bytes(long n) {
        if (n >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", n / 1024.0 / 1024.0 / 1024.0);
        }
        if (n >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0);
        }
        if (n >= 1024L) {
            return String.format(Locale.US, "%.0f KB", n / 1024.0);
        }
        return n + " B";
    }
}
