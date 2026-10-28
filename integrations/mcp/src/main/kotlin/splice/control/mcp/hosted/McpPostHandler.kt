package splice.control.mcp.hosted

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpSharing
import splice.control.mcp.GlobalMcpServers
import splice.control.mcp.HostedServer
import splice.control.mcp.HostedServers
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpAdmission
import splice.control.mcp.McpClient
import splice.control.mcp.McpHostMessage
import splice.control.mcp.McpReply
import splice.control.mcp.McpResult
import splice.control.mcp.McpSession
import splice.control.mcp.McpSessions
import splice.control.mcp.RPC_SERVER_ERROR
import splice.control.mcp.RPC_TIMED_OUT
import splice.control.mcp.RpcKind
import splice.control.mcp.SessionMinting
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.wire.HttpStatus

// why: JSON-RPC specifies -32600 for an invalid request envelope.
private const val RPC_INVALID = -32600

// why: the host's existing unknown-session error identifies an unavailable client session.
private const val RPC_UNKNOWN_SESSION = -32001

// why: an MCP request carries its complete response in an HTTP 200 body.
private const val HTTP_OK = 200

// why: an accepted notification or client response has no JSON-RPC reply body.
private const val HTTP_ACCEPTED = 202

/** Keeps admission, initialization, and session routing below McpHost's lifecycle surface. */
internal class McpPostHandler(
    private val codec: JsonRpcCodec,
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val servers: HostedServers,
    private val sessions: McpSessions,
    private val minting: SessionMinting,
    private val log: LogSink,
) {
    suspend fun post(
        name: String,
        sessionId: String?,
        body: String,
        protocolVersion: String?,
        head: String?,
    ): McpReply {
        val msg = codec.parse(body)
            ?: return bad(HttpStatus.BAD_REQUEST, JsonPrimitive(0), RPC_INVALID, "not a JSON-RPC object")
        val message = McpHostMessage(msg, codec)
        if (message.kind == RpcKind.INVALID) {
            return bad(HttpStatus.BAD_REQUEST, message.id, RPC_INVALID, "invalid JSON-RPC")
        }
        val admission = admission(name, sessionId, message)
        return route(name, McpClient(sessionId, protocolVersion, head), msg, message, admission)
    }

    private suspend fun admission(name: String, sessionId: String?, message: McpHostMessage): McpAdmission? =
        if (message.settlement) null else servers.replacements.admit(name, sessions.get(name, sessionId))

    private suspend fun route(
        name: String,
        client: McpClient,
        msg: JsonObject,
        message: McpHostMessage,
        admission: McpAdmission?,
    ): McpReply {
        if (!accepts(name, msg, message, admission)) {
            return bad(
                HttpStatus.SERVICE_UNAVAILABLE,
                message.id,
                RPC_SERVER_ERROR,
                "no hosted MCP admission for '$name'",
            )
        }
        return try {
            when {
                initializes(message.kind, msg) -> initialize(name, msg, client.head, admission)
                !protocolAccepted(name, client.sessionId, client.protocolVersion) -> bad(
                    HttpStatus.BAD_REQUEST,
                    message.id,
                    RPC_INVALID,
                    "unsupported MCP-Protocol-Version '${client.protocolVersion}'",
                )
                else -> forSession(name, client, msg, message.kind, admission)
            }
        } finally {
            admission?.leave()
        }
    }

    private fun accepts(name: String, msg: JsonObject, message: McpHostMessage, admission: McpAdmission?): Boolean {
        val unknownInitialize = initializes(message.kind, msg) && sharing.hostedSpec(global.inputs(), name) == null
        return message.accepts(admission) || unknownInitialize
    }

    private fun initializes(kind: RpcKind, msg: JsonObject): Boolean =
        kind == RpcKind.REQUEST && codec.method(msg) == "initialize"

    /** An unknown session must reach the session path's 404 before protocol validation. */
    fun protocolAccepted(name: String, sessionId: String?, protocolVersion: String?): Boolean {
        val session = sessions.get(name, sessionId) ?: return true
        return session.adopted || protocolVersion == null || session.protocolVersion == protocolVersion
    }

    private suspend fun initialize(name: String, msg: JsonObject, head: String?, admission: McpAdmission?): McpReply {
        val id = msg.getValue("id")
        val minted = minting.mint(name, null, head, admission)
        val code = if (minted.timedOut) RPC_TIMED_OUT else RPC_SERVER_ERROR
        return minted.session?.let { McpReply(HTTP_OK, codec.encode(codec.result(id, it.initResult)), it.id) }
            ?: bad(HttpStatus.SERVICE_UNAVAILABLE, id, code, minted.failure)
    }

    private suspend fun forSession(
        name: String,
        client: McpClient,
        msg: JsonObject,
        kind: RpcKind,
        admission: McpAdmission?,
    ): McpReply {
        val id = msg["id"] ?: JsonPrimitive(0)
        val sessionId = client.sessionId
        val session = sessions.get(name, sessionId)
            ?: minting.adopt(name, client, kind, admission)
            ?: return bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
        sessions.touch(session)
        val server = servers.reserve(name)
            ?: return bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "server not hosted")
        return try {
            if (sessions.get(name, sessionId) !== session) {
                bad(HttpStatus.NOT_FOUND, id, RPC_UNKNOWN_SESSION, "session not found")
            } else {
                when (kind) {
                    RpcKind.REQUEST -> forward(server, session, msg)
                    RpcKind.NOTIFICATION -> notification(name, server, session, msg)
                    RpcKind.RESPONSE, RpcKind.INVALID -> McpReply(HTTP_ACCEPTED, null)
                }
            }
        } finally {
            sessions.touch(session)
            servers.release(name, server)
        }
    }

    /** 202 when the child took it; 503 in words when it could not — never a silent drop. */
    private suspend fun notification(
        name: String,
        server: HostedServer,
        session: McpSession,
        msg: JsonObject,
    ): McpReply {
        val delivered = when (val sent = server.notify(session.id, msg)) {
            is McpResult.Served -> sent.value
            is McpResult.Refused -> {
                log("[mcp-host] ${LogSafe.str(name)}: notify failed (${LogSafe.str(sent.reason)})\n")
                false
            }
        }
        return if (delivered) {
            McpReply(HTTP_ACCEPTED, null)
        } else {
            bad(
                HttpStatus.SERVICE_UNAVAILABLE,
                JsonPrimitive(0),
                RPC_SERVER_ERROR,
                "hosted MCP server '$name' is not running",
            )
        }
    }

    private suspend fun forward(server: HostedServer, session: McpSession, msg: JsonObject): McpReply {
        val id = msg.getValue("id")
        val answer = server.call(session.id, id, msg)
        sessions.touch(session)
        return McpReply(HTTP_OK, codec.encode(answer), session.id)
    }

    private fun bad(status: Int, id: JsonElement, code: Int, message: String) =
        McpReply(status, codec.encode(codec.error(id, code, message)))
}
