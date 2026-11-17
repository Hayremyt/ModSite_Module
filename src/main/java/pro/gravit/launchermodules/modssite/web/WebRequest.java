package pro.gravit.launchermodules.modssite.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import pro.gravit.launchermodules.modssite.Utils;
import pro.gravit.launchserver.socket.NettyConnectContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Удобная обёртка HTTP-запроса: IP (с учётом прокси), https-детект,
 * разбор пути, параметров, cookies и JSON-тела.
 */
public class WebRequest {
    public final ChannelHandlerContext ctx;
    public final FullHttpRequest msg;
    public final NettyConnectContext context;
    public final String method;
    public final String fullPath;      // например /mods/api/login
    public final String path;          // путь относительно точки монтирования: /api/login
    public final Map<String, String> query;
    private final boolean trustProxy;
    private JsonObject body;
    private Map<String, String> cookies;

    public WebRequest(ChannelHandlerContext ctx, FullHttpRequest msg, NettyConnectContext context,
                      String mountPath, boolean trustProxy) {
        this.ctx = ctx;
        this.msg = msg;
        this.context = context;
        this.trustProxy = trustProxy;
        this.method = msg.method() == null ? "GET" : msg.method().name();
        String uri = msg.uri();
        int q = uri.indexOf('?');
        this.fullPath = q >= 0 ? uri.substring(0, q) : uri;
        String mountless = fullPath.length() >= mountPath.length()
                ? fullPath.substring(mountPath.length()) : fullPath;
        this.path = mountless.startsWith("/") ? mountless : "/" + mountless;
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        this.query = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : decoder.parameters().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                query.put(e.getKey(), e.getValue().get(0));
            }
        }
    }

    public String ip() {
        String headerIp = null;
        if (trustProxy) {
            String xff = header("X-Forwarded-For");
            String xri = header("X-Real-IP");
            headerIp = xff != null && !xff.isBlank() ? xff : xri;
        }
        if (headerIp != null && !headerIp.isBlank()) {
            int comma = headerIp.indexOf(',');
            String ip = (comma > 0 ? headerIp.substring(0, comma) : headerIp).trim();
            // Длина IPv6-литерала максимум 45 символов; более длинное значение —
            // мусор (или попытка раздуть ключи бакетов/записей банов) — игнорируем
            if (!ip.isEmpty() && ip.length() <= 45) return ip;
        }
        return Utils.clientIp(ctx.channel(), context == null ? null : context.ip);
    }

    /** Соединение пришло по HTTPS? (прямой TLS или доверенный X-Forwarded-Proto) */
    public boolean isHttps() {
        if (ctx.pipeline().get(SslHandler.class) != null) {
            return true;
        }
        if (trustProxy) {
            String proto = header("X-Forwarded-Proto");
            return proto != null && proto.equalsIgnoreCase("https");
        }
        return false;
    }

    public boolean isHttpViaProxy() {
        if (trustProxy) {
            String proto = header("X-Forwarded-Proto");
            return proto != null && proto.equalsIgnoreCase("http");
        }
        return false;
    }

    public String header(String name) {
        return msg.headers().get(name);
    }

    public Map<String, String> cookies() {
        if (cookies == null) {
            cookies = new HashMap<>();
            String header = msg.headers().get(HttpHeaderNames.COOKIE);
            if (header != null) {
                for (String part : header.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                    }
                }
            }
        }
        return cookies;
    }

    /** JSON-тело запроса (null, если тело не JSON или пустое). */
    public JsonObject body() {
        if (body != null) return body;
        String contentType = msg.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null && contentType.contains("application/json")) {
            try {
                ByteBuf content = msg.content();
                if (content.isReadable()) {
                    byte[] bytes = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), bytes);
                    JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                    if (element.isJsonObject()) {
                        body = element.getAsJsonObject();
                        return body;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        body = new JsonObject();
        return body;
    }

    public String bodyString(String field) {
        var e = body().get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** JSON-массив строк из тела запроса (пустой список, если поля нет или тип другой). */
    public java.util.List<String> bodyArray(String field) {
        var e = body().get(field);
        if (e == null || !e.isJsonArray()) return java.util.List.of();
        java.util.List<String> result = new java.util.ArrayList<>();
        for (JsonElement element : e.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) result.add(value.trim());
            }
        }
        return result;
    }

    public String query(String name) {
        return query.get(name);
    }

    public boolean isPost() {
        return HttpMethod.POST.name().equalsIgnoreCase(method);
    }
}
