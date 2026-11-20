package io.bandorilove.lovepet;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class AppTheme {
    private static JSONArray themes;
    final int accent;
    final int ink;
    final int line;
    final int surface;

    static synchronized JSONArray definitions(Context context) {
        if (themes != null) return themes;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.app_themes), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) json.append(line);
            themes = new JSONArray(json.toString());
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Cannot read the bundled app themes", error);
        }
        return themes;
    }

    AppTheme(Context context) {
        JSONArray all = definitions(context);
        String current = DesktopRuntime.read(context, "theme");
        JSONArray theme = all.optJSONArray(0);
        for (int i = 0; i < all.length(); i++) {
            JSONArray candidate = all.optJSONArray(i);
            if (candidate.optString(0).equals(current)) {
                theme = candidate;
                break;
            }
        }
        accent = Color.parseColor(theme.optString(2));
        int tint = Color.parseColor(theme.optString(3));
        ink = mix(accent, .35, 0xff302936);
        line = mix(accent, .16, 0xfff6f4f7);
        surface = mix(tint, .22, Color.WHITE);
    }

    private static int mix(int first, double weight, int second) {
        return Color.rgb(
                mixChannel(Color.red(first), weight, Color.red(second)),
                mixChannel(Color.green(first), weight, Color.green(second)),
                mixChannel(Color.blue(first), weight, Color.blue(second)));
    }

    private static int mixChannel(int first, double weight, int second) {
        return (int) Math.round(first * weight + second * (1 - weight));
    }
}
