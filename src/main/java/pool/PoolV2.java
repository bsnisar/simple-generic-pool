package pool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;


public class PoolV2<R> implements Pool<R> {



    private static final int POOL_IS_FRESH = 0;
    private static final int POOL_IS_OPEN = 1;
    private static final int POOL_CLOSED = 2;


    private final static PooledEntry TERMINATED = new PooledEntry() {
        @Override
        boolean allocate() {
            return false;
        }

        @Override
        boolean release() {
            return false;
        }

        @Override
        boolean markTombstone() {
            return false;
        }

        @Override
        Object value() {
            return null;
        }

        @Override
        EntryState state() {
            return null;
        }

        @Override
        void awaitRelease() throws InterruptedException {
        }
    };


    /**
     * Entries state.
     */
    private final ConcurrentHashMap<R, PooledEntry<R>> refs = new ConcurrentHashMap<>();

    /**
     * acquire -> release handoff
     */
    private final TransferQueue<PooledEntry<R>> idleQueue = new LinkedTransferQueue<>();

    /**
     * Pool state.
     */
    private final AtomicInteger poolState = new AtomicInteger(0);

    /**
     * Acquire processes.
     */
    private final AtomicInteger acqWaitCount = new AtomicInteger(0);

    /**
     * Pool management lock.
     */
    private final StampedLock openCloseLock = new StampedLock();




    enum EntryState {
        IN_USE,
        IDLE,
        TOMBSTONE
    }


    static abstract class PooledEntry<R> {
        abstract boolean allocate();
        abstract boolean release();
        abstract boolean markTombstone();
        abstract R value();
        abstract EntryState state();
        abstract void awaitRelease() throws InterruptedException;
    }

    @SuppressWarnings("WeakerAccess")
    static class DefaultPooledEntry<R>  extends PooledEntry<R> {
        private volatile EntryState state = EntryState.IDLE;

        private final CountDownLatch latch = new CountDownLatch(1);
        private final Lock lock = new ReentrantLock();

        public final R value;

        @Override
        EntryState state() {
            return state;
        }

        DefaultPooledEntry(R value) {
            this.value = value;
        }

        @Override
        public R value() {
            return value;
        }

        @Override
        public boolean allocate() {
            lock.lock();
            try {

                if (state == EntryState.IDLE) {
                    state = EntryState.IN_USE;
                    return true;
                } else {
                    return false;
                }

            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean release() {
            lock.lock();
            try {
                switch (state) {
                    case IDLE:
                        return true;
                    case IN_USE:
                        state = EntryState.IDLE;
                        return true;
                    case TOMBSTONE:
                        latch.countDown();
                        return false;
                    default: throw new IllegalStateException();
                }

            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean markTombstone() {
            lock.lock();
            try {
                EntryState old = state;
                state = EntryState.TOMBSTONE;
                return old == EntryState.IN_USE;
            } finally {
                lock.unlock();
            }
        }

        @Override
        void awaitRelease() throws InterruptedException {
            latch.await();
        }
    }


    @Override
    public R acquire() throws InterruptedException {
        return doAcquire(0, TimeUnit.MILLISECONDS);
    }

    @Override
    public R acquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        return doAcquire(timeout, timeUnit);
    }

    @Override
    public void release(R resource) {
        Lock lock = openCloseLock.asReadLock();
        lock.lock();

        try {
            PooledEntry<R> entry = refs.get(resource);

            if (entry == null) {
                return;
            }

            if (entry.release()) {

                if (isOpen()) {
                    idleQueue.add(entry);
                }

            } else {
                refs.remove(resource);
            }

        } finally {
            lock.unlock();
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
        PooledEntry<R> awaitLatch = null;

        Lock lock = openCloseLock.asReadLock();
        lock.lock();
        try {
            PooledEntry<R> entry = refs.get(resource);

            if (entry == null) {
                return false;
            }

            if (entry.markTombstone()) {
                awaitLatch = entry;
            } else {
                refs.remove(resource);
            }

        } finally {
            lock.unlock();
        }



        if (awaitLatch != null && await) {
            awaitLatch.awaitRelease();
        }

        return true;
    }


    private R doAcquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (!isOpen())
            throw new IllegalStateException("pool is closed");

        boolean isTimeoutValid = true;
        boolean hasTimeout = timeout > 0;
        timeout = timeUnit.toNanos(timeout);

        try {
            // Increment #acqWaitCount and only after it - check state of the pool,
            // during close do opposite, CAS poolState and then verify "count-down"  #acqWaitCount count.
            //
            // Expects to have no race here.
            acqWaitCount.incrementAndGet();

            while (isOpen() && isTimeoutValid) {


                long startNanos = System.nanoTime();

                PooledEntry<R> elem = hasTimeout
                        ? idleQueue.poll(timeout, TimeUnit.NANOSECONDS)
                        : idleQueue.take();

                if (elem == null || elem == TERMINATED) {
                    return null;
                }

                if (elem.allocate()) {
                    return elem.value();
                }

                if (hasTimeout) {
                    long elapsed = System.nanoTime() - startNanos;
                    timeout = timeout - elapsed;
                    isTimeoutValid = timeout > 0;
                }
            }

        } finally {
            acqWaitCount.decrementAndGet();
        }


        return null;
    }


    @Override
    public boolean add(R resource) {
        Lock lock = openCloseLock.asReadLock();
        lock.lock();

        try {
            if (!isOpen()) {
                return false;
            }

            PooledEntry<R> entry = new DefaultPooledEntry<>(resource);
            boolean added = refs.putIfAbsent(resource, entry) == null;
            if (added) {
                idleQueue.offer(entry);
            }

            return added;
        } finally {
            lock.unlock();
        }
    }


    @Override
    public void open() {
        if (poolState.get() == POOL_IS_FRESH) {
            poolState.compareAndSet(POOL_IS_FRESH, POOL_IS_OPEN);
        }
    }

    @Override
    public boolean isOpen() {
        return poolState.get() == POOL_IS_OPEN;
    }

    @Override
    public void close() throws InterruptedException {
        doClose(true);

    }

    @Override
    public void closeNow() {
        try {
            doClose(false);
        } catch (InterruptedException e) {
            //ignore
        }
    }

    private void doClose(boolean await) throws InterruptedException {
        Lock lock = openCloseLock.asWriteLock();
        lock.lock();
        boolean closeDone = false;

        try {
            if (poolState.get() == POOL_IS_OPEN
                    && poolState.compareAndSet(POOL_IS_OPEN, POOL_CLOSED)) {

                closeDone = true;

                // clean-up acquire threads
                while (acqWaitCount.get() > 0) {
                    //noinspection unchecked
                    idleQueue.offer(TERMINATED);
                }
            }

        } finally {
            lock.unlock();
        }

        if (!closeDone) {
            return;
        }

        // Iterate across entries and wait for release if necessary
        // Even though that iterators returned by ConcurrentHashMap.iterator() may or may not reflect insertions
        // or removals that occurred since the iterator was constructed,
        // expects to have any phantom writes at this point as all modifications of the pool
        // performed under lock
        if (await) {
            for (PooledEntry<R> value : refs.values()) {

                if (value.state() == EntryState.IN_USE) {
                    value.awaitRelease();
                }
            }
        }

        refs.clear();
    }



}

