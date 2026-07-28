package com.citation.core.oreilly

/**
 * The **auto-reauth brain** for library-proxied O'Reilly. A library EZproxy session is short-lived;
 * when it lapses, the proxy bounces you to an OCLC sign-in page that asks for your **library card and
 * PIN**. This object answers the two pure questions the WebView needs:
 *
 *  1. *Is this page the library login?* ([isLoginPage]) — so we know when to step in.
 *  2. *What script fills the card + PIN and submits?* ([fillScript]) — so returning is one tap, or
 *     zero when we auto-submit.
 *
 * This is the *user's own* library credential re-entered into the *library's* login form — nothing
 * here touches O'Reilly's DRM or its short-lived federated tokens (the design keeps content
 * uncached and licensed). It only spares you retyping your card number every time the proxy expires.
 *
 * All of it is framework-independent so it's unit-tested without a device; the WebView merely calls
 * `isLoginPage(url)` and `evaluateJavascript(fillScript(...))`.
 */
object EzproxyLogin {

    /** Value returned by [fillScript]'s JS to the `evaluateJavascript` callback. */
    object Result {
        const val NO_FORM = "no-form"
        const val FILLED = "filled"
        const val SUBMITTED = "submitted"
    }

    /**
     * Best-effort detection of an OCLC / EZproxy sign-in page. Recognises OCLC's hosted IDM login
     * (`login.idm.oclc.org`), an EZproxy `/login` endpoint on an `*.oclc.org` host, and the classic
     * EZproxy `/login?url=…` bounce. Deliberately conservative: a false negative just means you sign
     * in by hand this once, never a wrong auto-submit onto some other page's form.
     */
    fun isLoginPage(url: String): Boolean {
        val host = hostOf(url)?.lowercase() ?: return false
        val path = pathOf(url).lowercase()
        return when {
            host == "login.idm.oclc.org" -> true
            host.endsWith(".oclc.org") && path.startsWith("/login") -> true
            host.endsWith(".oclc.org") && path.startsWith("/connect/") -> true
            path.startsWith("/login") && url.contains("url=", ignoreCase = true) -> true
            else -> false
        }
    }

    /**
     * Build the JavaScript that fills the library [card] and [pin] into the sign-in form and, when
     * [autoSubmit] is true, submits it. The script probes a list of common field selectors (OCLC's
     * form field names vary by library), fills the first match, fires `input`/`change` so any
     * client-side validation notices, and returns one of [Result]'s tokens to the caller.
     *
     * Credentials are embedded as JSON string literals ([jsString]), so a card or PIN containing
     * quotes, backslashes, or angle brackets can't break out of the string or inject markup.
     */
    fun fillScript(card: String, pin: String, autoSubmit: Boolean = true): String {
        val cardLit = jsString(card)
        val pinLit = jsString(pin)
        val submit = if (autoSubmit) "true" else "false"
        return """
            (function(){
              var userSel=["input[name=user]","input[name=username]","input[name=userid]",
                "input[name=barcode]","input[name=cardnumber]","input#username","input#user",
                "input[type=email]","input[type=tel]","input[type=text]"];
              var pinSel=["input[name=pass]","input[name=password]","input[name=pin]",
                "input#password","input#pass","input[type=password]"];
              function first(sels){for(var i=0;i<sels.length;i++){
                var e=document.querySelector(sels[i]); if(e) return e;} return null;}
              function set(el,val){ if(!el) return; try{el.focus();}catch(e){}
                el.value=val;
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true})); }
              var u=first(userSel), p=first(pinSel);
              if(!p){ return "${Result.NO_FORM}"; }
              set(u, $cardLit);
              set(p, $pinLit);
              if($submit){
                var b=document.querySelector("button[type=submit],input[type=submit],button");
                if(b){ b.click(); return "${Result.SUBMITTED}"; }
                var f=p.form||(u&&u.form);
                if(f){ f.submit(); return "${Result.SUBMITTED}"; }
              }
              return "${Result.FILLED}";
            })();
        """.trimIndent()
    }

    /** Encode [s] as a JSON/JS string literal (with surrounding quotes), safe to embed in a script. */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                else -> when {
                    c.code == 0x2028 -> sb.append("\\u2028")
                    c.code == 0x2029 -> sb.append("\\u2029")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun hostOf(url: String): String? =
        Regex("^https?://([^/?#]+)").find(url)?.groupValues?.get(1)

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return "/"
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }
}
