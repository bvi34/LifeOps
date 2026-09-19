package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four headers the world actually writes, each read from a file shaped like the real one.
 *
 * The dialect assertions are the least of it. What matters here is that the same reader gets a
 * password out of all four without being told which is which, and that the files it should refuse —
 * a spreadsheet of something else, a row with nothing in it — are refused rather than imported as
 * empty items.
 */
class CredentialCsvTest {

    private val now = 1_700_000_000_000L

    private fun read(text: String) = CredentialCsv.read(text, now, ids())

    private fun ids(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    @Test
    fun `a chrome export`() {
        val read = read(
            "name,url,username,password,note\n" +
                "Bank,https://www.bank.example/login,me@example.com,hunter2,\n" +
                "Shop,https://shop.example/,shopper,s3cret,\"bought a, thing\"\n"
        )!!

        assertEquals(VaultImport.Format.CHROMIUM_CSV, read.format)
        assertEquals(2, read.items.size)
        assertEquals("Bank", read.items[0].title)
        assertEquals("me@example.com", read.items[0].username)
        assertEquals("hunter2", read.items[0].secret)
        assertEquals("https://www.bank.example/login", read.items[0].url)
        assertEquals(VaultItemKind.LOGIN, read.items[0].kind)
        assertEquals("bought a, thing", read.items[1].note)
    }

    @Test
    fun `a firefox export has no title column, so the address becomes one`() {
        val read = read(
            "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\"," +
                "\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
                "\"https://bank.example\",\"me\",\"hunter2\",,\"https://bank.example\",\"{x}\"," +
                "\"1500000000000\",\"1600000000000\",\"1550000000000\"\n"
        )!!

        assertEquals(VaultImport.Format.FIREFOX_CSV, read.format)
        assertEquals("bank.example", read.items[0].title)
        assertEquals("hunter2", read.items[0].secret)
    }

    @Test
    fun `when the file says when the password changed, that is what the item says`() {
        val read = read(
            "url,username,password,httpRealm,formActionOrigin,timeCreated,timePasswordChanged\n" +
                "https://bank.example,me,hunter2,,,1500000000000,1550000000000\n"
        )!!

        // Not the import time: the audit scores age from this, and an import that called every
        // password fresh would be an audit with nothing to say on the day it is most needed.
        assertEquals(1_550_000_000_000L, read.items[0].updatedAt)
        assertEquals(1_500_000_000_000L, read.items[0].createdAt)
    }

    @Test
    fun `a date in the future is not a date somebody meant`() {
        val read = read(
            "url,username,password,httpRealm,timePasswordChanged\n" +
                "https://bank.example,me,hunter2,,3500000000000\n"
        )!!

        // Zero rather than the moment of the import: "the file did not say" is a fact the plan
        // needs, and a row stamped with today would outrank every password in the vault.
        assertEquals(0L, read.items[0].updatedAt)
    }

    @Test
    fun `a file with no dates at all leaves them unset`() {
        val read = read("name,url,username,password,note\nBank,https://b.example,me,x,\n")!!

        assertEquals(0L, read.items[0].updatedAt)
        assertEquals(0L, read.items[0].createdAt)
    }

    @Test
    fun `an apple export brings its second factor with it`() {
        val read = read(
            "Title,URL,Username,Password,Notes,OTPAuth\n" +
                "Bank,https://bank.example,me,hunter2,,otpauth://totp/Bank:me?secret=JBSWY3DPEHPK3PXP&issuer=Bank\n"
        )!!

        assertEquals(VaultImport.Format.APPLE_CSV, read.format)
        val totp = read.items[0].totp
        assertNotNull(totp)
        assertEquals("JBSWY3DPEHPK3PXP", totp!!.secret)
        assertEquals("Bank", totp.issuer)
    }

    @Test
    fun `a bare base32 seed in the otp column is taken too`() {
        val read = read("Title,Username,Password,OTPAuth\nBank,me,hunter2,jbswy3dpehpk3pxp\n")!!

        assertEquals("jbswy3dpehpk3pxp", read.items[0].totp?.secret)
    }

    @Test
    fun `a 1password export keeps tags and favourites, and leaves the archive alone`() {
        val read = read(
            "Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes\n" +
                "Bank,https://bank.example,me,hunter2,,true,false,\"money,daily\",\n" +
                "Old forum,https://forum.example,me,letmein,,false,true,,\n"
        )!!

        assertEquals(VaultImport.Format.ONEPASSWORD_CSV, read.format)
        assertEquals(1, read.items.size)
        assertTrue(read.items[0].favourite)
        assertEquals(listOf("money", "daily"), read.items[0].tags)
        assertEquals(1, read.skipped.size)
        assertEquals("Old forum", read.skipped[0].what)
    }

    @Test
    fun `a row with nothing in it is reported rather than imported`() {
        val read = read("name,url,username,password,note\nEmpty,https://x.example,,,\n")!!

        assertTrue(read.items.isEmpty())
        assertEquals(1, read.skipped.size)
    }

    @Test
    fun `a row with only a note becomes a note`() {
        val read = read("name,url,username,password,note\nSafe,,,,\"the code is 1234\"\n")!!

        assertEquals(VaultItemKind.NOTE, read.items[0].kind)
        assertEquals("the code is 1234", read.items[0].note)
    }

    @Test
    fun `a file with no password, username or note column is not a password export`() {
        assertNull(read("date,payee,amount\n2026-01-01,Shop,12.40\n"))
    }

    @Test
    fun `a file with only a header is nothing`() {
        assertNull(read("name,url,username,password,note\n"))
    }

    @Test
    fun `an unrecognised header still imports if it says which column is the password`() {
        val read = read("Account,Login,Password\nBank,me,hunter2\n")!!

        assertEquals(VaultImport.Format.GENERIC_CSV, read.format)
        assertEquals("me", read.items[0].username)
        assertEquals("hunter2", read.items[0].secret)
        // `Account` is in the username list as well, but `Login` is ahead of it, so the column that
        // says what it is wins and the title falls back to the username.
        assertEquals("me", read.items[0].title)
    }

    @Test
    fun `nothing imported is a favourite unless the file says so`() {
        val read = read("name,url,username,password,note\nBank,https://b.example,me,x,\n")!!

        assertFalse(read.items[0].favourite)
    }
}
