package com.abhinavxt.debforge.`data`.local

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
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
public class ChunkDao_Impl(
  __db: RoomDatabase,
) : ChunkDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfChunkEntity: EntityInsertAdapter<ChunkEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfChunkEntity = object : EntityInsertAdapter<ChunkEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `chunks` (`downloadId`,`index`,`startByte`,`endByte`,`bytesWritten`,`complete`) VALUES (?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ChunkEntity) {
        statement.bindText(1, entity.downloadId)
        statement.bindLong(2, entity.index.toLong())
        statement.bindLong(3, entity.startByte)
        statement.bindLong(4, entity.endByte)
        statement.bindLong(5, entity.bytesWritten)
        val _tmp: Int = if (entity.complete) 1 else 0
        statement.bindLong(6, _tmp.toLong())
      }
    }
  }

  public override suspend fun insertAll(chunks: List<ChunkEntity>): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfChunkEntity.insert(_connection, chunks)
  }

  public override suspend fun getForDownload(downloadId: String): List<ChunkEntity> {
    val _sql: String = "SELECT * FROM chunks WHERE downloadId = ? ORDER BY `index` ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, downloadId)
        val _columnIndexOfDownloadId: Int = getColumnIndexOrThrow(_stmt, "downloadId")
        val _columnIndexOfIndex: Int = getColumnIndexOrThrow(_stmt, "index")
        val _columnIndexOfStartByte: Int = getColumnIndexOrThrow(_stmt, "startByte")
        val _columnIndexOfEndByte: Int = getColumnIndexOrThrow(_stmt, "endByte")
        val _columnIndexOfBytesWritten: Int = getColumnIndexOrThrow(_stmt, "bytesWritten")
        val _columnIndexOfComplete: Int = getColumnIndexOrThrow(_stmt, "complete")
        val _result: MutableList<ChunkEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChunkEntity
          val _tmpDownloadId: String
          _tmpDownloadId = _stmt.getText(_columnIndexOfDownloadId)
          val _tmpIndex: Int
          _tmpIndex = _stmt.getLong(_columnIndexOfIndex).toInt()
          val _tmpStartByte: Long
          _tmpStartByte = _stmt.getLong(_columnIndexOfStartByte)
          val _tmpEndByte: Long
          _tmpEndByte = _stmt.getLong(_columnIndexOfEndByte)
          val _tmpBytesWritten: Long
          _tmpBytesWritten = _stmt.getLong(_columnIndexOfBytesWritten)
          val _tmpComplete: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfComplete).toInt()
          _tmpComplete = _tmp != 0
          _item =
              ChunkEntity(_tmpDownloadId,_tmpIndex,_tmpStartByte,_tmpEndByte,_tmpBytesWritten,_tmpComplete)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeForDownload(downloadId: String): Flow<List<ChunkEntity>> {
    val _sql: String = "SELECT * FROM chunks WHERE downloadId = ? ORDER BY `index` ASC"
    return createFlow(__db, false, arrayOf("chunks")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, downloadId)
        val _columnIndexOfDownloadId: Int = getColumnIndexOrThrow(_stmt, "downloadId")
        val _columnIndexOfIndex: Int = getColumnIndexOrThrow(_stmt, "index")
        val _columnIndexOfStartByte: Int = getColumnIndexOrThrow(_stmt, "startByte")
        val _columnIndexOfEndByte: Int = getColumnIndexOrThrow(_stmt, "endByte")
        val _columnIndexOfBytesWritten: Int = getColumnIndexOrThrow(_stmt, "bytesWritten")
        val _columnIndexOfComplete: Int = getColumnIndexOrThrow(_stmt, "complete")
        val _result: MutableList<ChunkEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChunkEntity
          val _tmpDownloadId: String
          _tmpDownloadId = _stmt.getText(_columnIndexOfDownloadId)
          val _tmpIndex: Int
          _tmpIndex = _stmt.getLong(_columnIndexOfIndex).toInt()
          val _tmpStartByte: Long
          _tmpStartByte = _stmt.getLong(_columnIndexOfStartByte)
          val _tmpEndByte: Long
          _tmpEndByte = _stmt.getLong(_columnIndexOfEndByte)
          val _tmpBytesWritten: Long
          _tmpBytesWritten = _stmt.getLong(_columnIndexOfBytesWritten)
          val _tmpComplete: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfComplete).toInt()
          _tmpComplete = _tmp != 0
          _item =
              ChunkEntity(_tmpDownloadId,_tmpIndex,_tmpStartByte,_tmpEndByte,_tmpBytesWritten,_tmpComplete)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun totalBytesWritten(downloadId: String): Long {
    val _sql: String = "SELECT COALESCE(SUM(bytesWritten), 0) FROM chunks WHERE downloadId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, downloadId)
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun remainingChunkCount(downloadId: String): Int {
    val _sql: String = "SELECT COUNT(*) FROM chunks WHERE downloadId = ? AND complete = 0"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, downloadId)
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

  public override suspend fun updateProgress(
    downloadId: String,
    index: Int,
    bytesWritten: Long,
  ) {
    val _sql: String = "UPDATE chunks SET bytesWritten = ? WHERE downloadId = ? AND `index` = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, bytesWritten)
        _argIndex = 2
        _stmt.bindText(_argIndex, downloadId)
        _argIndex = 3
        _stmt.bindLong(_argIndex, index.toLong())
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun markComplete(
    downloadId: String,
    index: Int,
    bytesWritten: Long,
  ) {
    val _sql: String =
        "UPDATE chunks SET bytesWritten = ?, complete = 1 WHERE downloadId = ? AND `index` = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, bytesWritten)
        _argIndex = 2
        _stmt.bindText(_argIndex, downloadId)
        _argIndex = 3
        _stmt.bindLong(_argIndex, index.toLong())
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteForDownload(downloadId: String) {
    val _sql: String = "DELETE FROM chunks WHERE downloadId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, downloadId)
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
