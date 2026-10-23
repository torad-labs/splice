package splice.core.topology

/** A rule a decoded table breaks, raised from its own init. [key] is the setting's TOML name, so a config read can
 *  point at its line, and the message is splice's own sentence, which never quotes the value written: a read
 *  withholds the parser's text because that text can (Oct 22: a 0.3.2 config's retired effort_ceiling reached its
 *  upgrade as "the file cannot be decoded", naming neither the key nor the fix). */
public class TopologyRuleViolation(public val key: String, message: String) : IllegalArgumentException(message)
