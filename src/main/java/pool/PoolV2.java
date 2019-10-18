package pool;

import java.util.Objects;
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
        void awaitRelease() {
        }
    };


    /**
     * Entries state.
     */
    private final ConcurrentHashMap<R, PooledEntry<R>> _refs = new ConcurrentHashMap<>();

    /**
     * acquire -> release hand-off
     */
    // visible for testing
    final TransferQueue<PooledEntry<R>> _idleQueue = new LinkedTransferQueue<>();

    /**
     * Pool state.
     */
    private final AtomicInteger _poolState = new AtomicInteger(0);

    /**
     * Acquire processes.
     */
    private final AtomicInteger _acqWaitCount = new AtomicInteger(0);

    /**
     * Pool management lock.
     */
    private final ReadWriteLock _poolStateLock = new ReentrantReadWriteLock();


    enum EntryState {
        IDLE,
        IN_USE,
        TOMBSTONE
    }


    static abstract class PooledEntry<R> {

        /**
         * Utilize entry.
         * @return true if entry can be used, false otherwise.
         */
        abstract boolean allocate();

        /**
         * Release entry.
         * @return true - entry can be reused, false - need to be removed
         */
        abstract boolean release();

        /**
         * Set tombstone.
         * @return return true if need to await for the release, false - can be removed
         */
        abstract boolean markTombstone();

        /**
         * Wrapped resource.
         */
        abstract R value();

        /**
         * current state
         */
        abstract EntryState state();

        /**
         * await entry release
         * @throws InterruptedException on interrupt
         */
        abstract void awaitRelease() throws InterruptedException;
    }

    @SuppressWarnings("WeakerAccess")
    static final class DefaultPooledEntry<R>  extends PooledEntry<R> {
        private volatile EntryState state = EntryState.IDLE;

        private final CountDownLatch latch = new CountDownLatch(1);
        private final Lock lock = new ReentrantLock();

        public final R value;


        DefaultPooledEntry(R value) {
            this.value = Objects.requireNonNull(value);
        }

        @Override
        EntryState state() {
            return state;
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
        Lock lock = _poolStateLock.readLock();
        lock.lock();

        try {
            PooledEntry<R> entry = _refs.get(resource);

            if (entry == null) {
                return;
            }

            if (entry.release()) {
                if (isOpen()) {
                    _idleQueue.add(entry);
                }

            } else {
                _refs.remove(resource);
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

        Lock rlock = _poolStateLock.readLock();
        rlock.lock();

        try {
            PooledEntry<R> entry = _refs.get(resource);

            if (entry == null) {
                return false;
            }

            if (entry.markTombstone()) {
                awaitLatch = entry;
            } else {
                _refs.remove(resource);
            }

        } finally {
            rlock.unlock();
        }



        if (awaitLatch != null && await) {
            awaitLatch.awaitRelease();
        }

        return true;
    }


    private R doAcquire(long timeout, TimeUnit timeUnit) throws InterruptedException {

        boolean hasTimeout = timeout > 0;
        Lock rlock = _poolStateLock.readLock();

        try {
            _acqWaitCount.incrementAndGet();

            PooledEntry<R> entry = null;

            while (isOpen()) {

                if (entry == null) {
                    entry = hasTimeout ? _idleQueue.poll(timeout, timeUnit) : _idleQueue.take();
                }

                if (entry == null || entry == TERMINATED) {
                    return null;
                }

                // guaranty that any element can't be acquired
                // during pool state changed
                if (rlock.tryLock()) {
                    try {
                        
                        if (!isOpen()) {
                            return null;
                        }

                        if (entry.allocate()) {
                            return entry.value();
                        } else {
                            entry = null;
                        }

                    } finally {
                        rlock.unlock();
                    }
                }
            }


        } finally {
            _acqWaitCount.decrementAndGet();
        }

        return null;
    }


    @Override
    public boolean add(R resource) {
        Objects.requireNonNull(resource);

        Lock rlock = _poolStateLock.readLock();
        rlock.lock();

        try {

            if (!isOpen()) {
                return false;
            }

            PooledEntry<R> entry = new DefaultPooledEntry<>(resource);
            boolean added = _refs.putIfAbsent(resource, entry) == null;
            if (added) {
                _idleQueue.offer(entry);
            }

            return added;
        } finally {
            rlock.unlock();
        }
    }


    @Override
    public void open() {
        if (_poolState.get() == POOL_IS_FRESH) {
            _poolState.compareAndSet(POOL_IS_FRESH, POOL_IS_OPEN);
        }
    }

    @Override
    public boolean isOpen() {
        return _poolState.get() == POOL_IS_OPEN;
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
        Lock lock = _poolStateLock.writeLock();
        lock.lock();
        boolean closeDone = false;

        try {
            if (_poolState.get() == POOL_IS_OPEN
                    && _poolState.compareAndSet(POOL_IS_OPEN, POOL_CLOSED)) {

                _idleQueue.clear();

                closeDone = true;
            }

        } finally {
            lock.unlock();
        }

        if (!closeDone) {
            return;
        }


        // clean-up acquire threads
        while (_acqWaitCount.get() > 0) {
            //noinspection unchecked
            _idleQueue.tryTransfer(TERMINATED);
        }

        // Iterate across entries and wait for release if necessary
        // Even though that iterators returned by ConcurrentHashMap.iterator() may or may not reflect insertions
        // or removals that occurred since the iterator was constructed,
        // expects to have any phantom writes at this point as all modifications of the pool
        // performed under lock
        if (await) {
            for (PooledEntry<R> value : _refs.values()) {

                if (value.state() == EntryState.IN_USE) {
                    value.awaitRelease();
                }
            }
        }

        _refs.clear();
    }



}

