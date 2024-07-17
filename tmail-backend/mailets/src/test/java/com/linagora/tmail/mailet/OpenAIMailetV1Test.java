package com.linagora.tmail.mailet;

import static com.linagora.tmail.mailet.OpenAIMailetV1.API_KEY_PARAMETER_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.james.core.MailAddress;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.server.core.MailImpl;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetContext;
import org.apache.mailet.base.MailAddressFixture;
import org.apache.mailet.base.test.FakeMailetConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAIMailetV1Test {
    private static final MailAddress SENDER = MailAddressFixture.ANY_AT_JAMES;

    private OpenAIMailetV1 testee;
    private MailetContext mailetContext;

    @BeforeEach
    void setUp() {
        testee = new OpenAIMailetV1();
        mailetContext = mock(MailetContext.class);
    }

    // todo add more unit tests

    @Test
    void test() throws Exception {
        testee.init(FakeMailetConfig
            .builder()
            .setProperty(API_KEY_PARAMETER_NAME, "demo")
            .mailetContext(mailetContext)
            .build());

        Mail mail = MailImpl.builder()
            .name("mail-id")
            .sender(SENDER)
            .addRecipient(MailAddressFixture.OTHER_AT_JAMES)
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                .setSubject("How can I cook an egg?")
                .setText("I do not know how to cook an egg. Please help me.")
                .build())
            .build();

        testee.service(mail);

        verify(mailetContext).sendMail(eq(MailAddress.nullSender()), any(), any());
    }
}
