package dev.leonetic.features.modules.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.leonetic.event.impl.network.ChatEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class TranslatorModule extends Module {
    public static final java.util.Map<String, String> translationCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, String>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                return size() > 100;
            }
        }
    );

    public static boolean bypass = false;

    public final Setting<Boolean> translateOwn = bool("Translate Own Chat", true).setPage("General");

    public TranslatorModule() {
        super("Translator", "Translates all outgoing chat messages into Swedish.", Category.PLAYER);
    }

    @Subscribe
    public void onChatSent(ChatEvent event) {
        if (nullCheck()) return;
        if (!translateOwn.getValue()) return;
        String message = event.getMessage();
        
        if (message.startsWith("/") || message.startsWith(".")) {
            return;
        }

        // Ignore messages containing [] (e.g. [inaktiverad], [VIP], [Staff] etc.)
        if (containsBrackets(message)) {
            return; // Send original message without translating
        }

        // Cancel the original event so it isn't sent synchronously
        event.cancel();

        // Run translation asynchronously to prevent game thread freeze
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String translated = translateToSwedish(message);
            String toSend = (translated != null && !translated.isEmpty()) ? translated : message;
            
            if (translated != null && !translated.isEmpty() && !translated.equalsIgnoreCase(message)) {
                translationCache.put(translated, message);
            }
            
            mc.execute(() -> {
                if (mc.getConnection() != null) {
                    try {
                        bypass = true;
                        mc.getConnection().sendChat(toSend);
                    } finally {
                        bypass = false;
                    }
                }
            });
        });
    }

    // Stronger bracket detection
    private boolean containsBrackets(String text) {
        if (text == null || text.isEmpty()) return false;
        String trimmed = text.trim();
        return trimmed.contains("[") || trimmed.contains("]");
    }

    private String translateToSwedish(String text) {
        try {
            String encoded = URLEncoder.encode(text, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=sv&dt=t&q=" + encoded;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return text;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            JsonArray outerArray = JsonParser.parseString(response.toString()).getAsJsonArray();
            JsonArray segments = outerArray.get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                translated.append(segments.get(i).getAsJsonArray().get(0).getAsString());
            }
            return translated.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return text;
        }
    }

    public static class TranslationResult {
        public final String translatedText;
        public final String detectedLanguage;

        public TranslationResult(String translatedText, String detectedLanguage) {
            this.translatedText = translatedText;
            this.detectedLanguage = detectedLanguage;
        }
    }

    public static TranslationResult translateToEnglish(String text) {
        try {
            String encoded = URLEncoder.encode(text, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=" + encoded;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            JsonArray outerArray = JsonParser.parseString(response.toString()).getAsJsonArray();
            JsonArray segments = outerArray.get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                translated.append(segments.get(i).getAsJsonArray().get(0).getAsString());
            }

            String detectedLang = "auto";
            if (outerArray.size() > 2 && !outerArray.get(2).isJsonNull()) {
                detectedLang = outerArray.get(2).getAsString();
            }

            return new TranslationResult(translated.toString(), detectedLang);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}