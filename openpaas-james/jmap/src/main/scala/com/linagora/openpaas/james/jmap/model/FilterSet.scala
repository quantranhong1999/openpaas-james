package com.linagora.openpaas.james.jmap.model

import org.apache.james.jmap.core.Id.Id
import org.apache.james.jmap.core.SetError.{SetErrorDescription, SetErrorType, invalidArgumentValue, serverFailValue}
import org.apache.james.jmap.core.{AccountId, State}
import org.apache.james.jmap.mail.Name
import org.apache.james.jmap.method.WithAccountId
import play.api.libs.json.JsObject

case class FilterSetRequest(accountId: AccountId,
                            update: Update) extends WithAccountId

case class Update(singleton: List[RuleWithId])

case class RuleWithId(id: Id, name: Name, condition: Condition, action: Action)

case class FilterSetResponse(accountId: AccountId,
                             newState: State,
                             updated: Option[Map[String, FilterSetUpdateResponse]])

case class FilterSetUpdateResponse(value: JsObject)

object FilterSetError {
  def invalidArgument(description: Option[SetErrorDescription]) = FilterSetError(invalidArgumentValue, description)
  def serverFail(description: Option[SetErrorDescription]) = FilterSetError(serverFailValue, description)
}

case class FilterSetError(`type`: SetErrorType, description: Option[SetErrorDescription])
