package pool;

import org.openjdk.jcstress.annotations.*;


public class PoolImpl_StressTest {


    private static final String ITEM = "OK";


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "Gracefully finished."),
            @Outcome(id = "STALE", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Test hung up.")
    })
    @State
    public static class Remove_WaitRelease {


        private final PoolImpl<String> pool = createAcquired();

        @SuppressWarnings("Duplicates")
        private static PoolImpl<String> createAcquired() {
            final PoolImpl<String> pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM);
            final String s;

            try {
                s = pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            assert s.equals(ITEM);

            return pool;
        }

        @Actor
        public void actor1()  {
            try {
                pool.remove(ITEM);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.release(ITEM);
        }
    }


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "Gracefully finished."),
            @Outcome(id = "STALE", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Test hung up.")
    })
    @State
    public static class Close_WaitRelease {

        private final PoolImpl<String> pool = createAcquired();

        @SuppressWarnings("Duplicates")
        private static PoolImpl<String> createAcquired() {
            final PoolImpl<String> pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM);

            final String s;

            try {
                s = pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            assert s.equals(ITEM);

            return pool;
        }


        @Actor
        public void actor1()  {
            try {
                pool.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }


        @Signal
        public void sig() {
            pool.release(ITEM);
        }
    }


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "Gracefully finished."),
            @Outcome(id = "STALE", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Test hung up.")
    })
    @State
    public static class Acquire {

        private final PoolImpl<String> pool = createAcquired();


        @SuppressWarnings("Duplicates")
        private static PoolImpl<String> createAcquired() {
            final PoolImpl<String> pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM);

            final String s;

            try {
                s = pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            assert s.equals(ITEM);

            return pool;
        }


        @Actor
        public void actor1()  {
            try {
                pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.release(ITEM);
        }
    }

}
