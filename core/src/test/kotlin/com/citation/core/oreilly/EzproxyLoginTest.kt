package com.citation.core.oreilly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EzproxyLoginTest {

    @Test
    fun recognisesOclcIdmLoginHost() {
        assertTrue(EzproxyLogin.isLoginPage("https://login.idm.oclc.org/auth/?whatever"))
    }

    @Test
    fun recognisesEzproxyLoginPathOnOclcHost() {
        assertTrue(EzproxyLogin.isLoginPage("https://mcpl.idm.oclc.org/login?url=https://learning.oreilly.com/"))
    }

    @Test
    fun recognisesBareEzproxyLoginBounce() {
        assertTrue(EzproxyLogin.isLoginPage("https://ezproxy.example.edu/login?url=https://learning.oreilly.com/"))
    }

    @Test
    fun theBookItselfIsNotALoginPage() {
        assertFalse(EzproxyLogin.isLoginPage("https://learning-oreilly-com.mcpl.idm.oclc.org/library/view/-/9781492082279/"))
        assertFalse(EzproxyLogin.isLoginPage("https://learning.oreilly.com/library/view/-/9781492082279/"))
    }

    @Test
    fun nonUrlIsNotALoginPage() {
        assertFalse(EzproxyLogin.isLoginPage("not a url"))
    }

    @Test
    fun recognisesMcplLoginPageAtLoginPath() {
        // The real Mid-Continent form posts to https://mcpl.idm.oclc.org/login (method=post).
        assertTrue(EzproxyLogin.isLoginPage("https://mcpl.idm.oclc.org/login?url=https://learning.oreilly.com/"))
    }

    @Test
    fun fillScriptTargetsTheRealMcplFieldIds() {
        // MCPL's OCLC form: card is name=user id=cardnum (itself type=password), PIN is name=pass id=pin.
        val js = EzproxyLogin.fillScript("21234567890123", "1984")
        assertTrue("card selector #cardnum present", js.contains("#cardnum"))
        assertTrue("pin selector #pin present", js.contains("#pin"))
        assertTrue(js.contains("input[name=user]"))
        assertTrue(js.contains("input[name=pass]"))
        // The card list must not fall back to type=password (that's the card field here too).
        val userList = js.substringAfter("userSel=[").substringBefore("]")
        assertFalse("card must not select by type=password", userList.contains("type=password"))
    }

    @Test
    fun fillScriptEmbedsCredentialsAndSubmits() {
        val js = EzproxyLogin.fillScript("21234567890123", "1984", autoSubmit = true)
        assertTrue(js.contains("\"21234567890123\""))
        assertTrue(js.contains("\"1984\""))
        assertTrue(js.contains("input[type=password]"))
        assertTrue(js.contains(EzproxyLogin.Result.SUBMITTED))
    }

    @Test
    fun fillScriptCanFillWithoutSubmitting() {
        val js = EzproxyLogin.fillScript("card", "pin", autoSubmit = false)
        // With autoSubmit off the submit branch is compiled out to `if(false)`, so it never clicks.
        assertTrue(js.contains("if(false)"))
    }

    @Test
    fun credentialsAreSafelyEscaped() {
        // A PIN with a quote and a closing-tag sequence must not break out of the JS string.
        val js = EzproxyLogin.fillScript("card", "a\"b</script>", autoSubmit = false)
        assertFalse(js.contains("a\"b</script>"))
        assertTrue(js.contains("a\\\"b\\u003c/script\\u003e"))
    }

    @Test
    fun jsStringEscapesBackslashesAndQuotes() {
        assertEquals("\"a\\\\b\\\"c\"", EzproxyLogin.jsString("a\\b\"c"))
    }
}
