package com.abhinavxt.debforge.`data`.local

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.appendPlaceholders
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.abhinavxt.debforge.domain.DownloadState
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
import kotlin.text.StringBuilder
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class DownloadDao_Impl(
  __db: RoomDatabase,
) : DownloadDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfDownloadEntity: EntityInsertAdapter<DownloadEntity>

  private val __converters: Converters = Converters()

  private val __updateAdapterOfDownloadEntity: EntityDeleteOrUpdateAdapter<DownloadEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfDownloadEntity = object : EntityInsertAdapter<DownloadEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `downloads` (`rdId`,`provider`,`filename`,`originalLink`,`downloadUrl`,`host`,`filesize`,`chunksAllowed`,`state`,`bytesDownloaded`,`partFilePath`,`finalFilePath`,`supportsRanges`,`errorMessage`,`createdAt`,`updatedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: DownloadEntity) {
        statement.bindText(1, entity.id)
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(2, _tmp)
        statement.bindText(3, entity.filename)
        statement.bindText(4, entity.sourceRef)
        statement.bindText(5, entity.downloadUrl)
        statement.bindText(6, entity.host)
        statement.bindLong(7, entity.filesize)
        statement.bindLong(8, entity.chunksAllowed.toLong())
        val _tmp_1: String = __converters.stateToString(entity.state)
        statement.bindText(9, _tmp_1)
        statement.bindLong(10, entity.bytesDownloaded)
        val _tmpPartFilePath: String? = entity.partFilePath
        if (_tmpPartFilePath == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpPartFilePath)
        }
        val _tmpFinalFilePath: String? = entity.finalFilePath
        if (_tmpFinalFilePath == null) {
          statement.bindNull(12)
        } else {
          statement.bindText(12, _tmpFinalFilePath)
        }
        val _tmpSupportsRanges: Boolean? = entity.supportsRanges
        val _tmp_2: Int? = _tmpSupportsRanges?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(13)
        } else {
          statement.bindLong(13, _tmp_2.toLong())
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(14)
        } else {
          statement.bindText(14, _tmpErrorMessage)
        }
        statement.bindLong(15, entity.createdAt)
        statement.bindLong(16, entity.updatedAt)
      }
    }
    this.__updateAdapterOfDownloadEntity = object : EntityDeleteOrUpdateAdapter<DownloadEntity>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `downloads` SET `rdId` = ?,`provider` = ?,`filename` = ?,`originalLink` = ?,`downloadUrl` = ?,`host` = ?,`filesize` = ?,`chunksAllowed` = ?,`state` = ?,`bytesDownloaded` = ?,`partFilePath` = ?,`finalFilePath` = ?,`supportsRanges` = ?,`errorMessage` = ?,`createdAt` = ?,`updatedAt` = ? WHERE `rdId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: DownloadEntity) {
        statement.bindText(1, entity.id)
        val _tmp: String = __converters.providerToString(entity.provider)
        statement.bindText(2, _tmp)
        statement.bindText(3, entity.filename)
        statement.bindText(4, entity.sourceRef)
        statement.bindText(5, entity.downloadUrl)
        statement.bindText(6, entity.host)
        statement.bindLong(7, entity.filesize)
        statement.bindLong(8, entity.chunksAllowed.toLong())
        val _tmp_1: String = __converters.stateToString(entity.state)
        statement.bindText(9, _tmp_1)
        statement.bindLong(10, entity.bytesDownloaded)
        val _tmpPartFilePath: String? = entity.partFilePath
        if (_tmpPartFilePath == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpPartFilePath)
        }
        val _tmpFinalFilePath: String? = entity.finalFilePath
        if (_tmpFinalFilePath == null) {
          statement.bindNull(12)
        } else {
          statement.bindText(12, _tmpFinalFilePath)
        }
        val _tmpSupportsRanges: Boolean? = entity.supportsRanges
        val _tmp_2: Int? = _tmpSupportsRanges?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(13)
        } else {
          statement.bindLong(13, _tmp_2.toLong())
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(14)
        } else {
          statement.bindText(14, _tmpErrorMessage)
        }
        statement.bindLong(15, entity.createdAt)
        statement.bindLong(16, entity.updatedAt)
        statement.bindText(17, entity.id)
      }
    }
  }

  public override suspend fun upsert(download: DownloadEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfDownloadEntity.insert(_connection, download)
  }

  public override suspend fun update(download: DownloadEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __updateAdapterOfDownloadEntity.handle(_connection, download)
  }

  public override fun observeAll(): Flow<List<DownloadEntity>> {
    val _sql: String = "SELECT * FROM downloads ORDER BY createdAt DESC"
    return createFlow(__db, false, arrayOf("downloads")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<DownloadEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_1)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_2?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeByState(state: DownloadState): Flow<List<DownloadEntity>> {
    val _sql: String = "SELECT * FROM downloads WHERE state = ? ORDER BY createdAt ASC"
    return createFlow(__db, false, arrayOf("downloads")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.stateToString(state)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<DownloadEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp_1)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_2: String
          _tmp_2 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_2)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_3: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_3 = null
          } else {
            _tmp_3 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_3?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getById(rdId: String): DownloadEntity? {
    val _sql: String = "SELECT * FROM downloads WHERE rdId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, rdId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: DownloadEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_1)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_2?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeById(rdId: String): Flow<DownloadEntity?> {
    val _sql: String = "SELECT * FROM downloads WHERE rdId = ?"
    return createFlow(__db, false, arrayOf("downloads")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, rdId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: DownloadEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_1)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_2?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun nextInState(state: DownloadState): DownloadEntity? {
    val _sql: String = "SELECT * FROM downloads WHERE state = ? ORDER BY createdAt ASC LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.stateToString(state)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: DownloadEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp_1)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_2: String
          _tmp_2 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_2)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_3: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_3 = null
          } else {
            _tmp_3 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_3?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun isFinalPathClaimed(path: String): Boolean {
    val _sql: String = "SELECT EXISTS(SELECT 1 FROM downloads WHERE finalFilePath = ?)"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, path)
        val _result: Boolean
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp != 0
        } else {
          _result = false
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun allIds(): List<String> {
    val _sql: String = "SELECT rdId FROM downloads"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun completed(): List<DownloadEntity> {
    val _sql: String = "SELECT * FROM downloads WHERE state = 'COMPLETED'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "rdId")
        val _columnIndexOfProvider: Int = getColumnIndexOrThrow(_stmt, "provider")
        val _columnIndexOfFilename: Int = getColumnIndexOrThrow(_stmt, "filename")
        val _columnIndexOfSourceRef: Int = getColumnIndexOrThrow(_stmt, "originalLink")
        val _columnIndexOfDownloadUrl: Int = getColumnIndexOrThrow(_stmt, "downloadUrl")
        val _columnIndexOfHost: Int = getColumnIndexOrThrow(_stmt, "host")
        val _columnIndexOfFilesize: Int = getColumnIndexOrThrow(_stmt, "filesize")
        val _columnIndexOfChunksAllowed: Int = getColumnIndexOrThrow(_stmt, "chunksAllowed")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfBytesDownloaded: Int = getColumnIndexOrThrow(_stmt, "bytesDownloaded")
        val _columnIndexOfPartFilePath: Int = getColumnIndexOrThrow(_stmt, "partFilePath")
        val _columnIndexOfFinalFilePath: Int = getColumnIndexOrThrow(_stmt, "finalFilePath")
        val _columnIndexOfSupportsRanges: Int = getColumnIndexOrThrow(_stmt, "supportsRanges")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "errorMessage")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: MutableList<DownloadEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpProvider: ProviderId
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfProvider)
          _tmpProvider = __converters.stringToProvider(_tmp)
          val _tmpFilename: String
          _tmpFilename = _stmt.getText(_columnIndexOfFilename)
          val _tmpSourceRef: String
          _tmpSourceRef = _stmt.getText(_columnIndexOfSourceRef)
          val _tmpDownloadUrl: String
          _tmpDownloadUrl = _stmt.getText(_columnIndexOfDownloadUrl)
          val _tmpHost: String
          _tmpHost = _stmt.getText(_columnIndexOfHost)
          val _tmpFilesize: Long
          _tmpFilesize = _stmt.getLong(_columnIndexOfFilesize)
          val _tmpChunksAllowed: Int
          _tmpChunksAllowed = _stmt.getLong(_columnIndexOfChunksAllowed).toInt()
          val _tmpState: DownloadState
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfState)
          _tmpState = __converters.stringToState(_tmp_1)
          val _tmpBytesDownloaded: Long
          _tmpBytesDownloaded = _stmt.getLong(_columnIndexOfBytesDownloaded)
          val _tmpPartFilePath: String?
          if (_stmt.isNull(_columnIndexOfPartFilePath)) {
            _tmpPartFilePath = null
          } else {
            _tmpPartFilePath = _stmt.getText(_columnIndexOfPartFilePath)
          }
          val _tmpFinalFilePath: String?
          if (_stmt.isNull(_columnIndexOfFinalFilePath)) {
            _tmpFinalFilePath = null
          } else {
            _tmpFinalFilePath = _stmt.getText(_columnIndexOfFinalFilePath)
          }
          val _tmpSupportsRanges: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfSupportsRanges)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfSupportsRanges).toInt()
          }
          _tmpSupportsRanges = _tmp_2?.let { it != 0 }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item =
              DownloadEntity(_tmpId,_tmpProvider,_tmpFilename,_tmpSourceRef,_tmpDownloadUrl,_tmpHost,_tmpFilesize,_tmpChunksAllowed,_tmpState,_tmpBytesDownloaded,_tmpPartFilePath,_tmpFinalFilePath,_tmpSupportsRanges,_tmpErrorMessage,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun downloadingIds(): List<String> {
    val _sql: String = "SELECT rdId FROM downloads WHERE state = 'DOWNLOADING'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun hasPendingWork(): Boolean {
    val _sql: String =
        "SELECT EXISTS(SELECT 1 FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING'))"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Boolean
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp != 0
        } else {
          _result = false
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun remainingPendingBytes(): Long {
    val _sql: String =
        "SELECT COALESCE(SUM(MAX(filesize - bytesDownloaded, 0)), 0) FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING')"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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

  public override suspend fun completedIds(provider: ProviderId, ids: List<String>): List<String> {
    val _stringBuilder: StringBuilder = StringBuilder()
    _stringBuilder.append("SELECT rdId FROM downloads WHERE provider = ")
    _stringBuilder.append("?")
    _stringBuilder.append(" AND state = 'COMPLETED' AND rdId IN (")
    val _inputSize: Int = ids.size
    appendPlaceholders(_stringBuilder, _inputSize)
    _stringBuilder.append(")")
    val _sql: String = _stringBuilder.toString()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.providerToString(provider)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        for (_item: String in ids) {
          _stmt.bindText(_argIndex, _item)
          _argIndex++
        }
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item_1: String
          _item_1 = _stmt.getText(0)
          _result.add(_item_1)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun unfinishedIds(provider: ProviderId): List<String> {
    val _sql: String = "SELECT rdId FROM downloads WHERE provider = ? AND state != 'COMPLETED'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.providerToString(provider)
        _stmt.bindText(_argIndex, _tmp)
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateState(
    rdId: String,
    state: DownloadState,
    now: Long,
  ) {
    val _sql: String = "UPDATE downloads SET state = ?, updatedAt = ? WHERE rdId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.stateToString(state)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, rdId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setFailed(
    rdId: String,
    message: String?,
    state: DownloadState,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE downloads SET state = ?, errorMessage = ?, updatedAt = ? WHERE rdId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.stateToString(state)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        if (message == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, message)
        }
        _argIndex = 3
        _stmt.bindLong(_argIndex, now)
        _argIndex = 4
        _stmt.bindText(_argIndex, rdId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun requeueOrphans(from: DownloadState, to: DownloadState) {
    val _sql: String = "UPDATE downloads SET state = ? WHERE state = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.stateToString(to)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        val _tmp_1: String = __converters.stateToString(from)
        _stmt.bindText(_argIndex, _tmp_1)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateBytes(
    rdId: String,
    bytes: Long,
    now: Long,
  ) {
    val _sql: String = "UPDATE downloads SET bytesDownloaded = ?, updatedAt = ? WHERE rdId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, bytes)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, rdId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateLink(
    rdId: String,
    url: String,
    chunks: Int,
    filesize: Long,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE downloads SET downloadUrl = ?, chunksAllowed = ?, filesize = ?, updatedAt = ? WHERE rdId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, url)
        _argIndex = 2
        _stmt.bindLong(_argIndex, chunks.toLong())
        _argIndex = 3
        _stmt.bindLong(_argIndex, filesize)
        _argIndex = 4
        _stmt.bindLong(_argIndex, now)
        _argIndex = 5
        _stmt.bindText(_argIndex, rdId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteCompleted() {
    val _sql: String = "DELETE FROM downloads WHERE state = 'COMPLETED'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun pauseQueued(now: Long): Int {
    val _sql: String = "UPDATE downloads SET state = 'PAUSED', updatedAt = ? WHERE state = 'QUEUED'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun requeuePaused(now: Long): Int {
    val _sql: String = "UPDATE downloads SET state = 'QUEUED', updatedAt = ? WHERE state = 'PAUSED'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(rdId: String) {
    val _sql: String = "DELETE FROM downloads WHERE rdId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, rdId)
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
