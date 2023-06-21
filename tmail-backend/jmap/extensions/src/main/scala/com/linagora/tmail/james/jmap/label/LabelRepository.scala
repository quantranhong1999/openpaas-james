package com.linagora.tmail.james.jmap.label

import java.util

import com.google.common.collect.{HashBasedTable, Table, Tables}
import com.google.inject.{AbstractModule, Scopes}
import com.linagora.tmail.james.jmap.model.{Color, DisplayName, Label, LabelCreationRequest, LabelId, LabelNotFoundException}
import org.apache.james.core.Username
import org.apache.james.jmap.mail.Keyword
import org.reactivestreams.Publisher
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.jdk.CollectionConverters._

trait LabelRepository {
  def addLabel(username: Username, labelCreationRequest: LabelCreationRequest): Publisher[Label]

  def addLabel(username: Username, label: Label): Publisher[Void]

  def addLabels(username: Username, labelCreationRequests: util.Collection[LabelCreationRequest]): Publisher[Label]

  def updateLabel(username: Username, labelId: LabelId, newDisplayName: Option[DisplayName] = None, newColor: Option[Color] = None): Publisher[Void]

  def getLabels(username: Username, ids: util.Collection[LabelId]): Publisher[Label]

  def listLabels(username: Username): Publisher[Label]

  def deleteLabel(username: Username, labelId: LabelId): Publisher[Void]

  def deleteAllLabels(username: Username): Publisher[Void]
}

class MemoryLabelRepository extends LabelRepository {
  private val labelsTable: Table[Username, Keyword, Label] = Tables.synchronizedTable(HashBasedTable.create())

  override def addLabel(username: Username, labelCreationRequest: LabelCreationRequest): Publisher[Label] =
    SMono.fromCallable(() => {
      val label: Label = labelCreationRequest.toLabel
      labelsTable.put(username, label.keyword, label)
      label
    })

  override def addLabel(username: Username, label: Label): Publisher[Void] =
    SMono.fromCallable(() => labelsTable.put(username, label.keyword, label))
      .`then`()

  override def addLabels(username: Username, labelCreationRequests: util.Collection[LabelCreationRequest]): Publisher[Label] =
    SFlux.fromIterable(labelCreationRequests.asScala)
      .concatMap(creationRequest => addLabel(username, creationRequest))

  override def updateLabel(username: Username, labelId: LabelId, newDisplayName: Option[DisplayName] = None, newColor: Option[Color] = None): Publisher[Void] =
    SMono.justOrEmpty(labelsTable.get(username, labelId.toKeyword))
      .doOnNext(oldLabel => labelsTable.put(username, labelId.toKeyword, oldLabel.update(newDisplayName, newColor)))
      .switchIfEmpty(SMono.error(LabelNotFoundException(labelId)))
      .`then`()

  override def getLabels(username: Username, ids: util.Collection[LabelId]): Publisher[Label] =
    SFlux.fromIterable(labelsTable.row(username).entrySet().asScala)
      .filter((entry: util.Map.Entry[Keyword, Label]) => ids.contains(LabelId.fromKeyword(entry.getKey)))
      .map(_.getValue)

  override def listLabels(username: Username): Publisher[Label] =
    SFlux.fromIterable(labelsTable.row(username).entrySet().asScala)
      .map(_.getValue)

  override def deleteLabel(username: Username, labelId: LabelId): Publisher[Void] =
    SMono.fromCallable(() => labelsTable.remove(username, labelId.toKeyword))
      .`then`()

  override def deleteAllLabels(username: Username): Publisher[Void] =
    SMono.fromCallable(() => labelsTable.row(username).clear())
      .`then`()
}

case class MemoryLabelRepositoryModule() extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[LabelRepository]).to(classOf[MemoryLabelRepository])
    bind(classOf[MemoryLabelRepository]).in(Scopes.SINGLETON)
  }
}