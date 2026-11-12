package com.gathin.tvhome;

import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mini servidor para subir un fondo desde el celular. No corre en segundo plano: LauncherSettingsActivity
 * lo abre con start() mientras el cuadro "Subir desde el celular" esta en pantalla y lo cierra con stop().
 * Cada subida lleva un codigo de 4 digitos que se muestra en la TV; 5 errores bloquean 60 segundos.
 * Solo acepta POST /upload (imagen) y sirve una pagina de una sola pantalla en GET /. Sin dependencias.
 */
final class PhoneUpload {
    interface Listener {
        /** Se llama en el hilo del servidor; quien la recibe debe pasar al hilo de la interfaz. */
        void onUploaded(String fileName);
    }

    private static final int[] PORTS = {8080, 8081, 8082};
    /** Mismo tope que WallpaperRotation.images(): un archivo mayor no aparecería en el selector. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60_000L;

    private final File wallpapers;
    private final Listener listener;
    private final String pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
    private volatile ServerSocket server;
    private volatile boolean running;
    private int port;
    private int fails;
    private long lockUntil;

    PhoneUpload(File wallpapersDir, Listener listener) {
        this.wallpapers = wallpapersDir;
        this.listener = listener;
    }

    String pin() { return pin; }

    /** "http://192.168.1.6:8080", o null si la TV no tiene red. */
    String url() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + port;
    }

    boolean start() {
        if (running) return true;
        for (int p : PORTS) {
            try {
                final ServerSocket s = new ServerSocket(p);
                server = s;
                port = p;
                running = true;
                Thread t = new Thread(() -> {
                    while (running) {
                        try {
                            final Socket c = s.accept();
                            Thread h = new Thread(() -> handle(c), "phone-upload-conn");
                            h.setDaemon(true);
                            h.start();
                        } catch (IOException e) {
                            break;
                        }
                    }
                }, "phone-upload");
                t.setDaemon(true);
                t.start();
                return true;
            } catch (IOException ignored) {
                // puerto ocupado: prueba el siguiente
            }
        }
        return false;
    }

    void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String localIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) return null;
        }
    }

    private static void reply(Socket c, String status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(bytes);
        o.flush();
    }

    private static void text(Socket c, String status, String body) throws IOException {
        reply(c, status, "text/plain; charset=utf-8", body);
    }

    private synchronized boolean locked() { return System.currentTimeMillis() < lockUntil; }

    private synchronized void fail() {
        if (++fails >= MAX_FAILS) {
            fails = 0;
            lockUntil = System.currentTimeMillis() + LOCK_MS;
        }
    }

    private synchronized void ok() { fails = 0; }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String first = readLine(in);
            if (first == null) return;
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String full = parts[1];
            int qi = full.indexOf('?');
            String path = qi < 0 ? full : full.substring(0, qi);
            Map<String, String> q = new HashMap<>();
            if (qi >= 0) {
                for (String kv : full.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    q.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
            long len = 0;
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try { len = Long.parseLong(h.substring(h.indexOf(':') + 1).trim()); }
                    catch (NumberFormatException ignored) { len = 0; }
                }
            }
            if ("GET".equals(method) && "/".equals(path)) {
                reply(c, "200 OK", "text/html; charset=utf-8", PAGE);
                return;
            }
            if (!"POST".equals(method) || !"/upload".equals(path)) {
                text(c, "404 Not Found", "No existe");
                return;
            }
            if (locked()) {
                text(c, "429 Too Many Requests", "Demasiados intentos. Espera un minuto.");
                return;
            }
            if (!pin.equals(q.get("k"))) {
                fail();
                text(c, "403 Forbidden", "Código incorrecto");
                return;
            }
            ok();
            if (len <= 0 || len > MAX_BYTES) {
                text(c, "413 Payload Too Large", "La foto pesa más de 10 MB");
                return;
            }
            saveUpload(c, in, len, q.get("name"));
        } catch (Exception ignored) {
            // conexion cortada o mal formada: se descarta
        } finally {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private void saveUpload(Socket c, InputStream in, long len, String originalName) throws IOException {
        if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) {
            text(c, "500 Internal Server Error", "No se pudo crear la carpeta de fondos");
            return;
        }
        String ext = "jpg";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            String e = dot < 0 ? "" : originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("webp")) ext = e;
        }
        File tmp = new File(wallpapers, ".subida.tmp");
        long left = len;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        if (left > 0) {
            tmp.delete();
            text(c, "400 Bad Request", "La subida se cortó. Inténtalo otra vez.");
            return;
        }
        // Solo se leen las medidas: no se decodifica la imagen, asi que casi no gasta RAM.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (!WallpaperSize.accepted(o.outWidth, o.outHeight)) {
            tmp.delete();
            text(c, "422 Unprocessable Entity", "No se pudo leer la imagen o es mayor que 4K");
            return;
        }
        String name = "fondo_" + System.currentTimeMillis() + "." + ext;
        File dest = new File(wallpapers, name);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            text(c, "500 Internal Server Error", "No se pudo guardar el fondo");
            return;
        }
        listener.onUploaded(name);
        text(c, "200 OK", "Fondo subido. Ya se ve en la TV.");
    }

    /** Pagina de una pantalla. La foto se reduce a 4K y se pasa a JPEG en el celular antes de subirla. */
    private static final String PAGE =
            "<!doctype html><html lang='es'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Fondo de la TV</title>"
            + "<style>body{font-family:sans-serif;background:#111;color:#eee;max-width:480px;margin:0 auto;padding:20px}"
            + "input,button{width:100%;box-sizing:border-box;font-size:18px;padding:14px;margin:8px 0;border-radius:12px;border:0}"
            + "input{background:#222;color:#eee}button{background:#7c5cf0;color:#fff;font-weight:bold}"
            + "#m{margin-top:12px;color:#5eead4}</style></head><body>"
            + "<h2>Fondo de la TV</h2><p>Escribe el código que se ve en la TV y elige una foto.</p>"
            + "<input id='k' inputmode='numeric' maxlength='4' placeholder='Código'>"
            + "<input id='f' type='file' accept='image/*'><button onclick='up()'>Subir</button><div id='m'></div>"
            + "<script>"
            + "function M(s){document.getElementById('m').textContent=s}"
            + "function up(){var f=document.getElementById('f').files[0],k=document.getElementById('k').value;"
            + "if(!f)return M('Elige una foto');if(!k)return M('Escribe el código');M('Preparando...');"
            + "var im=new Image();im.onload=function(){"
            + "var s=Math.min(1,Math.sqrt(8000000/(im.width*im.height))),c=document.createElement('canvas');"
            + "c.width=Math.round(im.width*s);c.height=Math.round(im.height*s);"
            + "c.getContext('2d').drawImage(im,0,0,c.width,c.height);"
            + "c.toBlob(function(b){if(!b)return M('No se pudo preparar la foto');"
            + "if(b.size>10000000)return c.toBlob(function(b2){send(b2,k)},'image/jpeg',0.7);send(b,k)},'image/jpeg',0.9)};"
            + "im.onerror=function(){M('No se pudo leer la foto')};im.src=URL.createObjectURL(f)}"
            + "function send(b,k){M('Subiendo...');"
            + "fetch('/upload?k='+encodeURIComponent(k)+'&name=foto.jpg',{method:'POST',body:b})"
            + ".then(function(r){return r.text()}).then(M).catch(function(){M('No se pudo conectar')})}"
            + "</script></body></html>";
}
