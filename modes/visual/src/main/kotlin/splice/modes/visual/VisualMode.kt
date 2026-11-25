// NEW: the visual mode (modes/visual/mode.toml): read_me gives the guidance, show_widget draws an HTML or SVG fragment,
// and html_render draws a whole page and keeps it. A client that draws MCP Apps draws both in resources/reply.html from
// the call's own arguments; any other client shows the answer, which says what was drawn and asks the model to say it
// in words. Two management routes give the view and a kept page to a host that reads them by address.
package splice.modes.visual

import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeRoute

private const val READ_ME = "read_me"
private const val SHOW_WIDGET = "show_widget"
private const val HTML_RENDER = "html_render"

/** The management routes: `GET /api/visual/view?uri=<ui://…>` and `GET /api/visual/pages/<vp_…>`. */
private const val VIEW_ROUTE = "/api/visual/view"
private const val PAGES_ROUTE = "/api/visual/pages/{id}"

/** What a html_render call naming `path` is told. The mode runs inside the daemon, and an mcp mode's call carries
 *  neither the session's folder nor who is calling, so a file read on the model's word could hand a caller a file it
 *  could not read itself. */
private const val NO_PATH =
    "html_render takes the page itself as html, and this version reads no file: pass the page's whole HTML as html."

internal class VisualMode : Mode {
    private val guide = VisualGuide()
    private val pages = VisualPages()
    private val view = VisualView()

    override fun answer(call: ModeCall): ModeAnswer = when (call.tool) {
        READ_ME -> guide.answer(call.input)
        SHOW_WIDGET -> widget(call)
        HTML_RENDER -> page(call)
        else -> ModeAnswer.Error("there is no tool ${call.tool} in this mode")
    }

    override val routes: List<ModeRoute> = listOf(
        ModeRoute("GET", VIEW_ROUTE, view::answer),
        ModeRoute("GET", PAGES_ROUTE, pages::answer),
    )

    private fun widget(call: ModeCall): ModeAnswer =
        when (val drawing = DrawingRead(SHOW_WIDGET, "widget_code").read(call.input)) {
            is Drawing.Refused -> ModeAnswer.Error(drawing.why)
            is Drawing.Ready -> ModeAnswer.Text(
                "\"${drawing.title}\" is drawn inline in the conversation by clients that draw MCP Apps. Any other " +
                    "client shows only this answer, so your reply should say in words what the visual shows.",
            )
        }

    private fun page(call: ModeCall): ModeAnswer {
        if ("path" in call.input) return ModeAnswer.Error(NO_PATH)
        return when (val drawing = DrawingRead(HTML_RENDER, "html").read(call.input)) {
            is Drawing.Refused -> ModeAnswer.Error(drawing.why)
            is Drawing.Ready -> {
                val id = pages.keep(call.store, drawing)
                ModeAnswer.Text(
                    "Kept the page \"${drawing.title}\" as $id. Clients that draw MCP Apps draw it in the " +
                        "conversation. Any other client shows only this answer, so your reply should say in words " +
                        "what the page holds.",
                )
            }
        }
    }
}
