// NEW: #791 (server mode, F9). How the notice a server listener hands its head in SERVED_NOTICE_HEADER crosses that one
// HTTP header: an operator's `outdated_message` may be in any language, and a header value carries only visible ASCII,
// so the text goes as RFC 8187's `UTF-8''` and its bytes percent-encoded (FormEncoding), and comes back as the text it
// was. A value without that prefix is read as it is, so a plain ASCII notice from an older listener still reads.
package splice.core.turn

import splice.core.util.FormEncoding
import java.net.URLDecoder

public class ServedNoticeText {
    /** [text] as a header value: `UTF-8''` and every byte but a letter, a digit or `-._~` as `%XX`. */
    public fun encode(text: String): String = NOTICE_ENCODED_PREFIX + FormEncoding.percentEncode(text)

    /** The text a header [value] carries: an `UTF-8''` value decoded, any other as it is. A value whose escapes are
     *  broken is no notice, and reads as null. */
    public fun decode(value: String): String? {
        if (!value.startsWith(NOTICE_ENCODED_PREFIX, ignoreCase = true)) return value
        return try {
            URLDecoder.decode(value.substring(NOTICE_ENCODED_PREFIX.length), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private const val NOTICE_ENCODED_PREFIX = "UTF-8''"
