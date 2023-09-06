package com.linagora.tmail.james.jmap.settings

import com.datastax.oss.driver.api.core.`type`.DataTypes.{TEXT, UUID, frozenMapOf, mapOf}
import com.datastax.oss.driver.api.core.`type`.codec.registry.CodecRegistry
import com.datastax.oss.driver.api.core.`type`.codec.{TypeCodec, TypeCodecs}
import com.datastax.oss.driver.api.core.cql.{BoundStatementBuilder, PreparedStatement, Row}
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.{bindMarker, deleteFrom, insertInto, selectFrom, update}
import com.datastax.oss.driver.api.querybuilder.relation.Relation.column
import com.linagora.tmail.james.jmap.settings.JmapSettings.{JmapSettingsKey, JmapSettingsValue}
import javax.inject.Inject
import org.apache.james.backends.cassandra.components.CassandraModule
import org.apache.james.backends.cassandra.utils.CassandraAsyncExecutor
import org.apache.james.core.Username
import org.apache.james.jmap.core.UuidState
import reactor.core.scala.publisher.SMono

import scala.collection.immutable.{Map => ImmutableMap}

object CassandraJmapSettingsTable {
  val TABLE_NAME = "settings"
  val USER: CqlIdentifier = CqlIdentifier.fromCql("user")
  val STATE: CqlIdentifier = CqlIdentifier.fromCql("state")
  val SETTINGS: CqlIdentifier = CqlIdentifier.fromCql("settings")

  val MODULE: CassandraModule = CassandraModule.table(TABLE_NAME)
    .comment("Hold user JMAP settings")
    .statement(statement => _ => statement
      .withPartitionKey(USER, TEXT)
      .withColumn(STATE, UUID)
      .withColumn(SETTINGS, mapOf(TEXT, TEXT)))
    .build

  val MAP_OF_STRING_CODEC: TypeCodec[java.util.Map[String,String]] = CodecRegistry.DEFAULT.codecFor(frozenMapOf(TEXT, TEXT))
}

class CassandraJmapSettingsDAO @Inject()(session: CqlSession) {
  import CassandraJmapSettingsTable._

  private val executor: CassandraAsyncExecutor = new CassandraAsyncExecutor(session)

  private val insert: PreparedStatement = session.prepare(insertInto(TABLE_NAME)
    .value(USER, bindMarker(USER))
    .value(STATE, bindMarker(STATE))
    .value(SETTINGS, bindMarker(SETTINGS))
    .build())

  private val updateStatement: PreparedStatement = session.prepare(update(TABLE_NAME)
    .setColumn(STATE, bindMarker(STATE))
    .setColumn(SETTINGS, bindMarker(SETTINGS))
    .where(column(USER).isEqualTo(bindMarker(USER)))
    .build())

  private val selectOne: PreparedStatement = session.prepare(selectFrom(TABLE_NAME)
    .all()
    .whereColumn(USER).isEqualTo(bindMarker(USER))
    .build())

  private val deleteOne: PreparedStatement = session.prepare(deleteFrom(TABLE_NAME)
    .whereColumn(USER).isEqualTo(bindMarker(USER))
    .build())

  def insert(username: Username, jmapSettings: JmapSettings): SMono[JmapSettings] = {
    val settings: java.util.Map[String, String] = jmapSettings.settings.map(item => item._1.value.value -> item._2.value).asInstanceOf[java.util.Map[String, String]]
    val insertSetting = insert.bind()
      .set(USER, username.asString(), TypeCodecs.TEXT)
      .set(STATE, jmapSettings.state.serialize, TypeCodecs.TEXT)
      .set(SETTINGS, settings , MAP_OF_STRING_CODEC)

    SMono.fromPublisher(executor.executeVoid(insertSetting)
      .thenReturn(jmapSettings))
  }

  def selectOne(username: Username): SMono[JmapSettings] =
    SMono.fromPublisher(executor.executeSingleRow(selectOne.bind()
      .set(USER, username.asString(), TypeCodecs.TEXT))
      .map(toJmapSettings))

  def selectState(username: Username): SMono[UuidState] =
    SMono.fromPublisher(executor.executeSingleRow(selectOne.bind()
      .set(USER, username.asString(), TypeCodecs.TEXT))
      .map(toState))

  def updateSetting(username: Username, newState: UuidState, newSettings: Map[JmapSettingsKey, JmapSettingsValue]): SMono[Void] = {
    val updateStatementBuilder: BoundStatementBuilder = updateStatement.boundStatementBuilder()
    updateStatementBuilder.set(USER, username.asString(), TypeCodecs.TEXT)
    updateStatementBuilder.set(STATE, newState.toString, TypeCodecs.TEXT)
    val settings: java.util.Map[String, String] = new java.util.HashMap[String, String]
    if (newSettings.nonEmpty) {
      newSettings.foreach {
        kv => settings.put(kv._1.value.value, kv._2.value)
      }
    }
    updateStatementBuilder.set(SETTINGS, settings, MAP_OF_STRING_CODEC)
    SMono.fromPublisher(executor.executeVoid(updateStatementBuilder.build()))
  }

  def deleteOne(username: Username): SMono[Void] =
    SMono.fromPublisher(executor.executeVoid(deleteOne.bind()
      .set(USER, username.asString(), TypeCodecs.TEXT)))

  private def toJmapSettings(row: Row): JmapSettings =
    JmapSettings(settings = toSettings(row), state = UuidState(row.get(STATE, TypeCodecs.UUID)))

  private def toSettings(row: Row): Map[JmapSettingsKey, JmapSettingsValue] = {
    val settings: Map[JmapSettingsKey, JmapSettingsValue] = ImmutableMap.newBuilder[JmapSettingsKey, JmapSettingsValue].result()
    row.get(SETTINGS, MAP_OF_STRING_CODEC).forEach {
      case (key, value) => settings + (JmapSettingsKey.liftOrThrow(key) -> JmapSettingsValue(value))
    }
    settings
  }

  private def toState(row: Row): UuidState = row.get(STATE, TypeCodecs.TEXT)
    .map(state => UuidState.fromStringUnchecked(state.toString)).head
}
