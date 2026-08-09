package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import su.grinev.mediabot.AgentProperties;

@Configuration
@Slf4j
public class TelegramConfig {

    /**
     * The bot client.
     *
     * <p>Always created, even with no token: everything except the transport works without one, and
     * a context that will not build for want of a BotFather token makes the whole application
     * untestable. {@link BotRegistration} is what refuses to go online.
     */
    @Bean
    @Profile("!console")
    public MediaBot mediaBot(AgentProperties props) {
        var telegram = props.telegram();
        var options = new DefaultBotOptions();

        // This one line is what raises the upload ceiling from 50 MB to 2 GB, by pointing the client
        // at a telegram-bot-api running next to the bot instead of at Telegram's public API.
        if (telegram.baseUrl() != null && !telegram.baseUrl().isBlank()) {
            options.setBaseUrl(apiRoot(telegram.baseUrl()));
        }
        log.info("Telegram API at {}, upload limit {} MB, token {}",
                options.getBaseUrl(), telegram.maxUploadBytes() / (1024 * 1024),
                masked(telegram.token()));
        return new MediaBot(options, telegram.token(), telegram.username());
    }

    /**
     * Enough of the token to tell it apart from an empty one or an unresolved placeholder, and not
     * enough to use. Worth logging because every way of getting it wrong ends in the same [404] from
     * a URL nothing prints, and the shape of the string is what tells the three apart.
     */
    private static String masked(String token) {
        if (token == null || token.isBlank()) {
            return "MISSING";
        }
        String clean = token.strip();
        String shape = clean.length() <= 12
                ? clean
                : clean.substring(0, 6) + "…" + clean.substring(clean.length() - 4);
        return "%s (%d chars)".formatted(shape, clean.length());
    }

    /**
     * The base the library concatenates a token onto, which is not the host: its own default is
     * {@code https://api.telegram.org/bot}, with the word {@code bot} on the end and no slash after
     * it. Handing it the bare host instead produces {@code https://api.telegram.org/<token>/getMe},
     * and every call comes back [404] Not Found — which reads like a dead token and is not one.
     *
     * <p>So the host is what goes in the configuration and the suffix is added here. A local
     * telegram-bot-api serves the same {@code /bot<token>/} paths, so this is right for both.
     */
    static String apiRoot(String url) {
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/bot") ? trimmed : trimmed + "/bot";
    }
}
