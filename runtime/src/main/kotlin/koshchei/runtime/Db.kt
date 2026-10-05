package koshchei.runtime

import java.sql.Connection
import java.sql.DriverManager

/** Test code can mutate these to point at a Testcontainers instance. */
object DbConnectionOverride {
    var url: String? = null
    var user: String? = null
    var pass: String? = null
}

object Db {
    val url: String get() = DbConnectionOverride.url ?: System.getenv("KOSHCHEI_DB_URL") ?: "jdbc:postgresql://localhost:15433/koshchei"
    val user: String get() = DbConnectionOverride.user ?: System.getenv("KOSHCHEI_DB_USER") ?: "koshchei"
    val pass: String get() = DbConnectionOverride.pass ?: System.getenv("KOSHCHEI_DB_PASS") ?: "koshchei"

    fun connect(): Connection = DriverManager.getConnection(url, user, pass)
}
