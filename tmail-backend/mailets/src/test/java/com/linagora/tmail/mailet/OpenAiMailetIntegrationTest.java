/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package com.linagora.tmail.mailet;

import static com.linagora.tmail.mailet.OpenAIMailetV1.API_KEY_PARAMETER_NAME;
import static org.apache.james.mailets.configuration.CommonProcessors.ERROR_REPOSITORY;
import static org.apache.james.mailets.configuration.Constants.DEFAULT_DOMAIN;
import static org.apache.james.mailets.configuration.Constants.LOCALHOST_IP;
import static org.apache.james.mailets.configuration.Constants.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;

import jakarta.mail.internet.MimeMessage;

import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.mailets.TemporaryJamesServer;
import org.apache.james.mailets.configuration.CommonProcessors;
import org.apache.james.mailets.configuration.Constants;
import org.apache.james.mailets.configuration.MailetConfiguration;
import org.apache.james.mailets.configuration.MailetContainer;
import org.apache.james.mailets.configuration.ProcessorConfiguration;
import org.apache.james.modules.MailboxProbeImpl;
import org.apache.james.modules.protocols.ImapGuiceProbe;
import org.apache.james.modules.protocols.SmtpGuiceProbe;
import org.apache.james.rate.limiter.memory.MemoryRateLimiterModule;
import org.apache.james.transport.mailets.ToRepository;
import org.apache.james.transport.matchers.All;
import org.apache.james.transport.matchers.RecipientIs;
import org.apache.james.util.MimeMessageUtil;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.SMTPMessageSender;
import org.apache.james.utils.TestIMAPClient;
import org.apache.mailet.base.test.FakeMail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class OpenAiMailetIntegrationTest {
    private static final int SEARCH_LIMIT_DEFAULT = 100;
    private static final String SENDER = "sender@" + DEFAULT_DOMAIN;
    private static final String SENDER2 = "sender2@" + DEFAULT_DOMAIN;
    private static final String CHAT_GPT_MAIL = "gpt@linagora.com";

    private TemporaryJamesServer jamesServer;
    private MailboxProbeImpl mailboxProbe;

    @RegisterExtension
    public TestIMAPClient imapClient = new TestIMAPClient();
    @RegisterExtension
    public SMTPMessageSender messageSender = new SMTPMessageSender(DEFAULT_DOMAIN);

    @BeforeEach
    void setup(@TempDir File temporaryFolder) throws Exception {
        MailetContainer.Builder mailetContainer = TemporaryJamesServer.simpleMailetContainerConfiguration()
            .putProcessor(ProcessorConfiguration.error()
                .enableJmx(false)
                .addMailet(MailetConfiguration.builder()
                    .matcher(All.class)
                    .mailet(ToRepository.class)
                    .addProperty("repositoryPath", ERROR_REPOSITORY.asString()))
                .build())
            .putProcessor(ProcessorConfiguration.transport()
                .addMailet(MailetConfiguration.builder()
                    .matcher(RecipientIs.class)
                    .matcherCondition(CHAT_GPT_MAIL)
                    .mailet(OpenAIMailetV1.class)
                    .addProperty(API_KEY_PARAMETER_NAME, "demo")
                    .build())
                .addMailetsFrom(CommonProcessors.transport()));

        jamesServer = TemporaryJamesServer.builder()
            .withMailetContainer(mailetContainer)
            .withOverrides(new MemoryRateLimiterModule())
            .build(temporaryFolder);
        jamesServer.start();

        jamesServer.getProbe(DataProbeImpl.class).fluent()
            .addDomain(DEFAULT_DOMAIN)
            .addUser(SENDER, PASSWORD)
            .addUser(SENDER2, PASSWORD);

        mailboxProbe = jamesServer.getProbe(MailboxProbeImpl.class);
    }

    @AfterEach
    void tearDown() {
        jamesServer.shutdown();
    }

    private void awaitFirstMessage(String username) throws IOException {
        imapClient.connect(LOCALHOST_IP, jamesServer.getProbe(ImapGuiceProbe.class).getImapPort())
            .login(username, PASSWORD)
            .select(TestIMAPClient.INBOX)
            .awaitMessage(Constants.awaitAtMostOneMinute);
    }

    @Test
    void shouldReturnAIGenMailToSender() throws Exception {
        messageSender.connect(LOCALHOST_IP, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(SENDER, PASSWORD)
            .sendMessage(FakeMail.builder()
                .name("name")
                .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                    .addToRecipient(CHAT_GPT_MAIL)
                    .setSender(SENDER)
                    .setSubject("How can I cook an egg?")
                    .setText("I do not know how to cook an egg. Please help me."))
                .sender(SENDER)
                .recipient(CHAT_GPT_MAIL));

        awaitFirstMessage(SENDER);

        String responseMail = imapClient.readFirstMessage();
        System.out.println(responseMail);
        assertThat(responseMail).contains("egg");

        MimeMessage mimeMessage = MimeMessageUtil.mimeMessageFromString(responseMail);
        assertThat(mimeMessage.getHeader("To")[0]).isEqualTo(SENDER);
    }

    // todo test for multipart content type

    // todo test make sure no reply mail to gpt address, otherwise we would have a loop

    // todo should reply to all recipients (except gpt one)

    // todo not send mail to the gpt address should work normally
}
