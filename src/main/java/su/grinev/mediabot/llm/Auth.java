package su.grinev.mediabot.llm;

import lombok.experimental.UtilityClass;

/** Resolves the Authorization header value shared by the LLM and vision clients. */
@UtilityClass
public class Auth {

    /**
     * An explicit {@code authHeader} wins; otherwise a non-blank {@code apiKey} becomes a Bearer
     * token. Blank or null yields null, meaning "send no Authorization header".
     */
    public static String resolve(String apiKey, String authHeader) {
        if (authHeader != null && !authHeader.isBlank()) {
            return authHeader.strip();
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return "Bearer " + apiKey.strip();
        }
        return null;
    }
}
