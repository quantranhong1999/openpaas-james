package com.linagora.tmail.james.common

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import com.google.inject.AbstractModule
import com.google.inject.multibindings.Multibinder
import com.linagora.tmail.team.{TeamMailbox, TeamMailboxName, TeamMailboxRepository, TeamMailboxRepositoryImpl}
import eu.timepit.refined.auto._
import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.http.ContentType.JSON
import javax.inject.Inject
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER
import net.javacrumbs.jsonunit.core.internal.Options
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.core.Username
import org.apache.james.jmap.api.change.State
import org.apache.james.jmap.api.model.AccountId
import org.apache.james.jmap.core.ResponseObject.SESSION_STATE
import org.apache.james.jmap.draft.JmapGuiceProbe
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, BOB, BOB_PASSWORD, CEDRIC, DOMAIN, authScheme, baseRequestSpecBuilder}
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.MailboxPath
import org.apache.james.mime4j.dom.Message
import org.apache.james.modules.MailboxProbeImpl
import org.apache.james.utils.{DataProbeImpl, GuiceProbe}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS
import org.junit.jupiter.api.{BeforeEach, Test}
import play.api.libs.json.{JsArray, Json}
import reactor.core.scala.publisher.SMono

class TeamMailboxProbeModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[TeamMailboxRepository]).to(classOf[TeamMailboxRepositoryImpl])

    Multibinder.newSetBinder(binder(), classOf[GuiceProbe])
      .addBinding()
      .to(classOf[TeamMailboxProbe])
  }
}

class TeamMailboxProbe @Inject()(teamMailboxRepository: TeamMailboxRepository) extends GuiceProbe {
  def create(teamMailbox: TeamMailbox): TeamMailboxProbe = {
    SMono(teamMailboxRepository.createTeamMailbox(teamMailbox)).block()
    this
  }

  def addMember(teamMailbox: TeamMailbox, member: Username): TeamMailboxProbe = {
    SMono(teamMailboxRepository.addMember(teamMailbox, member)).block()
    this
  }
}

