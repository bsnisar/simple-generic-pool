package pool;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class PoolV2<R> implements Pool<R> {


    /**
     * Entries state.
     */
    private final Map<R, PooledEntry> refs = new ConcurrentHashMap<>();

    /**
     * acquire -> release handoff
     */
    private final BlockingQueue<R> handOff = new LinkedBlockingQueue<>();

    /**
     * open-close state.
     */
    private final ReadWriteLock openCloseLock;

    @SuppressWarnings("WeakerAccess")
    public PoolV2() {
        openCloseLock = new ReentrantReadWriteLock();
    }

    enum State {
        IN_USE,
        NOT_IN_USE,
        TOMBSTONE
    }

    @SuppressWarnings("WeakerAccess")
    static class PooledEntry {
        private final AtomicReference<State> state = new AtomicReference<>(State.NOT_IN_USE);
        private final CountDownLatch latch = new CountDownLatch(1);
    }


    @Override
    public R acquire() throws InterruptedException {
        return doAcquire(0, TimeUnit.MILLISECONDS);
    }

    @Override
    public R acquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        return doAcquire(timeout, timeUnit);
    }

    private R doAcquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long startTime = System.nanoTime();
        boolean pollForever = timeout <= 0;
        timeout = timeUnit.toNanos(timeout);

        do {
            final R item = pollForever
                    ? handOff.take()
                    : handOff.poll(timeout, TimeUnit.NANOSECONDS);

            if (item == null) {
                return null;
            }

            PooledEntry compute = refs.compute(item, (k, entry) -> {
                if (entry == null) {
                    return null; // mapping have been just removed, try again
                }

                entry.state.set(State.IN_USE);
                return entry;
            });

            if (compute != null) {
                return item;
            }

            if (!pollForever) {
                timeout = timeout - elapsedNanos(startTime);
            }

        } while (pollForever || timeout > 0);


        return null;
    }


    private long elapsedNanos(long startTime) {
        return System.nanoTime() - startTime;
    }


    @Override
    public void release(R resource) {
        final PooledEntry updated = refs.compute(resource, (k, entry) -> {
            if (entry == null) {
                return null;
            }

            if (entry.state.get() == State.TOMBSTONE) {
                entry.latch.countDown();
                return null;
            }

            entry.state.set(State.NOT_IN_USE);
            return entry;
        });

        if (updated != null) {
            handOff.offer(resource);
        }
    }

    @Override
    public boolean remove(R resource) throws InterruptedException {
        return doRemove(resource, true);
    }


    @Override
    public boolean removeNow(R resource) throws InterruptedException {
        return doRemove(resource, false);
    }


    private boolean doRemove(R resource, boolean await) throws InterruptedException {
        final PooledEntry mapping = refs.compute(resource, (k, entry) -> {
            if (entry == null) {
                return null;
            }

            if (entry.state.get() == State.IN_USE) {
                entry.state.set(State.TOMBSTONE);
                return entry;
            }
            // remove mapping
            return null;
        });

        if (await && mapping != null) {
            // await for the latch count down
            // mapping will be removed by release method
            mapping.latch.await();
            return true;
        }

        //TODO how to track updated status?
        return false;
    }


    @Override
    public boolean add(R resource) {
        final PooledEntry mapping = refs.putIfAbsent(resource, new PooledEntry());
        if (mapping == null) {
            handOff.add(resource);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void closeNow() {

    }


    @Override
    public void open() {

    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void close() throws InterruptedException {

    }

}

