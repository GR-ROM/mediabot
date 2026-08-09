package su.grinev.mediabot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class MediaBotApplication {

    public static void main(String[] args) {
        // The console defaults to the Windows OEM codepage, which mangles every non-ASCII
        // character in a log line — and video titles are full of them.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        // No System.exit around run(): it returns as soon as the context is up, and exiting there
        // killed the process the moment it was ready to work. What keeps the JVM alive instead is
        // JobWorkers' non-daemon threads — the process should live exactly as long as it can run
        // jobs. Ctrl-C closes the context through Boot's shutdown hook, which stops them.
        var app = new SpringApplication(MediaBotApplication.class);
        app.setLogStartupInfo(false);
        app.run(args);
    }
}
