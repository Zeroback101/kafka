package org.apache.kafka.server.common;

import org.apache.kafka.common.utils.BufferSupplier;

import java.util.Objects;

/**
 * Container for stateful instances where the lifecycle is scoped to one request.
 * When each request is handled by one thread, efficient data structures with no locking or atomic operations
 * can be used (see RequestLocal.withThreadConfinedCaching).
 */
public class RequestLocal implements AutoCloseable {
    private static final RequestLocal NO_CACHING = new RequestLocal(BufferSupplier.NO_CACHING);

    private final BufferSupplier bufferSupplier;

    public RequestLocal(BufferSupplier bufferSupplier) {
        this.bufferSupplier = bufferSupplier;
    }

    public static RequestLocal noCaching() {
        return NO_CACHING;
    }

    /** The returned instance should be confined to a single thread. */
    public static RequestLocal withThreadConfinedCaching() {
        return new RequestLocal(BufferSupplier.create());
    }

    public BufferSupplier bufferSupplier() {
        return bufferSupplier;
    }

    @Override
    public void close() {
        bufferSupplier.close();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestLocal that = (RequestLocal) o;
        return Objects.equals(bufferSupplier, that.bufferSupplier);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(bufferSupplier);
    }

    @Override
    public String toString() {
        return "RequestLocal(bufferSupplier=" + bufferSupplier + ')';
    }
}
