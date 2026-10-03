package ng.cvgfc.api

import ng.cvgfc.api.config.DatabaseUrlPostProcessor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseUrlTest {
    @Test
    fun `railway style urls become jdbc plus credentials`() {
        val p = DatabaseUrlPostProcessor.parse("postgresql://postgres:s3cr%40t@postgres.railway.internal:5432/railway")!!
        assertEquals("jdbc:postgresql://postgres.railway.internal:5432/railway", p["spring.datasource.url"])
        assertEquals("postgres", p["spring.datasource.username"])
        assertEquals("s3cr@t", p["spring.datasource.password"])
        assertEquals("jdbc:postgresql://db:5432/cvg?sslmode=require", DatabaseUrlPostProcessor.parse("postgres://u:p@db/cvg?sslmode=require")!!["spring.datasource.url"])
        assertNull(DatabaseUrlPostProcessor.parse("jdbc:postgresql://localhost:5432/cvg"), "jdbc URLs pass through")
    }
}
