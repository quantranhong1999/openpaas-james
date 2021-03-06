package com.linagora.tmail.encrypted

import java.io.InputStream
import java.util
import java.util.Date

import com.linagora.tmail.pgp.Encrypter
import javax.inject.Inject
import javax.mail.Flags
import org.apache.james.mailbox.MessageManager.{AppendCommand, AppendResult, MailboxMetaData}
import org.apache.james.mailbox.model.{ComposedMessageIdWithMetaData, FetchGroup, Mailbox, MailboxACL, MailboxCounters, MailboxId, MailboxPath, MessageRange, MessageResultIterator, SearchQuery}
import org.apache.james.mailbox.{MailboxManager, MailboxSession, MessageManager, MessageUid}
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import org.reactivestreams.Publisher
import reactor.core.scala.publisher.SFlux

import scala.jdk.CollectionConverters._

class EncryptedMessageManager @Inject()(messageManager: MessageManager, keystoreManager: KeystoreManager) extends MessageManager {
  
  override def getMessageCount(mailboxSession: MailboxSession): Long = messageManager.getMessageCount(mailboxSession)

  override def getMailboxCounters(mailboxSession: MailboxSession): MailboxCounters = messageManager.getMailboxCounters(mailboxSession)

  override def isWriteable(session: MailboxSession): Boolean = messageManager.isWriteable(session)

  override def isModSeqPermanent(session: MailboxSession): Boolean = messageManager.isModSeqPermanent(session)

  override def expunge(set: MessageRange, mailboxSession: MailboxSession): util.Iterator[MessageUid] = messageManager.expunge(set, mailboxSession)

  override def search(searchQuery: SearchQuery, mailboxSession: MailboxSession): Publisher[MessageUid] = messageManager.search(searchQuery, mailboxSession)

  override def delete(uids: util.List[MessageUid], mailboxSession: MailboxSession): Unit = messageManager.delete(uids, mailboxSession)

  override def setFlags(flags: Flags, flagsUpdateMode: MessageManager.FlagsUpdateMode, set: MessageRange, mailboxSession: MailboxSession): util.Map[MessageUid, Flags] =
    messageManager.setFlags(flags, flagsUpdateMode, set, mailboxSession)

  override def appendMessage(msgIn: InputStream, internalDate: Date, mailboxSession: MailboxSession, isRecent: Boolean, flags: Flags): AppendResult = {
      throw new UnsupportedOperationException("append by InputStream only used in test for compatibility issue")
  }

  override def appendMessage(appendCommand: AppendCommand, session: MailboxSession): AppendResult = {
    val keys: Seq[PublicKey] = SFlux.fromPublisher(keystoreManager.listPublicKeys(session.getUser))
      .collectSeq()
      .block()

    if (keys.isEmpty) {
      messageManager.appendMessage(appendCommand, session)
    } else {
      val messageBuilder: DefaultMessageBuilder = new DefaultMessageBuilder();
      messageBuilder.setMimeEntityConfig(MimeConfig.PERMISSIVE)
      messageBuilder.setDecodeMonitor(DecodeMonitor.SILENT)
      val clearMessage: Message = messageBuilder.parseMessage(appendCommand.getMsgIn.getInputStream)
      val encryptedMessage: Message = Encrypter.forKeys(keys.map(key => key.payload).asJava)
        .encrypt(clearMessage)

      messageManager.appendMessage(AppendCommand.from(encryptedMessage), session)
    }
  }

  override def getMessages(set: MessageRange, fetchGroup: FetchGroup, mailboxSession: MailboxSession): MessageResultIterator =
    messageManager.getMessages(set, fetchGroup, mailboxSession)

  override def listMessagesMetadata(set: MessageRange, session: MailboxSession): Publisher[ComposedMessageIdWithMetaData] =
    messageManager.listMessagesMetadata(set, session)

  override def getMailboxEntity: Mailbox = messageManager.getMailboxEntity

  override def getSupportedMessageCapabilities: util.EnumSet[MailboxManager.MessageCapabilities] = messageManager.getSupportedMessageCapabilities

  override def getId: MailboxId = messageManager.getId

  override def getMailboxPath: MailboxPath = messageManager.getMailboxPath

  override def getApplicableFlags(session: MailboxSession): Flags = messageManager.getApplicableFlags(session)

  override def getMetaData(resetRecent: Boolean, mailboxSession: MailboxSession, fetchGroup: MailboxMetaData.FetchGroup): MessageManager.MailboxMetaData =
    messageManager.getMetaData(resetRecent, mailboxSession, fetchGroup)

  override def getResolvedAcl(mailboxSession: MailboxSession): MailboxACL = messageManager.getResolvedAcl(mailboxSession)
}
