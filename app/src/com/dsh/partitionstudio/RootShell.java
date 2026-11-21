package com.dsh.partitionstudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RootShell {
    interface Progress {
        void onBytes(long copied, long total);
    }

    static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) {
            this.exit = exit;
            this.output = output == null ? "" : output;
        }
        boolean ok() { return exit == 0; }
    }

    private RootShell() {}

    static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final String[] SU_PATHS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
    };

    static Process start(String command) throws IOException {
        IOException last = null;
        for (String su : SU_PATHS) {
            try {
                ProcessBuilder pb = new ProcessBuilder(su, "-c", command);
                pb.redirectErrorStream(true);
                return pb.start();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("su not found") : last;
    }

    /** Starts a root process whose stdout is raw binary data, never merged with diagnostics. */
    static Process startBinary(String command) throws IOException {
        IOException last = null;
        for (String su : SU_PATHS) {
            try {
                ProcessBuilder pb = new ProcessBuilder(su, "-c", command);
                pb.redirectErrorStream(false);
                pb.redirectError(new java.io.File("/dev/null"));
                return pb.start();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("su not found") : last;
    }

    static Result run(String command) throws IOException, InterruptedException {
        Process p = start(command);
        String out = readAll(p.getInputStream());
        int code = p.waitFor();
        return new Result(code, out);
    }

    static Result runWithProgress(String command, long expected, Progress progress)
            throws IOException, InterruptedException {
        Process p = start(command);
        StringBuilder all = new StringBuilder();
        StringBuilder line = new StringBuilder();
        Pattern bytesPattern = Pattern.compile("(?i)(\\d+)\\s+bytes");
        Pattern progressPattern = Pattern.compile("__PROG__:(\\d+)");
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        long lastReported = -1;
        long lastTime = 0L;
        while ((n = in.read(buf)) != -1) {
            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
            for (int i = 0; i < chunk.length(); i++) {
                char ch = chunk.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    if (line.length() > 0) {
                        String s = line.toString();
                        if (all.length() < 200_000) all.append(s).append('\n');
                        Matcher pm = progressPattern.matcher(s);
                        Matcher bm = bytesPattern.matcher(s);
                        long copied = -1;
                        if (pm.find()) {
                            try { copied = Long.parseLong(pm.group(1)); } catch (NumberFormatException ignored) {}
                        } else if (bm.find()) {
                            try { copied = Long.parseLong(bm.group(1)); } catch (NumberFormatException ignored) {}
                        }
                        if (copied >= 0 && progress != null) {
                            long now = System.currentTimeMillis();
                            if (lastReported < 0
                                    || copied - lastReported > 8L * 1024 * 1024
                                    || now - lastTime > 280
                                    || (expected > 0 && copied >= expected)) {
                                lastReported = copied;
                                lastTime = now;
                                progress.onBytes(copied, expected);
                            }
                        }
                        line.setLength(0);
                    }
                } else if (line.length() < 8192) {
                    line.append(ch);
                }
            }
        }
        if (line.length() > 0 && all.length() < 200_000) all.append(line);
        int code = p.waitFor();
        return new Result(code, all.toString());
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (out.size() < 400_000) out.write(buf, 0, Math.min(n, 400_000 - out.size()));
            }
            return out.toString("UTF-8");
        }
    }

    static String readText(InputStream in, int max) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[2048];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = reader.read(buf)) != -1 && sb.length() < max) {
                sb.append(buf, 0, Math.min(n, max - sb.length()));
            }
            return sb.toString();
        }
    }
}
