package su.grinev.mediabot.console;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

@UtilityClass
public class ConsoleText {

    private static final Map<String, String> SUBSTITUTIONS = substitutions();

    public String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String plain = text;
        for (var entry : SUBSTITUTIONS.entrySet()) {
            if (plain.contains(entry.getKey())) {
                plain = plain.replace(entry.getKey(), entry.getValue());
            }
        }
        return plain.replaceAll("(\\R[ \t]*){3,}", "\n\n").strip();
    }

    public String shorten(String text, int limit) {
        String plain = plain(text).replaceAll("\\s+", " ");
        return plain.length() <= limit ? plain : plain.substring(0, Math.max(1, limit - 3)) + "...";
    }

    private Map<String, String> substitutions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("‘", "'");
        map.put("’", "'");
        map.put("‚", "'");
        map.put("‛", "'");
        map.put("“", "\"");
        map.put("”", "\"");
        map.put("„", "\"");
        map.put("«", "\"");
        map.put("»", "\"");
        map.put("–", "-");
        map.put("—", "-");
        map.put("‒", "-");
        map.put("‑", "-");
        map.put("−", "-");
        map.put("…", "...");
        map.put("•", "*");
        map.put("·", "-");
        map.put(" ", " ");
        map.put(" ", " ");
        map.put(" ", " ");
        map.put(" ", " ");
        map.put("​", "");
        map.put("﻿", "");
        map.put("▰", "#");
        map.put("▱", ".");
        map.put("⏳", "");
        map.put("✅", "ok");
        map.put("❌", "failed:");
        map.put("📦", "");
        map.put("🚫", "");
        map.put("⚠️", "!");
        return map;
    }
}