trait TeamMailboxesContract {
  private lazy val slowPacedPollInterval = ONE_HUNDRED_MILLISECONDS
  private lazy val calmlyAwait = Awaitility.`with`
    .pollInterval(slowPacedPollInterval)
    .and.`with`.pollDelay(slowPacedPollInterval)
    .await
  private lazy val awaitAtMostTenSeconds = calmlyAwait.atMost(10, TimeUnit.SECONDS)

  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent()
      .addDomain(DOMAIN.asString())
      .addUser(BOB.asString(), BOB_PASSWORD)
      .addUser(CEDRIC.asString(), "CEDRIC_pass")

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .build
  }

  @Test
  def mailboxGetShouldListBaseMailbox(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail",
                    |    "urn:apache:james:params:jmap:mail:shares"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [
           |        {
           |          "id": "$id1",
           |          "name": "marketing",
           |          "sortOrder": 1000,
           |          "totalEmails": 0,
           |          "unreadEmails": 0,
           |          "totalThreads": 0,
           |          "unreadThreads": 0,
           |          "myRights": {
           |            "mayReadItems": true,
           |            "mayAddItems": true,
           |            "mayRemoveItems": true,
           |            "maySetSeen": true,
           |            "maySetKeywords": true,
           |            "mayCreateChild": false,
           |            "mayRename": false,
           |            "mayDelete": false,
           |            "maySubmit": false
           |          },
           |          "isSubscribed": false,
           |          "namespace": "TeamMailbox[marketing@domain.tld]",
           |          "rights": {"bob@domain.tld":["i", "l", "r", "s", "t", "w"]}
           |        }
           |      ],
           |      "notFound": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def mailboxGetShouldListInboxMailbox(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail",
                    |    "urn:apache:james:params:jmap:mail:shares"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [
           |        {
           |          "id": "$id1",
           |          "parentId": "$id2",
           |          "name": "INBOX",
           |          "sortOrder": 1000,
           |          "totalEmails": 0,
           |          "unreadEmails": 0,
           |          "totalThreads": 0,
           |          "unreadThreads": 0,
           |          "myRights": {
           |            "mayReadItems": true,
           |            "mayAddItems": true,
           |            "mayRemoveItems": true,
           |            "maySetSeen": true,
           |            "maySetKeywords": true,
           |            "mayCreateChild": false,
           |            "mayRename": false,
           |            "mayDelete": false,
           |            "maySubmit": false
           |          },
           |          "isSubscribed": false,
           |          "namespace": "TeamMailbox[marketing@domain.tld]",
           |          "rights": {"bob@domain.tld":["i", "l", "r", "s", "t", "w"]}
           |        }
           |      ],
           |      "notFound": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def mailboxGetShouldListSentMailbox(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail",
                    |    "urn:apache:james:params:jmap:mail:shares"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [
           |        {
           |          "id": "$id1",
           |          "parentId": "$id2",
           |          "name": "Sent",
           |          "sortOrder": 1000,
           |          "totalEmails": 0,
           |          "unreadEmails": 0,
           |          "totalThreads": 0,
           |          "unreadThreads": 0,
           |          "myRights": {
           |            "mayReadItems": true,
           |            "mayAddItems": true,
           |            "mayRemoveItems": true,
           |            "maySetSeen": true,
           |            "maySetKeywords": true,
           |            "mayCreateChild": false,
           |            "mayRename": false,
           |            "mayDelete": false,
           |            "maySubmit": false
           |          },
           |          "isSubscribed": false,
           |          "namespace": "TeamMailbox[marketing@domain.tld]",
           |          "rights": {"bob@domain.tld":["i", "l", "r", "s", "t", "w"]}
           |        }
           |      ],
           |      "notFound": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def mailboxGetShouldNotReturnTeamMailboxesWhenNoShareExtension(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val id3 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1", "$id2", "$id3"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .withOptions(new Options(IGNORING_ARRAY_ORDER))
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [],
           |      "notFound": ["$id1", "$id2", "$id3"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def mailboxGetShouldNotReturnTeamMailboxesWhenNotAMember(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val id3 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail",
                    |    "urn:apache:james:params:jmap:mail:shares"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1", "$id2", "$id3"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .withOptions(new Options(IGNORING_ARRAY_ORDER))
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [],
           |      "notFound": ["$id1", "$id2", "$id3"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def mailboxGetShouldNotListRightsOfOthers(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)
      .addMember(teamMailbox, CEDRIC)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =s"""{
                    |  "using": [
                    |    "urn:ietf:params:jmap:core",
                    |    "urn:ietf:params:jmap:mail",
                    |    "urn:apache:james:params:jmap:mail:shares"],
                    |  "methodCalls": [[
                    |    "Mailbox/get",
                    |    {
                    |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |      "ids": ["$id1"]
                    |    },
                    |    "c1"]]
                    |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "Mailbox/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "list": [
           |        {
           |          "id": "$id1",
           |          "name": "marketing",
           |          "sortOrder": 1000,
           |          "totalEmails": 0,
           |          "unreadEmails": 0,
           |          "totalThreads": 0,
           |          "unreadThreads": 0,
           |          "myRights": {
           |            "mayReadItems": true,
           |            "mayAddItems": true,
           |            "mayRemoveItems": true,
           |            "maySetSeen": true,
           |            "maySetKeywords": true,
           |            "mayCreateChild": false,
           |            "mayRename": false,
           |            "mayDelete": false,
           |            "maySubmit": false
           |          },
           |          "isSubscribed": false,
           |          "namespace": "TeamMailbox[marketing@domain.tld]",
           |          "rights": {"bob@domain.tld":["i", "l", "r", "s", "t", "w"]}
           |        }
           |      ],
           |      "notFound": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def renamingATeamMailboxShouldFail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "update": {
         |                    "$id1": {
         |                      "name": "otherName"
         |                    }
         |                }
         |           },
         |    "c1"
         |       ]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |  "sessionState":"2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |  "methodResponses":[[
           |    "Mailbox/set",
           |    {
           |      "accountId":"29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "notUpdated":{
           |        "$id1":{
           |          "type":"notFound",
           |          "description":"#TeamMailbox:team-mailbox@domain.tld:marketing"
           |        }
           |      }
           |    },
           |    "c1"]
           |  ]
           |}""".stripMargin)
  }

  @Test
  def deletingATeamMailboxShouldFail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "destroy": ["$id1"]
         |           },
         |    "c1"
         |       ]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notDestroyed": {
           |                    "$id1": {
           |                        "type": "notFound",
           |                        "description": "#TeamMailbox:team-mailbox@domain.tld:marketing.INBOX"
           |                    }
           |                }
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def creatingATeamMailboxChildShouldFail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "create": {
         |                  "K39" : {
         |                    "name": "aname",
         |                    "parentId": "$id1"
         |                  }
         |                }
         |           },
         |    "c1"
         |       ]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notCreated": {
           |                    "K39": {
           |                        "type": "forbidden",
           |                        "description": "Insufficient rights",
           |                        "properties": ["parentId"]
           |                    }
           |                }
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def movingATeamMailboxShouldFail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "update": {
         |                    "$id1": {
         |                      "parentId": "$id2"
         |                    }
         |                }
         |           },
         |    "c1"
         |       ]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |  "sessionState":"2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |  "methodResponses":[[
           |    "Mailbox/set",
           |    {
           |      "accountId":"29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "notUpdated":{
           |        "$id1":{
           |          "type":"notFound",
           |          "description":"#TeamMailbox:team-mailbox@domain.tld:marketing.Sent.INBOX"
           |        }
           |      }
           |    },
           |    "c1"]
           |  ]
           |}""".stripMargin)
  }

  @Test
  def delegatingATeamMailboxShouldFail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val request =
      s"""{
         |   "using": [ "urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares" ],
         |   "methodCalls": [
         |       [
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "update": {
         |                    "$id1": {
         |                      "sharedWith": {
         |                        "${CEDRIC.asString()}":["r", "l"]
         |                      }
         |                    }
         |                }
         |           },
         |    "c1"
         |       ],
         |       ["Mailbox/get",
         |         {
         |           "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |           "properties": ["id", "rights"],
         |           "ids": ["$id1"]
         |          },
         |       "c2"]
         |   ]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notUpdated": {
           |                    "$id1": {
           |                        "type": "invalidArguments",
           |                        "description": "Invalid change to a delegated mailbox"
           |                    }
           |                }
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Mailbox/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "id": "$id1",
           |                        "rights": {
           |                            "bob@domain.tld": ["i", "l", "r", "s", "t", "w"]
           |                        }
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def subscribingATeamMailboxShouldSucceed(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |           "Mailbox/set",
         |           {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "update": {
         |                    "$id1": {
         |                      "isSubscribed": true
         |                    }
         |                }
         |           },
         |    "c1"], [
         |    "Mailbox/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$id1"]
         |    },
         |    "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "updated": {
           |                    "$id1": {}
           |                }
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Mailbox/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "id": "$id1",
           |                        "name": "marketing",
           |                        "sortOrder": 1000,
           |                        "totalEmails": 0,
           |                        "unreadEmails": 0,
           |                        "totalThreads": 0,
           |                        "unreadThreads": 0,
           |                        "myRights": {
           |                            "mayReadItems": true,
           |                            "mayAddItems": true,
           |                            "mayRemoveItems": true,
           |                            "maySetSeen": true,
           |                            "maySetKeywords": true,
           |                            "mayCreateChild": false,
           |                            "mayRename": false,
           |                            "mayDelete": false,
           |                            "maySubmit": false
           |                        },
           |                        "isSubscribed": true,
           |                        "namespace": "TeamMailbox[marketing@domain.tld]",
           |                        "rights": {
           |                            "bob@domain.tld": ["i",  "l", "r", "s", "t", "w"]
           |                        }
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  private def provisionSystemMailboxes(server: GuiceJamesServer): State = {
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val jmapGuiceProbe: JmapGuiceProbe = server.getProbe(classOf[JmapGuiceProbe])

    val request =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |    "Mailbox/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6"
         |    },
         |    "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
      .when
      .post
      .`then`
      .statusCode(SC_OK)
      .contentType(JSON)

    //Wait until all the system mailboxes are created
    val request2 =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [[
         |    "Mailbox/changes",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "sinceState": "${State.INITIAL.getValue.toString}"
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response1 = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(request2)
        .when
        .post
        .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      val createdSize = Json.parse(response1)
        .\("methodResponses")
        .\(0).\(1)
        .\("created")
        .get.asInstanceOf[JsArray].value.size

      assertThat(createdSize).isEqualTo(6)
    }

    jmapGuiceProbe.getLatestMailboxState(AccountId.fromUsername(BOB))
  }

  @Test
  def addMemberTriggersAMailboxChange(server: GuiceJamesServer): Unit = {
    val oldState = provisionSystemMailboxes(server)

    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val id3 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Mailbox/changes", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "sinceState": "${oldState.getValue}"
         |    }, "c1"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/changes",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "hasMoreChanges": false,
           |                "updatedProperties": null,
           |                "created": [],
           |                "updated": ["$id1", "$id2", "$id3"],
           |                "destroyed": []
           |            },
           |            "c1"
           |        ]
           |    ]
           |}
           |""".stripMargin)
  }

  private def waitForNextState(server: GuiceJamesServer, accountId: AccountId, initialState: State): State = {
    val jmapGuiceProbe: JmapGuiceProbe = server.getProbe(classOf[JmapGuiceProbe])
    awaitAtMostTenSeconds.untilAsserted {
      () => assertThat(jmapGuiceProbe.getLatestMailboxStateWithDelegation(accountId)).isNotEqualTo(initialState)
    }

    jmapGuiceProbe.getLatestMailboxStateWithDelegation(accountId)
  }

  @Test
  def receivingAMailShouldTriggerAStateChange(server: GuiceJamesServer): Unit = {
    val originalState = provisionSystemMailboxes(server)

    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val id2 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.inboxPath.getNamespace, teamMailbox.inboxPath.getUser.asString(), teamMailbox.inboxPath.getName)
      .serialize()

    val id3 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.sentPath.getNamespace, teamMailbox.sentPath.getUser.asString(), teamMailbox.sentPath.getName)
      .serialize()

    val oldState = waitForNextState(server, AccountId.fromUsername(BOB), originalState)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))

    waitForNextState(server, AccountId.fromUsername(BOB), oldState)

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Mailbox/changes", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "sinceState": "${oldState.getValue}"
         |    }, "c1"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].oldState", "methodResponses[0][1].newState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Mailbox/changes",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "hasMoreChanges": false,
           |                "updatedProperties": ["totalEmails","unreadEmails","totalThreads","unreadThreads"],
           |                "created": [],
           |                "updated": ["$id2"],
           |                "destroyed": []
           |            },
           |            "c1"
           |        ]
           |    ]
           |}
           |""".stripMargin)
  }

  @Test
  def emailQueryShouldReturnTeamMailboxEmail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/query", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "filter": {}
         |    }, "c1"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].queryState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/query",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "canCalculateChanges": false,
           |                "ids": ["$messageId"],
           |                "position": 0,
           |                "limit": 256
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailQueryShouldNotReturnTeamMailboxEmailWhenNoShare(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [["Email/query", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "filter": {}
         |    }, "c1"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].queryState")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/query",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "canCalculateChanges": false,
           |                "ids": [],
           |                "position": 0,
           |                "limit": 256
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailGetShouldReturnTeamMailboxEmail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$messageId"],
         |      "properties":["subject"]
         |    }, "c1"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "subject": "test",
           |                        "id": "$messageId"
           |                    }
           |                ]
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailSetShouldDestroyTeamMailboxEmail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/set", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "destroy": ["$messageId"]
         |    }, "c1"], ["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$messageId"],
         |      "properties":["subject"]
         |    }, "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].newState", "methodResponses[0][1].oldState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "destroyed": ["$messageId"]
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": ["$messageId"],
           |                "state": "14ee6150-95ea-44dc-bf1b-e50953f43404",
           |                "list": []
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailSetShouldUpdateFlagsForTeamMailboxEmail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/set", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "update": {
         |        "$messageId": {
         |          "keywords": { "Custom": true }
         |        }
         |      }
         |    }, "c1"], ["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$messageId"],
         |      "properties":["keywords"]
         |    }, "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].newState", "methodResponses[0][1].oldState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "updated": {"$messageId": null}
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "keywords": {"custom": true},
           |                        "id": "$messageId"
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailSetShouldMoveTeamMailboxEmailOut(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), teamMailbox.inboxPath, AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/set", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "update": {
         |        "$messageId": {
         |          "mailboxIds": { "$id": true }
         |        }
         |      }
         |    }, "c1"], ["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$messageId"],
         |      "properties":["mailboxIds"]
         |    }, "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].newState", "methodResponses[0][1].oldState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "updated": {"1": null}
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "mailboxIds":{"$id":true}
           |                        "id": "$messageId"
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailSetShouldMoveTeamMailboxEmailIn(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val messageId = server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), MailboxPath.inbox(BOB), AppendCommand.from(message))
      .getMessageId.serialize()

    val request =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail", "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/set", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "update": {
         |        "$messageId": {
         |          "mailboxIds": { "$id": true }
         |        }
         |      }
         |    }, "c1"], ["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["$messageId"],
         |      "properties":["mailboxIds"]
         |    }, "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].newState", "methodResponses[0][1].oldState", "methodResponses[1][1].state")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "updated": {"1": null}
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "mailboxIds":{"$id":true},
           |                        "id": "$messageId"
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

  @Test
  def emailSetShouldCreateTeamMailboxEmail(server: GuiceJamesServer): Unit = {
    val teamMailbox = TeamMailbox(DOMAIN, TeamMailboxName("marketing"))
    server.getProbe(classOf[TeamMailboxProbe])
      .create(teamMailbox)
      .addMember(teamMailbox, BOB)

    val id1 = server.getProbe(classOf[MailboxProbeImpl])
      .getMailboxId(teamMailbox.mailboxPath.getNamespace, teamMailbox.mailboxPath.getUser.asString(), teamMailbox.mailboxPath.getName)
      .serialize()

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:apache:james:params:jmap:mail:shares"],
         |  "methodCalls": [["Email/set", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "create": {
         |        "K39": {
         |          "mailboxIds": {"$id1":true}
         |        }
         |      }
         |    }, "c1"], ["Email/get", {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": ["#K39"],
         |      "properties":["mailboxIds"]
         |    }, "c2"]]
         |}""".stripMargin

    val response: String = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[0][1].newState", "methodResponses[0][1].oldState", "methodResponses[1][1].state",
        "methodResponses[0][1].created.K39.id", "methodResponses[0][1].created.K39.threadId", "methodResponses[0][1].created.K39.blobId",
        "methodResponses[0][1].created.K39.size", "methodResponses[1][1].list[0].id")
      .isEqualTo(
        s"""{
           |    "sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
           |    "methodResponses": [
           |        [
           |            "Email/set",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "created": {
           |                    "K39": {
           |                    }
           |                }
           |            },
           |            "c1"
           |        ],
           |        [
           |            "Email/get",
           |            {
           |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |                "notFound": [],
           |                "list": [
           |                    {
           |                        "mailboxIds": {"$id1": true}
           |                    }
           |                ]
           |            },
           |            "c2"
           |        ]
           |    ]
           |}""".stripMargin)
  }

}
