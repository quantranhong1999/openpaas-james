package com.linagora.tmail.encrypted;

import static org.apache.james.jmap.JMAPTestingConstants.ALICE;
import static org.apache.james.jmap.JMAPTestingConstants.BOB;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.inmemory.manager.InMemoryIntegrationResources;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.MessageResultIterator;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.tmail.pgp.Decrypter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import reactor.core.publisher.Mono;

public class InMemoryEncryptedMessageManagerTest {
    private EncryptedMessageManager testee;
    private MailboxManager mailboxManager;
    private MessageManager messageManager;
    private KeystoreManager keystoreManager;
    private MailboxSession session;
    private MailboxPath path;
    private Message message;

    @BeforeAll
    static void setUpAll() throws Exception {
        String bouncyCastleProviderClassName = "org.bouncycastle.jce.provider.BouncyCastleProvider";
        Security.addProvider((Provider)Class.forName(bouncyCastleProviderClassName).getDeclaredConstructor().newInstance());
    }

    @BeforeEach
    void setUp() throws Exception {
        mailboxManager = InMemoryIntegrationResources.defaultResources().getMailboxManager();
        session = mailboxManager.createSystemSession(BOB);
        path = MailboxPath.inbox(session);
        MailboxId mailboxId = mailboxManager.createMailbox(path, session).get();
        messageManager = mailboxManager.getMailbox(mailboxId, session);

        keystoreManager = new InMemoryKeystoreManager();
        testee = new EncryptedMessageManager(messageManager, keystoreManager);

        message = Message.Builder
            .of()
            .setSubject("test")
            .setSender(BOB.asString())
            .setFrom(BOB.asString())
            .setTo(ALICE.asString())
            .setBody("testmail", StandardCharsets.UTF_8)
            .build();
    }

    @Test
    void commandAppendShouldEncryptMessage() throws Exception {
        byte[] keyBytes = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.pub").readAllBytes();
        Mono.from(keystoreManager.save(BOB, keyBytes)).block();

        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.BODY_CONTENT, session);
        MessageResult result = messages.next();

        assertThat(new String(result.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .doesNotContain("testmail");
    }

    @Test
    void commandAppendShouldEncryptMessageWithMultipleKeys() throws Exception {
        byte[] keyBytes1 = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.pub").readAllBytes();
        byte[] keyBytes2 = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg2.pub").readAllBytes();
        Mono.from(keystoreManager.save(BOB, keyBytes1)).block();
        Mono.from(keystoreManager.save(BOB, keyBytes2)).block();

        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.BODY_CONTENT, session);
        MessageResult result = messages.next();

        assertThat(new String(result.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .doesNotContain("testmail");
    }

    @Test
    void commandAppendedWithSingleKeyShouldBeDecryptable() throws Exception {
        byte[] keyBytes = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.pub").readAllBytes();
        Mono.from(keystoreManager.save(BOB, keyBytes)).block();

        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.FULL_CONTENT, session);
        MessageResult result = messages.next();

        Body body = new DefaultMessageBuilder().parseMessage(result.getFullContent().getInputStream()).getBody();
        Multipart encryptedMultiPart = (Multipart) body;
        Body encryptedBodyPart = encryptedMultiPart.getBodyParts().get(1).getBody();
        ByteArrayOutputStream encryptedBodyBytes = new ByteArrayOutputStream();
        new DefaultMessageWriter().writeBody(encryptedBodyPart, encryptedBodyBytes);

        byte[] decryptedPayload = Decrypter
            .forKey(ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.private"), "123456".toCharArray())
            .decrypt(new ByteArrayInputStream(encryptedBodyBytes.toByteArray()))
            .readAllBytes();

        assertThat(new String(decryptedPayload, StandardCharsets.UTF_8))
            .contains("testmail");
    }

    @Test
    void commandAppendedWithMultipleKeysShouldBeDecryptable() throws Exception {
        byte[] keyBytes1 = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.pub").readAllBytes();
        byte[] keyBytes2 = ClassLoader.getSystemClassLoader().getResourceAsStream("gpg2.pub").readAllBytes();
        Mono.from(keystoreManager.save(BOB, keyBytes1)).block();
        Mono.from(keystoreManager.save(BOB, keyBytes2)).block();

        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.FULL_CONTENT, session);
        MessageResult result = messages.next();

        Body body = new DefaultMessageBuilder().parseMessage(result.getFullContent().getInputStream()).getBody();
        Multipart encryptedMultiPart = (Multipart) body;
        Body encryptedBodyPart = encryptedMultiPart.getBodyParts().get(1).getBody();
        ByteArrayOutputStream encryptedBodyBytes = new ByteArrayOutputStream();
        new DefaultMessageWriter().writeBody(encryptedBodyPart, encryptedBodyBytes);

        byte[] decryptedPayload = Decrypter
            .forKey(ClassLoader.getSystemClassLoader().getResourceAsStream("gpg.private"), "123456".toCharArray())
            .decrypt(new ByteArrayInputStream(encryptedBodyBytes.toByteArray()))
            .readAllBytes();

        assertThat(new String(decryptedPayload, StandardCharsets.UTF_8))
            .contains("testmail");
    }

    @Test
    void commandAppendShouldThrowWhenNoPublicKey() throws Exception {
        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.BODY_CONTENT, session);
        MessageResult result = messages.next();

        assertThat(new String(result.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .contains("testmail");
    }

    @Test
    void commandAppendShouldNotEncryptWhenNoPublicKey() throws Exception {
        testee.appendMessage(MessageManager.AppendCommand.from(message), session);

        MessageResultIterator messages = mailboxManager.getMailbox(path, session)
            .getMessages(MessageRange.all(), FetchGroup.BODY_CONTENT, session);
        MessageResult result = messages.next();

        assertThat(new String(result.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .contains("testmail");
    }
}
