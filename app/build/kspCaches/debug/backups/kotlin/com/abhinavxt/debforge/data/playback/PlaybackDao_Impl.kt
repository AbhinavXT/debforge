package com.abhinavxt.debforge.`data`.playback

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.EntityUpsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.abhinavxt.debforge.`data`.local.Converters
import com.abhinavxt.debforge.domain.ProviderId
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class PlaybackDao_Impl(
  __db: RoomDatabase,
) : PlaybackDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfPlaybackEntity: EntityUpsertAdapter<PlaybackEntity>

  private val __converters: Converters = Converters()
  init {
    this.__db = __db
    this.__upsertAdapterOfPlaybackEntity = EntityUpsertAdapter<PlaybackEntity>(object :
        EntityInsertAdapter<PlaybackEntity>() {
      protected override fun createQuery(): String =
          "INSERT INTO `playback_positions` (`itemId`,`provider`,`sourceRef`,`filename`,`title`,`showKey`,`positionMs`,`durationMs`,`finished`,`updatedAt`,`parentRef`) VALUES (?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PlaybackEntity) {
        statement.bindText(1, entity.itemId)
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(2, _tmp)
        statement.bindText(3, entity.sourceRef)
        statement.bindText(4, entity.filename)
        statement.bindText(5, entity.title)
        val _tmpShowKey: String? = entity.showKey
        if (_tmpShowKey == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpShowKey)
        }
        statement.bindLong(7, entity.positionMs)
        statement.bindLong(8, entity.durationMs)
        val _tmp_1: Int = if (entity.finished) 1 else 0
        statement.bindLong(9, _tmp_1.toLong())
        statement.bindLong(10, entity.updatedAt)
        val _tmpParentRef: String? = entity.parentRef
        if (_tmpParentRef == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpParentRef)
        }
      }
    }, object : EntityDeleteOrUpdateAdapter<PlaybackEntity>() {
      protected override fun createQuery(): String =
          "UPDATE `playback_positions` SET `itemId` = ?,`provider` = ?,`sourceRef` = ?,`filename` = ?,`title` = ?,`showKey` = ?,`positionMs` = ?,`durationMs` = ?,`finished` = ?,`updatedAt` = ?,`parentRef` = ? WHERE `itemId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: PlaybackEntity) {
        statement.bindText(1, entity.itemId)
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(2, _tmp)
        statement.bindText(3, entity.sourceRef)
        statement.bindText(4, entity.filename)
        statement.bindText(5, entity.title)
        val _tmpShowKey: String? = entity.showKey
        if (_tmpShowKey == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpShowKey)
        }
        statement.bindLong(7, entity.positionMs)
        statement.bindLong(8, entity.durationMs)
        val _tmp_1: Int = if (entity.finished) 1 else 0
        statement.bindLong(9, _tmp_1.toLong())
        statement.bindLong(10, entity.updatedAt)
        val _tmpParentRef: String? = entity.parentRef
        if (_tmpParentRef == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpParentRef)
        }
        statement.bindText(12, entity.itemId)
      }
    })
  }

  public override suspend fun upsert(entity: PlaybackEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __upsertAdapterOfPlaybackEntity.upsert(_connection, entity)
  }

  public override suspend fun `get`(itemId: String): PlaybackEntity? {
    val _sql: String = "SELECT * FROM playback_positions WHERE itemId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, itemId)
        val _columnIndexOfItemId: Int = getColumnIndexOrThrow(_stmt, "itemId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "sourceRef")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfShowKey: Int = getColumnIndexOrThrow(_stmt, "showKey")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "positionMs")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfFinished: Int = getColumnIndexOrThrow(_stmt, "finished")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _columnIndexOfParentRef: Int = getColumnIndexOrThrow(_stmt, "parentRef")
        val _result: PlaybackEntity?
        if (_stmt.step()) {
          val _tmpItemId: String
          _tmpItemId = _stmt.getText(_columnIndexOfItemId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpShowKey: String?
          if (_stmt.isNull(_columnIndexOfShowKey)) {
            _tmpShowKey = null
          } else {
            _tmpShowKey = _stmt.getText(_columnIndexOfShowKey)
          }
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpFinished: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfFinished).toInt()
          _tmpFinished = _tmp_1 != 0
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpParentRef: String?
          if (_stmt.isNull(_columnIndexOfParentRef)) {
            _tmpParentRef = null
          } else {
            _tmpParentRef = _stmt.getText(_columnIndexOfParentRef)
          }
          _result =
              PlaybackEntity(_tmpItemId,_tmpProvider,_tmpSourceRef,_tmpFilename,_tmpTitle,_tmpShowKey,_tmpPositionMs,_tmpDurationMs,_tmpFinished,_tmpUpdatedAt,_tmpParentRef)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeAll(): Flow<List<PlaybackEntity>> {
    val _sql: String = "SELECT * FROM playback_positions ORDER BY updatedAt DESC"
    return createFlow(__db, false, arrayOf("playback_positions")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfItemId: Int = getColumnIndexOrThrow(_stmt, "itemId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "sourceRef")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfShowKey: Int = getColumnIndexOrThrow(_stmt, "showKey")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "positionMs")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfFinished: Int = getColumnIndexOrThrow(_stmt, "finished")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _columnIndexOfParentRef: Int = getColumnIndexOrThrow(_stmt, "parentRef")
        val _result: MutableList<PlaybackEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PlaybackEntity
          val _tmpItemId: String
          _tmpItemId = _stmt.getText(_columnIndexOfItemId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpShowKey: String?
          if (_stmt.isNull(_columnIndexOfShowKey)) {
            _tmpShowKey = null
          } else {
            _tmpShowKey = _stmt.getText(_columnIndexOfShowKey)
          }
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpFinished: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfFinished).toInt()
          _tmpFinished = _tmp_1 != 0
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpParentRef: String?
          if (_stmt.isNull(_columnIndexOfParentRef)) {
            _tmpParentRef = null
          } else {
            _tmpParentRef = _stmt.getText(_columnIndexOfParentRef)
          }
          _item =
              PlaybackEntity(_tmpItemId,_tmpProvider,_tmpSourceRef,_tmpFilename,_tmpTitle,_tmpShowKey,_tmpPositionMs,_tmpDurationMs,_tmpFinished,_tmpUpdatedAt,_tmpParentRef)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(itemId: String) {
    val _sql: String = "DELETE FROM playback_positions WHERE itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
