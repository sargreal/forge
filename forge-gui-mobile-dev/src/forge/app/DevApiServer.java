package forge.app;

import com.sun.net.httpserver.HttpServer;
import forge.adventure.devapi.DevApi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Dev-only HTTP front end for {@link DevApi} (see docs/MiddleEarth/DEVELOPMENT.md, "Driving the game via the dev API").
 * Started only when the system property {@code forge.devapi.port} (or env {@code FORGE_DEVAPI_PORT}) is set;
 * binds to 127.0.0.1 only. Desktop launcher only, so the shared module never references com.sun.net.httpserver.
 */
public final class DevApiServer {
    private DevApiServer() {
    }

    public static void startIfEnabled() {
        String port = System.getProperty("forge.devapi.port");
        if (port == null || port.isEmpty())
            port = System.getenv("FORGE_DEVAPI_PORT");
        if (port == null || port.isEmpty())
            return;
        final int p = Integer.parseInt(port.trim());
        // Started from a daemon thread: the HTTP dispatcher thread inherits daemon status and must not keep the JVM alive.
        Thread t = new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0);
                server.createContext("/", ex -> {
                    try {
                        String body;
                        try (InputStream in = ex.getRequestBody()) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            in.transferTo(bo);
                            body = bo.toString(StandardCharsets.UTF_8);
                        }
                        DevApi.Result r = DevApi.handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), body);
                        byte[] out = r.json.getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        ex.sendResponseHeaders(r.status, out.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(out);
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        ex.close();
                    }
                });
                server.setExecutor(Executors.newCachedThreadPool(r -> {
                    Thread w = new Thread(r, "devapi-worker");
                    w.setDaemon(true);
                    return w;
                }));
                server.start();
                System.out.println("[devapi] listening on 127.0.0.1:" + p);
            } catch (Exception e) {
                System.err.println("[devapi] could not start on port " + p + ": " + e);
            }
        }, "devapi-start");
        t.setDaemon(true);
        t.start();
    }
}
