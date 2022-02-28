package dev.aawadia

import org.junit.Test
import java.util.*

class DatastoreTest {
  @Test
  fun testDS() {
    val ds = Datastore(dbDir = "./${UUID.randomUUID()}")
    ds.insert("foo", "bar")
    assert(ds.get("foo") == "bar")
    ds.delete("foo")
    assert(ds.get("foo") == "")
    ds.close()
  }
}
