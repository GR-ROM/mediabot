package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one string that decides whether anything reaches Telegram at all.
 *
 * <p>Worth pinning because getting it wrong fails in the most misleading way available: every call
 * returns [404] Not Found, which reads as a revoked token, and the URL that produced it is never
 * printed anywhere.
 */
class TelegramApiRootTest {

    @Test
    void theBareHostGetsTheSuffixTheLibraryExpects() {
        assertEquals("https://api.telegram.org/bot",
                TelegramConfig.apiRoot("https://api.telegram.org"));
    }

    @Test
    void aTrailingSlashIsNotMistakenForTheSuffix() {
        assertEquals("https://api.telegram.org/bot",
                TelegramConfig.apiRoot("https://api.telegram.org/"));
        assertEquals("https://api.telegram.org/bot",
                TelegramConfig.apiRoot("https://api.telegram.org///"));
    }

    @Test
    void aBaseThatAlreadySaysBotIsLeftAlone() {
        assertEquals("https://api.telegram.org/bot",
                TelegramConfig.apiRoot("https://api.telegram.org/bot"));
        assertEquals("https://api.telegram.org/bot",
                TelegramConfig.apiRoot("https://api.telegram.org/bot/"));
    }

    @Test
    void aLocalBotApiServerIsTreatedTheSameWay() {
        assertEquals("http://localhost:8081/bot", TelegramConfig.apiRoot("http://localhost:8081"));
        assertEquals("http://localhost:8081/bot", TelegramConfig.apiRoot("  http://localhost:8081  "));
    }
}
