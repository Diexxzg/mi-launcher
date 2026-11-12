package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Cabecera al estilo del minilauncher: reloj pequeno sin fecha, "a. m." en minusculas, pastilla de red
 * rosada con icono de Bluetooth y boton de tres puntos en lugar de "Entradas".
 *
 * Es autonoma: lee sus propias opciones de config.json (mismo archivo del tema) y no toca Config.java.
 * Todo viene activo por defecto; se apaga con "mi_header": false. Sin servicios ni receptores: el estado
 * de Bluetooth se lee cuando el launcher refresca la etiqueta de red.
 */
final class MiHeader {
    private static JSONObject opts;
    private static boolean btOn = true;

    private MiHeader() {}

    private static JSONObject read(Context ctx) {
        if (opts != null) return opts;
        opts = new JSONObject();
        try {
            File dir = Config.dir(ctx);
            File f = dir != null ? new File(dir, Config.FILE_NAME) : null;
            if (f != null && f.isFile() && f.length() < 256 * 1024) {
                try (FileInputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    opts = new JSONObject(out.toString("UTF-8"));
                }
            }
        } catch (Exception ignored) {
            opts = new JSONObject();
        }
        return opts;
    }

    private static boolean on(Context ctx) {
        return read(ctx).optBoolean("mi_header", true);
    }

    /** Llamar una vez en onCreate, cuando los botones ya existen. */
    static void apply(Activity a, View btnInputs, View btnWifi, View btnSettings) {
        if (!on(a)) return;
        JSONObject o = read(a);
        float d = a.getResources().getDisplayMetrics().density;
        btOn = o.optBoolean("mi_bluetooth", true);

        // Reloj pequeno, sin fecha, "a. m." en minusculas.
        TextView clock = a.findViewById(R.id.clock);
        TextView ampm = a.findViewById(R.id.ampm);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) o.optDouble("mi_clock_sp", 40));
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ampm.setAllCaps(false);
        ViewGroup.LayoutParams lp = ampm.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).bottomMargin = Math.round(3 * d);
        }
        if (o.optBoolean("mi_hide_date", true)) {
            View date = a.findViewById(R.id.date);
            if (date.getParent() instanceof View) ((View) date.getParent()).setVisibility(View.GONE);
            a.findViewById(R.id.clockDivider).setVisibility(View.GONE);
        }

        // Pastilla de red rosada (el color se ajusta con "mi_pill_color").
        if (o.optBoolean("mi_pink_pill", false)) {
            int pink = parse(o.optString("mi_pill_color", "#CCE57FA3"), 0xCCE57FA3);
            btnWifi.setBackground(pill(pink, d));
        }

        // "..." en lugar del icono de Entradas (sigue abriendo el mismo menu de entradas).
        if (o.optBoolean("mi_more_button", true) && btnInputs instanceof TextView) {
            TextView more = (TextView) btnInputs;
            more.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_more_horiz, 0, 0, 0);
            // Solo el circulo con los tres puntos: sin la palabra "Entradas" (sigue abriendo el menu de entradas).
            more.setContentDescription(more.getText());
            more.setText(null);
            int pad = Math.round(10 * d);
            more.setPaddingRelative(pad, 0, pad, 0);
            more.setCompoundDrawablePadding(0);
            ViewGroup.LayoutParams mlp = more.getLayoutParams();
            if (mlp != null) mlp.width = Math.round(42 * d);
        }
    }

    /** Llamar al final de updateWifiLabel: pone o quita el icono de Bluetooth (con contador) a la derecha de la pastilla de red. */
    static void bluetooth(TextView pill) {
        if (!on(pill.getContext()) || !btOn) return;
        int n = bluetoothCount(pill.getContext());   // -1 oculto, 0 sin contador, >0 con contador
        android.graphics.drawable.Drawable[] c = pill.getCompoundDrawablesRelative();
        android.graphics.drawable.Drawable end = null;
        if (n >= 0) {
            end = new BtBadge(pill.getContext().getDrawable(R.drawable.ic_bluetooth), n,
                    pill.getResources().getDisplayMetrics().density);
        }
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(c[0], c[1], end, c[3]);
    }

    /**
     * -1: Bluetooth apagado (sin icono). 0: encendido pero sin permiso para contar (icono solo).
     * N > 0: dispositivos conectados (aproximado: GATT mas audio). Con el permiso y sin conectados, -1.
     * Concederlo: adb shell pm grant com.gathin.tvhome android.permission.BLUETOOTH_CONNECT
     */
    private static int bluetoothCount(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null || !ad.isEnabled()) return -1;
            if (Build.VERSION.SDK_INT >= 31
                    && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return 0;
            }
            int n = bm.getConnectedDevices(BluetoothProfile.GATT).size();
            if (ad.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                    || ad.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) n++;
            return n > 0 ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Icono de Bluetooth con el numero al lado; toma el color del tinte de la pastilla (blanco, o oscuro al enfocar). */
    private static final class BtBadge extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable icon;
        private final String label;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int iconPx, gapPx, textW;
        private android.content.res.ColorStateList tint;

        BtBadge(android.graphics.drawable.Drawable icon, int count, float d) {
            this.icon = icon.mutate();
            this.label = count > 0 ? String.valueOf(count) : "";
            this.iconPx = Math.round(18 * d);
            this.gapPx = label.isEmpty() ? 0 : Math.round(4 * d);
            paint.setTextSize(13 * d);
            this.textW = label.isEmpty() ? 0 : Math.round(paint.measureText(label));
            setBounds(0, 0, getIntrinsicWidth(), getIntrinsicHeight());
        }

        @Override public int getIntrinsicWidth() { return iconPx + gapPx + textW; }
        @Override public int getIntrinsicHeight() { return iconPx; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { return tint != null; }

        @Override public void setTintList(android.content.res.ColorStateList t) {
            tint = t;
            invalidateSelf();
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            int color = tint != null ? tint.getColorForState(getState(), tint.getDefaultColor()) : 0xE6FFFFFF;
            android.graphics.Rect b = getBounds();
            icon.setBounds(b.left, b.top, b.left + iconPx, b.top + iconPx);
            icon.setTint(color);
            icon.draw(canvas);
            if (!label.isEmpty()) {
                paint.setColor(color);
                android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(label, b.left + iconPx + gapPx, y, paint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static StateListDrawable pill(int color, float d) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xF5FFFFFF);
        focused.setCornerRadius(21 * d);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(21 * d);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    private static int parse(String s, int fallback) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}
