package pool;

public interface Pool <R> {

    static <R> Pool<R> create() {
        return new PoolImpl<>();
    }


    /**
     * Enable pool.
     * Can be called multiple times.
     */
    void open();

    /**
     * CHeck is pool enabled.
     * @return true if enabled otherwise false
     */
    boolean isOpen();

    /**
     * Disable pool. Can be called multiple times.
     */
    void close() throws InterruptedException;

    /**
     * Get resource from pool.
     * Wait till any resource become available.
     *
     * @return acquired value
     * @throws IllegalStateException in case the pool is closed. Waited threads will be released
     * and null will be returned.
     */
    R acquire() throws InterruptedException;

    /**
     *
     * Get resource from pool.
     * Wait specified time till any resource become available.
     *
     * @param timeout timeout for successful acquire
     * @param timeUnit time unit
     * @return acquired value or {@code null} if timeout exceeded
     * @throws IllegalStateException in case the pool is closed. Waited threads will be released
     * and null will be returned.
     */
    R acquire(long timeout,
              java.util.concurrent.TimeUnit timeUnit) throws InterruptedException;

    /**
     * Release resource
     * @param resource resource
     */
    void release(R resource);

    /**
     * •The add(R) and remove(R) methods return true if the pool
     * was modified as a result of the method call or false if
     * the pool was not modified.
     *
     * @param resource res
     * @return true if pool was modified.
     * @throws IllegalStateException in case the pool is closed
     */
    boolean add(R resource);

    /**
     * The remove(R) method should be such that if the resource
     * that is being removed is currently in use, the remove
     * operation will block until that resource has been
     * released.
     *
     * @param resource res
     * @return true if pool was modified.
     */
    boolean remove(R resource) throws InterruptedException;


    /**
     * removes the given resource immediately without waiting
     * for it to be released. It returns true if the call
     * resulted in the pool being modified and false otherwise.
     *
     * All waited threads on {@linkplain #remove(Object)} will be released also.
     * @param resource res
     * @return true if modified
     */
    boolean removeNow(R resource) throws InterruptedException;


    /**
     * Add a new method, void closeNow(), which closes the pool
     * immediately without waiting for all acquired resources to
     * be released.
     *
     */
    void closeNow();
}
