package app.kairos.mobile.data

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CacheCodecTest {
    @Test
    fun roundTripAndRejects() {
        val sealed = testCodec.seal("soldes 1234")
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("1234"))
        assertEquals("soldes 1234", testCodec.open(sealed))
        // Clair, trop court, altéré ou autre clé : illisible, jamais d'exception.
        assertNull(testCodec.open("{\"a\":1}".toByteArray()))
        assertNull(testCodec.open(ByteArray(3)))
        assertNull(testCodec.open(sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(CacheCodec { other }.open(sealed))
    }
}
