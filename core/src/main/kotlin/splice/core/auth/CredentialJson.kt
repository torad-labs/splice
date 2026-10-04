// NEW: (SH-10, no Node source) "merge, never rewrite" as a property of ONE primitive. A
// from-scratch credential-file rewrite once dropped `expires` and every field the official CLI
// stores beside ours (the 2026-07-18 audit finding grok's mergedAuthJson comment records); kimi
// still did it. Providers overwrite only the keys their refresh response replaces; everything
// else on disk survives — foreign CLIs and future vendor fields included.
// Named object since the 2026-08-16 style migration (HD-M8): both arguments are kotlinx JsonObjects,
// a foreign receiver that cannot host a member. Same member name, same body, same merge order. The
// FILE moved with it (MergedCredentialJson.kt -> CredentialJson.kt) because detekt's
// MatchingDeclarationName wants a single-declaration file named after its declaration while
// MemberNameEqualsClassName forbids `object MergedCredentialJson { fun mergedCredentialJson }`;
// the SH-10 wall was pointed at the new path in the same commit.
package splice.core.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

public object CredentialJson {

    /** [onDisk]'s keys survive unless [replacements] carries them; null onDisk (unreadable file —
     *  the caller logs it) degrades to the replacements-only object rather than throwing. */
    public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject =
        buildJsonObject {
            onDisk?.forEach { (k, v) -> if (k !in replacements) put(k, v) }
            replacements.forEach { (k, v) -> put(k, v) }
        }
}
