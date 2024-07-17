package com.linagora.tmail.mailet;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

class OpenAIPlayground {
    @Test
    void test() {
        ChatLanguageModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .build();

        String answer = model.generate("Viết cho tôi bài thơ về mùa hè");
        System.out.println(answer);
    }
}
