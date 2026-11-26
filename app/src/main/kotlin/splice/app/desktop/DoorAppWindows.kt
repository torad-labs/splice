package splice.app.desktop

import io.ktor.http.Headers
import splice.app.desktop.catalog.DesktopLineup
import splice.client.desktop.ANTHROPIC_BETA_HEADER
import splice.client.desktop.APP_MODE_HEADER
import splice.client.desktop.DesktopAppWindow
import splice.core.model.ModelServeWindows
import splice.core.model.ModelTierSuffix
import splice.core.topology.desktop.DesktopConfig

/** The request identifies app mode before routing; the routed row tells us which variants the client can pick. */
internal class DoorAppWindows(desktop: DesktopConfig) {
    private val windows = DesktopAppWindow(desktop)

    fun app(headers: Headers): Boolean = windows.app(headers[APP_MODE_HEADER])

    fun resolve(headers: Headers, ask: DoorAsk, lineup: DesktopLineup, route: DoorRoute): DoorAsk {
        if (!ask.appMode) return ask
        val beta = headers.getAll(ANTHROPIC_BETA_HEADER).orEmpty().joinToString(",")
        val window = windows.of(headers[APP_MODE_HEADER], beta, ask.model, publishedWindow(lineup, route))
        return ask.copy(appWindow = window)
    }

    private fun publishedWindow(lineup: DesktopLineup, route: DoorRoute): Long {
        val id = ModelTierSuffix.strip(route.upstream)
        val published = lineup.catalog.models.firstOrNull { it.provider == route.provider.key && it.id == id }
        return published?.contextWindow ?: ModelServeWindows.forRow(route.provider.catalog, route.upstream)
    }
}
