package io.bandorilove.lovepet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

final class WallpaperFrame {
    private final AtomicFile file;

    WallpaperFrame(File cache) {
        file = new AtomicFile(new File(cache, "wallpaper-frame-v1"));
    }

    Bitmap load(String model, int width, int height) {
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (!input.readUTF().equals(model)) return null;
            float aspect = input.readFloat();
            if (Math.abs(aspect - (float) width / height) > aspect * .04f) return null;
            return BitmapFactory.decodeStream(input);
        } catch (FileNotFoundException absent) {
            return null;
        } catch (IOException error) {
            Log.w("BandoriLovePet", "Could not read last wallpaper frame", error);
            return null;
        }
    }

    void save(String model, Bitmap bitmap) {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeUTF(model);
            output.writeFloat((float) bitmap.getWidth() / bitmap.getHeight());
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 89, output)) throw new IOException("JPEG encoding failed");
            file.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) file.failWrite(stream);
            Log.w("BandoriLovePet", "Could not cache last wallpaper frame", error);
        } finally {
            bitmap.recycle();
        }
    }
}
