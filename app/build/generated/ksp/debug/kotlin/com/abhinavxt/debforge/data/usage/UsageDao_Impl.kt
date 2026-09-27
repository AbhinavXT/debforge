package com.abhinavxt.debforge.`data`.usage

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performInTransactionSuspending
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
public class UsageDao_Impl(
  __db: RoomDatabase,
) : UsageDao() {
  private val __db: RoomDatabase

  private val __insertAdapterOfUsageEntity: EntityInsertAdapter<UsageEntity>

  private val __converters: Converters = Converters()
  init {
    this.__db = __db
    this.__insertAdapterOfUsageEntity = object : EntityInsertAdapter<UsageEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `data_usage` (`day`,`provider`,`metered`,`bytes`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: UsageEntity) {
        statement.bindText(1, entity.day)
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(2, _tmp)
        val _tmp_1: Int = if (entity.metered) 1 else 0
        statement.bindLong(3, _tmp_1.toLong())
        statement.bindLong(4, entity.bytes)
      }
    }
  }

  public override suspend fun put(row: UsageEntity): Unit = performSuspending(__db, false, true) {
      _connection ->
    __insertAdapterOfUsageEntity.insert(_connection, row)
  }

  public override suspend fun add(
    day: String,
    provider: ProviderId,
    metered: Boolean,
    bytes: Long,
  ): Unit = performInTransactionSuspending(__db) {
    super@UsageDao_Impl.add(day, provider, metered, bytes)
  }

  public override suspend fun bytesFor(
    day: String,
    provider: ProviderId,
    metered: Boolean,
  ): Long? {
    val _sql: String = "SELECT bytes FROM data_usage WHERE day = ? AND provider = ? AND metered = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, day)
        _argIndex = 2
        val _tmp: String = __converters.providerToString(provider)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 3
        val _tmp_1: Int = if (metered) 1 else 0
        _stmt.bindLong(_argIndex, _tmp_1.toLong())
        val _result: Long?
        if (_stmt.step()) {
          if (_stmt.isNull(0)) {
            _result = null
          } else {
            _result = _stmt.getLong(0)
          }
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeSince(fromDay: String): Flow<List<UsageEntity>> {
    val _sql: String = "SELECT * FROM data_usage WHERE day >= ? ORDER BY day"
    return createFlow(__db, false, arrayOf("data_usage")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, fromDay)
        val _columnIndexOfDay: Int = getColumnIndexOrThrow(_stmt, "day")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfMetered: Int = getColumnIndexOrThrow(_stmt, "metered")
        val _columnIndexOfBytes: Int = getColumnIndexOrThrow(_stmt, "bytes")
        val _result: MutableList<UsageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: UsageEntity
          val _tmpDay: String
          _tmpDay = _stmt.getText(_columnIndexOfDay)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpMetered: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfMetered).toInt()
          _tmpMetered = _tmp_1 != 0
          val _tmpBytes: Long
          _tmpBytes = _stmt.getLong(_columnIndexOfBytes)
          _item = UsageEntity(_tmpDay,_tmpProvider,_tmpMetered,_tmpBytes)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clear() {
    val _sql: String = "DELETE FROM data_usage"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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
