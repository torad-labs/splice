// NEW: (SH-10, no Node source) "merge, never rewrite" as a property of ONE primitive. A
// from-scratch credential-file rewrite once dropped `expires` and every field the official CLI
// stores beside ours (the 2026-07-18 audit finding grok's mergedAuthJson comment records); kimi
// still did it. Providers overwrite only the keys their refresh response replaces; everything
// else on disk survives — foreign CLIs and future vendor fields included.
package splice.core.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** [onDisk]'s keys survive unless [replacements] carries them; null onDisk (unreadable file —
 *  the caller logs it) degrades to the replacements-only object rather than throwing. */
public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject =
    buildJsonObject {
        onDisk?.forEach { (k, v) -> if (k !in replacements) put(k, v) }
        replacements.forEach { (k, v) -> put(k, v) }
    }
