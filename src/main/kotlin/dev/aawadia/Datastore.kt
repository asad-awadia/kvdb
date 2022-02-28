package dev.aawadia

import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.*
import jetbrains.exodus.util.CompressBackupUtil
import java.io.File
import java.util.concurrent.ConcurrentSkipListMap

class Datastore(
  private val dbDir: String = "./db",
  private val ssdtable: Environment = Environments.newInstance(dbDir, getEnvConfig()),
  private val memtable: ConcurrentSkipListMap<String, String> = ConcurrentSkipListMap(),
  private val store: String = "datastore",
) {
  init {
    populateMemtable()
  }

  fun get(key: String): String = memtable[key] ?: ""
  fun getKeyRange(from: String, to: String) = memtable.subMap(from, to) ?: emptyMap()

  fun insert(key: String, value: String) {
    ssdtable.executeInTransaction {
      getStore(it).put(it, key.toByteIterable(), value.toByteIterable())
    }
    memtable[key] = value
  }

  fun delete(key: String) {
    ssdtable.executeInTransaction {
      getStore(it).delete(it, key.toByteIterable())
    }
    memtable.remove(key)
  }

  fun takeBackup() = CompressBackupUtil.backup(ssdtable, File(ssdtable.location), "backups", false)
  fun close() = ssdtable.close()

  fun getDbStats(): MutableMap<String, Any> {
    val stats = mutableMapOf<String, Any>()
    EnvironmentStatistics.Type.values().forEach {
      stats[it.name] = ssdtable.statistics.getStatisticsItem(it)
    }
    stats["key_count"] = memtable.count()
    return stats
  }

  private fun populateMemtable() {
    memtable["init.ts"] = System.currentTimeMillis().toString()
    ssdtable.executeInTransaction {
      getStore(it).openCursor(it).use { cursor ->
        while (cursor.next) {
          memtable[StringBinding.entryToString(cursor.key)] = StringBinding.entryToString(cursor.value)
        }
      }
    }
  }

  private fun getStore(transaction: Transaction) =
    ssdtable.openStore(store, StoreConfig.WITHOUT_DUPLICATES_WITH_PREFIXING, transaction)
}

private fun String.toByteIterable(): ArrayByteIterable = StringBinding.stringToEntry(this)