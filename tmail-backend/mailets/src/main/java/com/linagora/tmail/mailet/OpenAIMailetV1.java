package com.linagora.tmail.mailet;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.apache.james.core.MailAddress;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetException;
import org.apache.mailet.base.GenericMailet;

import com.google.common.base.Strings;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

// todo add JavaDoc
public class OpenAIMailetV1 extends GenericMailet {
    public static final String API_KEY_PARAMETER_NAME = "apiKey";
    public static final boolean REPLY_TO_ALL_RECIPIENTS = true;

    private ChatLanguageModel openAiModel;

    @Override
    public void service(Mail mail) throws MessagingException {
        // todo set subject, from, to headers...
        MimeMessage response = (MimeMessage) mail.getMessage()
            .reply(REPLY_TO_ALL_RECIPIENTS);

        try {
            String subject = Strings.nullToEmpty(mail.getMessage().getSubject());
            String body = Strings.nullToEmpty((String) mail.getMessage().getContent());
            String prompt = subject + "\n" + body;

            String answer = openAiModel.generate(prompt);
            response.setText(answer);

            // todo change to gpt@domain.tld
            MailAddress sender = new MailAddress("gpt@linagora.com");
            Collection<MailAddress> recipients = Stream.of(mail.getMaybeSender().asList(), mail.getRecipients())
                .flatMap(Collection::stream)
                .filter(recipient -> !recipient.equals(sender))
                .collect(Collectors.toUnmodifiableList());

            getMailetContext()
                .sendMail(sender, recipients, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void init() throws MessagingException {
        String openAiApiKey = getMailetConfig().getInitParameter(API_KEY_PARAMETER_NAME);
        if (Strings.isNullOrEmpty(openAiApiKey)) {
            throw new MailetException("No value for " + API_KEY_PARAMETER_NAME + " parameter was provided.");
        }

        this.openAiModel = OpenAiChatModel.builder()
            .apiKey(getMailetConfig().getInitParameter("apiKey"))
            .build();
    }

    @Override
    public String getMailetName() {
        return "OpenAIMailetV1";
    }
}
