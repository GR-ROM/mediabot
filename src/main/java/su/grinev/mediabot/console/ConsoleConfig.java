package su.grinev.mediabot.console;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import su.grinev.mediabot.telegram.MediaBot;

@Configuration
@Profile("console")
public class ConsoleConfig {

    @Bean
    public MediaBot consoleBot() {
        return new ConsoleBot();
    }
}
