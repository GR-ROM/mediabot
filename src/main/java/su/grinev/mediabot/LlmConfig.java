package su.grinev.mediabot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.grinev.mediabot.llm.LlmClient;

@Configuration
public class LlmConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public LlmClient llmClient(ObjectMapper mapper, AgentProperties props) {
        var llm = props.llm();
        return new LlmClient(mapper, llm.baseUrl(), llm.apiKey(), llm.authHeader(), llm.model(),
                llm.maxTokens(), llm.temperature(), llm.timeoutSeconds());
    }
}
