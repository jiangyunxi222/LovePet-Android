package io.bandorilove.pet;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

final class WallpaperMedia {
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg",
            "webp", "image/webp", "mp4", "video/mp4");
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    static File find(Context context, String id) {
        if (!valid(id)) return null;
        for (String extension : TYPES.keySet()) {
            File file = DesktopRuntime.media(context, id + "." + extension);
            if (file.isFile()) return file;
        }
        return null;
    }

    static String mime(File file) {
        String name = file.getName();
        return TYPES.get(name.substring(name.lastIndexOf('.') + 1));
    }

    private static boolean valid(String id) { return id != null && id.matches("[a-z0-9-]{1,80}"); }

    static final class Writer {
        private final Context context;
        private FileOutputStream stream;
        private File pending;
        private File target;
        private int remaining;

        Writer(Context context) { this.context = context; }

        synchronized boolean begin(String id, String type, int bytes) {
            abort();
            if (!valid(id) || bytes <= 0 || bytes > MAX_BYTES) return false;
            String extension = TYPES.entrySet().stream().filter(entry -> entry.getValue().equals(type))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (extension == null) return false;
            try {
                target = DesktopRuntime.media(context, id + "." + extension);
                Files.createDirectories(target.getParentFile().toPath());
                pending = File.createTempFile("media-", ".pending", target.getParentFile());
                stream = new FileOutputStream(pending);
                remaining = bytes;
                return true;
            } catch (IOException error) {
                abort();
                return false;
            }
        }

        synchronized boolean append(String chunk) {
            if (stream == null || chunk == null || chunk.length() > 400_000) return false;
            try {
                byte[] bytes = Base64.decode(chunk, Base64.NO_WRAP);
                if (bytes.length > remaining) throw new IOException("Media exceeds its declared length");
                stream.write(bytes);
                remaining -= bytes.length;
                return true;
            } catch (IOException | IllegalArgumentException error) {
                abort();
                return false;
            }
        }

        synchronized boolean finish() {
            try {
                if (stream == null || remaining != 0) return false;
                stream.close();
                stream = null;
                return pending.renameTo(target);
            } catch (IOException error) {
                return false;
            } finally {
                abort();
            }
        }

        synchronized void abort() {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
                stream = null;
            }
            if (pending != null) {
                pending.delete();
                pending = null;
            }
            target = null;
        }
    }
}
