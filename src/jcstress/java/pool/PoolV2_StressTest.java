package pool;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public class PoolV2_StressTest {

    private static final String ITEM_1 = "A1";

    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "gracefully finished"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "test hung up.")
    })
    @State
    public static class Acquire_WaitForAdd {

        private final PoolImpl<String> pool;

        public Acquire_WaitForAdd() {
            pool = new PoolImpl<>();
            pool.open();
        }

        @Actor
        public void actor1()  {
            try {
                requireNonNull(pool.acquire());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.add(ITEM_1);
        }
    }


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "finished"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "test hung up.")
    })
    @State
    public static class Remove_WaitForRelease {

        private final PoolImpl<String> pool;

        public Remove_WaitForRelease() {
            pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM_1);

            try {
                pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Actor
        public void actor1()  {
            try {
                pool.remove(ITEM_1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.release(ITEM_1);
        }
    }



    @JCStressTest
    @Outcome(id = "null, A1", expect = Expect.ACCEPTABLE, desc = "T1 acquired X, and T2 not")
    @Outcome(id = "A1, null", expect = Expect.ACCEPTABLE, desc = "T2 acquired X, and T1 not")
    @State
    public static class Aq_OnlyOne {
        private final PoolImpl<String> pool;

        public Aq_OnlyOne() {
            pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM_1);
        }

        @Actor
        public void actor1(LL_Result r)  {
            try {
                r.r1 = pool.acquire(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Actor
        public void actor2(LL_Result r) {
            try {
                r.r2 = pool.acquire(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "finished"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "test hung up.")
    })
    @State
    public static class Close_CountDown {
        private final PoolImpl<String> pool;

        public Close_CountDown() {
            pool = new PoolImpl<>();
            pool.open();
        }

        @Actor
        public void actor1()  {
            try {
                pool.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException ex) {
                //ignore
            }
        }

        @Signal
        public void sig() {
            pool.closeNow();
        }

    }



    @JCStressTest
    @Outcome(id = "A1, true", expect = Expect.ACCEPTABLE, desc = "T1 acquire 'A', T2 add 'A', T3 close")
    @Outcome(id = "null, false", expect = Expect.ACCEPTABLE, desc = "T3 close, T2 not add 'A' || T1 acquire null")
    @Outcome(id = "null, true", expect = Expect.ACCEPTABLE, desc = "T2 add 'A', T3 close, T1 acquire null")
    @State
    public static class Close {
        private final PoolImpl<String> pool;

        public Close() {
            pool = new PoolImpl<>();
            pool.open();
        }

        @Actor
        public void actor1(LL_Result r) {
            try {
                r.r1 = pool.acquire(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }


        @Actor
        public void actor2(LL_Result r) {
            r.r2 = pool.add(ITEM_1);
        }


        @Actor
        public void actor3(LL_Result r) {
            pool.closeNow();
        }

    }


    @JCStressTest(Mode.Termination)
    @Outcome.Outcomes({
            @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "gracefully close"),
            @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "close hung up")
    })
    @State
    public static class Close_AwaitRelease {
        private final PoolImpl<String> pool;

        public Close_AwaitRelease() {
            pool = new PoolImpl<>();
            pool.open();
            pool.add(ITEM_1);

            try {
                pool.acquire();
            } catch (InterruptedException e) {
                //ignore
            }
        }

        @Actor
        public void actor1() {
            try {
                pool.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Signal
        public void sig() {
            pool.release(ITEM_1);
        }
    }
}
