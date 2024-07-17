package com.linagora.tmail.mailet;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

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

    private ChatLanguageModel openAiModel;

    @Override
    public void service(Mail mail) throws MessagingException {
        try {
            String subject = Strings.nullToEmpty(mail.getMessage().getSubject());
            String body = getBodyContent(mail.getMessage());
            String prompt = subject + "\n" + body;

            String answer = openAiModel.generate(prompt);

            MimeMessage response = (MimeMessage) mail.getMessage()
                .reply(false);
            MailAddress sender = new MailAddress("gpt@linagora.com"); // todo make this address configurable
            response.setFrom(sender.asString());
            response.setText(answer);

            Collection<MailAddress> recipients = Stream.of(mail.getMaybeSender().asList(), mail.getRecipients())
                .flatMap(Collection::stream)
                .filter(recipient -> !recipient.equals(sender))
                .collect(Collectors.toUnmodifiableList());

            getMailetContext().sendMail(sender, recipients, response);

            mail.setState(Mail.GHOST); // likely should not dispose the asking mail as other recipients should still receive the asking mail
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getBodyContent(MimeMessage mimeMessage) throws MessagingException, IOException {
        return switch (mimeMessage.getContent()) {
            case String value -> Strings.nullToEmpty(value);
            case MimeMultipart mimeMultipart -> getTextFromMimeMultipart(mimeMultipart);
            // should handle InputStream content type too
            default -> throw new IllegalStateException("Unexpected value: " + mimeMessage.getContent());
        };
    }

    public String getTextFromMimeMultipart(MimeMultipart mimeMultipart) throws IOException, MessagingException {
        StringBuilder result = new StringBuilder();
        int count = mimeMultipart.getCount();
        for (int i = 0; i < count; i++) {
            MimeBodyPart bodyPart = (MimeBodyPart) mimeMultipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                result.append(bodyPart.getContent().toString());
            } else if (bodyPart.isMimeType("text/html")) {
                // Handle HTML content if necessary
            } else if (bodyPart.getContent() instanceof MimeMultipart) {
                result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
            }
        }
        return result.toString();
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
