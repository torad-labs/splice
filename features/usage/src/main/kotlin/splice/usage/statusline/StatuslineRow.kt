package splice.usage.statusline

import splice.core.model.ModelCatalog

/** Use the configured label for a declared row; otherwise keep the client's name. */
internal class StatuslineRow(private val catalog: ModelCatalog?) {
    fun label(id: String?): String? = id?.takeIf { catalog?.contains(it) == true }?.let { catalog?.labelFor(it) }
}
