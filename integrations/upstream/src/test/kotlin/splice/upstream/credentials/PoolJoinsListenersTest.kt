// NEW: gateway-no-heads. One pool's books can be served by more than one route (a legacy head and the gateway's runtime
// for the same provider), and each wakes its own held turns when a login joins, so a second registration must never
// replace the first.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PoolJoinsListenersTest {
    @Test
    fun `every route that registered hears a login join, not only the last`() {
        val joins = PoolJoins()
        val first = AtomicInteger()
        val second = AtomicInteger()
        joins.whenJoined { first.incrementAndGet() }
        joins.whenJoined { second.incrementAndGet() }

        joins.joined()

        assertEquals(1, first.get(), "the route that registered first still hears it")
        assertEquals(1, second.get())
        assertEquals(1L, joins.count, "a join is one join however many routes hear it")
    }

    @Test
    fun `a pool nobody registered on says nothing and still counts the join`() {
        val joins = PoolJoins()

        joins.joined()

        assertEquals(1L, joins.count)
    }
}
