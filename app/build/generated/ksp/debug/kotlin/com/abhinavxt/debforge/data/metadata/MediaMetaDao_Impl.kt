package com.abhinavxt.debforge.`data`.metadata

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
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
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class MediaMetaDao_Impl(
  __db: RoomDatabase,
) : MediaMetaDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfMediaMetaEntity: EntityInsertAdapter<MediaMetaEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfMediaMetaEntity = object : EntityInsertAdapter<MediaMetaEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `media_meta` (`cacheKey`,`found`,`title`,`year`,`posterPath`,`backdropPath`,`overview`,`fetchedAt`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: MediaMetaEntity) {
        statement.bindText(1, entity.cacheKey)
        val _tmp: Int = if (entity.found) 1 else 0
        statement.bindLong(2, _tmp.toLong())
        val _tmpTitle: String? = entity.title
        if (_tmpTitle == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpTitle)
        }
        val _tmpYear: Int? = entity.year
        if (_tmpYear == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpYear.toLong())
        }
        val _tmpPosterPath: String? = entity.posterPath
        if (_tmpPosterPath == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpPosterPath)
        }
        val _tmpBackdropPath: String? = entity.backdropPath
        if (_tmpBackdropPath == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpBackdropPath)
        }
        val _tmpOverview: String? = entity.overview
        if (_tmpOverview == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpOverview)
        }
        statement.bindLong(8, entity.fetchedAt)
      }
    }
  }

  public override suspend fun put(entity: MediaMetaEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfMediaMetaEntity.insert(_connection, entity)
  }

  public override suspend fun `get`(key: String): MediaMetaEntity? {
    val _sql: String = "SELECT * FROM media_meta WHERE cacheKey = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, key)
        val _columnIndexOfCacheKey: Int = getColumnIndexOrThrow(_stmt, "cacheKey")
        val _columnIndexOfFound: Int = getColumnIndexOrThrow(_stmt, "found")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfYear: Int = getColumnIndexOrThrow(_stmt, "year")
        val _columnIndexOfPosterPath: Int = getColumnIndexOrThrow(_stmt, "posterPath")
        val _columnIndexOfBackdropPath: Int = getColumnIndexOrThrow(_stmt, "backdropPath")
        val _columnIndexOfOverview: Int = getColumnIndexOrThrow(_stmt, "overview")
        val _columnIndexOfFetchedAt: Int = getColumnIndexOrThrow(_stmt, "fetchedAt")
        val _result: MediaMetaEntity?
        if (_stmt.step()) {
          val _tmpCacheKey: String
          _tmpCacheKey = _stmt.getText(_columnIndexOfCacheKey)
          val _tmpFound: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfFound).toInt()
          _tmpFound = _tmp != 0
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpYear: Int?
          if (_stmt.isNull(_columnIndexOfYear)) {
            _tmpYear = null
          } else {
            _tmpYear = _stmt.getLong(_columnIndexOfYear).toInt()
          }
          val _tmpPosterPath: String?
          if (_stmt.isNull(_columnIndexOfPosterPath)) {
            _tmpPosterPath = null
          } else {
            _tmpPosterPath = _stmt.getText(_columnIndexOfPosterPath)
          }
          val _tmpBackdropPath: String?
          if (_stmt.isNull(_columnIndexOfBackdropPath)) {
            _tmpBackdropPath = null
          } else {
            _tmpBackdropPath = _stmt.getText(_columnIndexOfBackdropPath)
          }
          val _tmpOverview: String?
          if (_stmt.isNull(_columnIndexOfOverview)) {
            _tmpOverview = null
          } else {
            _tmpOverview = _stmt.getText(_columnIndexOfOverview)
          }
          val _tmpFetchedAt: Long
          _tmpFetchedAt = _stmt.getLong(_columnIndexOfFetchedAt)
          _result =
              MediaMetaEntity(_tmpCacheKey,_tmpFound,_tmpTitle,_tmpYear,_tmpPosterPath,_tmpBackdropPath,_tmpOverview,_tmpFetchedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clear() {
    val _sql: String = "DELETE FROM media_meta"
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
