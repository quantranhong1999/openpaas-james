package com.linagora.tmail.james.jmap.settings

import com.google.common.base.Preconditions
import com.google.inject.multibindings.Multibinder
import com.google.inject.{AbstractModule, Scopes}
import jakarta.inject.Inject
import org.apache.james.backends.cassandra.components.CassandraModule
import org.apache.james.core.Username
import org.apache.james.jmap.core.UuidState
import org.reactivestreams.Publisher
import reactor.core.scala.publisher.SMono

class CassandraJmapSettingsRepository @Inject()(dao: CassandraJmapSettingsDAO) extends JmapSettingsRepository {
  override def get(username: Username): Publisher[JmapSettings] = dao.selectOne(username)

  override def reset(username: Username, settings: JmapSettingsUpsertRequest): Publisher[SettingsStateUpdate] = {
    val newState: UuidState = JmapSettingsStateFactory.generateState()
    val oldState: UuidState = dao.selectState(username).block()
    SMono.fromCallable(() => {
      dao.updateSetting(username, Some(newState), Some(settings.settings))
    }).`then`(SMono.just(SettingsStateUpdate(oldState, newState)))
  }

  override def updatePartial(username: Username, settingsPatch: JmapSettingsPatch): Publisher[SettingsStateUpdate] = {
    Preconditions.checkArgument(!settingsPatch.isEmpty, "Cannot update when upsert and remove is empty".asInstanceOf[Object])
    Preconditions.checkArgument(!settingsPatch.isConflict, "Cannot update and remove the same setting key".asInstanceOf[Object])
    val newState: UuidState = JmapSettingsStateFactory.generateState()
    val oldState: UuidState = dao.selectState(username).block()

    SMono.fromCallable(() => {
      dao.updateSetting(username, Some(newState), Some(settingsPatch.toUpsert.settings))
    }).`then`(SMono.just(SettingsStateUpdate(oldState, newState)))
  }
}

case class CassandraJmapSettingsRepositoryModule() extends AbstractModule {
  override def configure(): Unit = {
    Multibinder.newSetBinder(binder, classOf[CassandraModule])
      .addBinding().toInstance(CassandraJmapSettingsTable.MODULE)
    bind(classOf[CassandraJmapSettingsDAO]).in(Scopes.SINGLETON)

    bind(classOf[JmapSettingsRepository]).to(classOf[CassandraJmapSettingsRepository])
    bind(classOf[CassandraJmapSettingsRepository]).in(Scopes.SINGLETON)
  }
}