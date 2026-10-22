package splice.http.listen

import io.ktor.server.netty.NettyApplicationEngine

// Each loopback listener accepts locally; it must not allocate another machine-sized thread pool per head.
private const val ACCEPT_THREADS = 1

// Socket events and application dispatch stay independent, with another thread available while one is occupied.
private const val SOCKET_THREADS = 2

// Route bodies leave these connection-affine executors before blocking; keep a second executor for independent clients.
private const val APPLICATION_THREADS = 2

/** Sizes each engine's own groups without sharing socket-adoption ownership or limiting concurrent requests. */
public object HttpListenerThreads {
    public fun configure(engine: NettyApplicationEngine.Configuration) {
        engine.connectionGroupSize = ACCEPT_THREADS
        engine.workerGroupSize = SOCKET_THREADS
        engine.callGroupSize = APPLICATION_THREADS
    }
}
