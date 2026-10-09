package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.RecordedRequest
import java.net.URLDecoder

/** The urlencoded form this request posted (a GraphQL query's), decoded, by field name. Every test that reads one uses this. */
internal fun RecordedRequest.formFields(): Map<String, String> =
    body!!.utf8().split('&').associate { field ->
        val (name, value) = field.split('=', limit = 2)
        URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }
