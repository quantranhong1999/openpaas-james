package com.linagora.openpaas.james.common

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.http.ContentType.JSON
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, BOB, BOB_PASSWORD, DOMAIN, authScheme, baseRequestSpecBuilder}
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Test}

trait LinagoraFilterSetMethodContract {

  def generateMailboxIdForUser(): String
  def generateAccountIdAsString(): String

  @BeforeEach
  def setUp(server : GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent()
      .addDomain(DOMAIN.asString)
      .addUser(BOB.asString(), BOB_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .build()
  }

  @Test
  def updateShouldSucceed(): Unit = {
    val request = s"""{
                     |	"using": ["com:linagora:params:jmap:filter"],
                     |	"methodCalls": [
                     |		["Filter/set", {
                     |			"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                     |			"update": {
                     |				"singleton": [{
                     |					"id": "1",
                     |					"name": "My first rule",
                     |					"condition": {
                     |						"field": "subject",
                     |						"comparator": "contains",
                     |						"value": "question"
                     |					},
                     |					"action": {
                     |						"appendIn": {
                     |							"mailboxIds": ["1"]
                     |						}
                     |					}
                     |				}]
                     |			}
                     |		}, "c1"],
                     |		[
                     |			"Filter/get",
                     |			{
                     |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                     |				"ids": ["singleton"]
                     |			},
                     |			"c2"
                     |		]
                     |	]
                     |}""".stripMargin


    val response = `given`()
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when()
      .post().prettyPeek()
    .`then`
      .log().ifValidationFails()
      .statusCode(HttpStatus.SC_OK)
      .contentType(JSON)
      .extract()
      .body()
      .asString()

    assertThatJson(response).isEqualTo(
      s"""{
         |	"sessionState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
         |	"methodResponses": [
         |		[
         |			"Filter/set",
         |			{
         |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |				"newState": "2c9f1b12-b35a-43e6-9af2-0106fb53a943",
         |				"updated": {
         |					"singleton": {
         |
         |					}
         |				}
         |			},
         |			"c1"
         |		],
         |		[
         |			"Filter/get", {
         |				"accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |				"list": [{
         |					"id": "singleton",
         |					"rules": [{
         |						"name": "My first rule",
         |						"condition": {
         |							"field": "subject",
         |							"comparator": "contains",
         |							"value": "question"
         |						},
         |						"action": {
         |							"appendIn": {
         |								"mailboxIds": ["1"]
         |							}
         |						}
         |					}]
         |				}],
         |				"notFound": []
         |			}, "c2"
         |		]
         |	]
         |}""".stripMargin)
  }

}
