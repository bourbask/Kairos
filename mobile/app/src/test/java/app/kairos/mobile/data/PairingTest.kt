package app.kairos.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    @Test
    fun parse_valid_uri() {
        val c = Pairing.parse("kairos://pair?url=http%3A%2F%2Fh%3A1%2Fx&token=abc123").getOrThrow()
        assertEquals(Credentials("http://h:1/x", "abc123"), c)
    }

    @Test
    fun parse_accepts_capitalised_scheme_and_spaces() {
        // Saisie au clavier : majuscule automatique et espaces parasites.
        val c = Pairing.parse("  Kairos://Pair?url=http%3A%2F%2Fh%3A1&token=abc ").getOrThrow()
        assertEquals("abc", c.token)
    }

    @Test
    fun parse_rejects_other_scheme() {
        assertTrue(Pairing.parse("https://pair?url=http%3A%2F%2Fh&token=abc").isFailure)
        assertTrue(Pairing.parse("kairos://autre?url=http%3A%2F%2Fh&token=abc").isFailure)
        assertTrue(Pairing.parse("kairos://pair?url=ftp%3A%2F%2Fh&token=abc").isFailure)
        assertTrue(Pairing.parse("kairos://pair?url=javascript%3Aalert(1)&token=abc").isFailure)
        // Accepté par java.net.URI, refusé par le client HTTP : doit échouer ici, pas planter plus loin.
        assertTrue(Pairing.parse("kairos://pair?url=http%3A%2F%2Fh%3A99999&token=abc").isFailure)
    }

    @Test
    fun parse_rejects_empty_token() {
        assertTrue(Pairing.parse("kairos://pair?url=http%3A%2F%2Fh&token=").isFailure)
        assertTrue(Pairing.parse("kairos://pair?url=http%3A%2F%2Fh").isFailure)
        assertTrue(Pairing.parse("kairos://pair?url=http%3A%2F%2Fh&token=%20").isFailure)
    }
}
