package com.abhinavxt.debforge.download.watch

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.abhinavxt.debforge.`data`.local.Converters
import com.abhinavxt.debforge.domain.ProviderId
import javax.`annotation`.processing.Generated
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
public class WatchDao_Impl(
  __db: RoomDatabase,
) : WatchDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfWatchEntity: EntityInsertAdapter<WatchEntity>

  private val __converters: Converters = Converters()
  init {
    this.__db = __db
    this.__insertAdapterOfWatchEntity = object : EntityInsertAdapter<WatchEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `watches` (`provider`,`jobRef`,`name`,`createdAt`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: WatchEntity) {
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(1, _tmp)
        statement.bindText(2, entity.jobRef)
        statement.bindText(3, entity.name)
        statement.bindLong(4, entity.createdAt)
      }
    }
  }

  public override suspend fun upsert(watch: WatchEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfWatchEntity.insert(_connection, watch)
  }

  public override fun observeAll(): Flow<List<WatchEntity>> {
    val _sql: String = "SELECT * FROM watches ORDER BY createdAt ASC"
    return createFlow(__db, false, arrayOf("watches")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfJobRef: Int = getColumnIndexOrThrow(_stmt, "jobRef")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<WatchEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: WatchEntity
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpJobRef: String
          _tmpJobRef = _stmt.getText(_columnIndexOfJobRef)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = WatchEntity(_tmpProvider,_tmpJobRef,_tmpName,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAll(): List<WatchEntity> {
    val _sql: String = "SELECT * FROM watches ORDER BY createdAt ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfJobRef: Int = getColumnIndexOrThrow(_stmt, "jobRef")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<WatchEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: WatchEntity
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpJobRef: String
          _tmpJobRef = _stmt.getText(_columnIndexOfJobRef)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = WatchEntity(_tmpProvider,_tmpJobRef,_tmpName,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM watches"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(provider: ProviderId, jobRef: String) {
    val _sql: String = "DELETE FROM watches WHERE provider = ? AND jobRef = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.providerToString(provider)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        _stmt.bindText(_argIndex, jobRef)
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
