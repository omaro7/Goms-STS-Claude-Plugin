package kr.co.goms.sts.claude.approval;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP/1.1 server on plain sockets (keep-alive, Content-Length and chunked request bodies).
 * Used instead of com.sun.net.httpserver, which is not part of the Java SE API and is not reliably
 * visible to OSGi bundles. Loopback only; intended for a single local client (Claude Code).
 */
final class MiniHttpServer implements AutoCloseable {

    static final int MAX_HEADER_BYTES = 64 * 1024;
    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    record Request(String method, String path, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }

        Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    private final Handler handler;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean closed;

    MiniHttpServer(Handler handler) {
        this.handler = handler;
    }

    synchronized void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "goms-mcp-http");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    socket.close();
                    continue;
                }
                connections.add(socket);
                executor.execute(() -> serve(socket));
            } catch (IOException e) {
                if (closed) return;
            } catch (RuntimeException e) {     // executor shut down
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (!closed) {
                Request request;
                try {
                    request = readRequest(in, out);
                } catch (HttpError e) {
                    write(out, new Response(e.status, "text/plain", e.getMessage()), false);
                    return;
                }
                if (request == null) return;                       // client closed the connection
                boolean keepAlive = !"close".equalsIgnoreCase(request.header("connection"));
                Response response;
                try {
                    response = handler.handle(request);
                } catch (Exception e) {
                    response = new Response(500, "text/plain", "Internal error: " + e);
                }
                write(out, response, keepAlive);
                if (!keepAlive) return;
            }
        } catch (SocketException e) {
            // connection reset / server closing
        } catch (IOException e) {
            // ignore: best effort local server
        } finally {
            connections.remove(socket);
        }
    }

    private static Request readRequest(InputStream in, OutputStream out) throws IOException, HttpError {
        String requestLine = readLine(in, true);
        if (requestLine == null) return null;
        while (requestLine.isEmpty()) {                         // tolerate stray CRLF between requests
            requestLine = readLine(in, true);
            if (requestLine == null) return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3 || !parts[2].startsWith("HTTP/1.")) throw new HttpError(400, "Bad request line");

        Map<String, String> headers = new LinkedHashMap<>();
        int total = requestLine.length();
        while (true) {
            String line = readLine(in, false);
            if (line == null) throw new HttpError(400, "Unexpected end of headers");
            if (line.isEmpty()) break;
            total += line.length();
            if (total > MAX_HEADER_BYTES) throw new HttpError(431, "Headers too large");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpError(400, "Bad header");
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }

        if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        byte[] body;
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(in);
        } else {
            String cl = headers.get("content-length");
            int length;
            try {
                length = cl == null ? 0 : Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new HttpError(400, "Bad Content-Length");
            }
            if (length < 0 || length > MAX_BODY_BYTES) throw new HttpError(413, "Body too large");
            body = in.readNBytes(length);
            if (body.length != length) throw new HttpError(400, "Truncated body");
        }
        return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], headers, body);
    }

    private static byte[] readChunked(InputStream in) throws IOException, HttpError {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, false);
            if (sizeLine == null) throw new HttpError(400, "Truncated chunk");
            int semi = sizeLine.indexOf(';');
            int size;
            try {
                size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
            } catch (NumberFormatException e) {
                throw new HttpError(400, "Bad chunk size");
            }
            if (size == 0) {
                String trailer;
                do { trailer = readLine(in, false); } while (trailer != null && !trailer.isEmpty());
                return buf.toByteArray();
            }
            if (buf.size() + size > MAX_BODY_BYTES) throw new HttpError(413, "Body too large");
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) throw new HttpError(400, "Truncated chunk");
            buf.write(chunk);
            readLine(in, false);                                // CRLF after chunk data
        }
    }

    /** Reads one CRLF/LF terminated ASCII line; null at EOF before any byte. */
    private static String readLine(InputStream in, boolean eofAllowed) throws IOException, HttpError {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (sb.length() == 0 && eofAllowed) return null;
                if (sb.length() == 0) return null;
                throw new HttpError(400, "Unexpected end of stream");
            }
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_HEADER_BYTES) throw new HttpError(431, "Line too long");
        }
    }

    private static void write(OutputStream out, Response r, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.body.length > 0 && r.contentType != null) head.append("Content-Type: ").append(r.contentType).append("; charset=utf-8\r\n");
        head.append("Content-Length: ").append(r.body.length).append("\r\n");
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        for (Map.Entry<String, String> h : r.headers.entrySet()) head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(r.body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            case 431 -> "Request Header Fields Too Large";
            default -> status >= 500 ? "Internal Server Error" : "Status";
        };
    }

    @Override
    public synchronized void close() {
        closed = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { /* closing */ }
        for (Socket s : connections) {
            try { s.close(); } catch (IOException ignored) { /* closing */ }
        }
        connections.clear();
        if (executor != null) executor.shutdownNow();
    }

    private static final class HttpError extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
