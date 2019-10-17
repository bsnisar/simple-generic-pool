package pool;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.L_Result;



public class PoolImpl_StressTest_PoolEntry {

    @JCStressTest
    @Outcome.Outcomes({
            @Outcome(id = "DROP", expect = Expect.ACCEPTABLE, desc = "outcome:rejected"),
    })
    @State
    public static class AcquireReject {

        private final PoolImpl.PoolEntry<String> inUse = new PoolImpl.PoolEntry<>(
                "0.56", PoolImpl.PoolEntry.State.IN_USE);
        @Actor
        public void actor1(L_Result r)  {
            inUse.release();
            r.r1 = inUse.getState();
        }

        @Actor
        public void actor2(L_Result r) {
            try {
                inUse.dropAwait();
                r.r1 = inUse.getState();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }



    @JCStressTest
    @Outcome(id = "IN_USE", expect = Expect.ACCEPTABLE, desc = "Default outcome")
    @State
    public static class Acquire {
        private final PoolImpl.PoolEntry<String> notInUse = new PoolImpl.PoolEntry<>(
                "0.56", PoolImpl.PoolEntry.State.NOT_IN_USE);
        @Actor
        public void actor1(L_Result r) {
            notInUse.take();
            r.r1 = notInUse.getState();
        }


        @Actor
        public void actor2(L_Result r) {
            notInUse.take();
            r.r1 = notInUse.getState();
        }
    }



    @JCStressTest
    @Outcome.Outcomes({
            @Outcome(id = "IN_USE", expect = Expect.ACCEPTABLE, desc = "outcome:take"),
            @Outcome(id = "NOT_IN_USE", expect = Expect.ACCEPTABLE, desc = "outcome:release"),
    })
    @State
    public static class AcquireRelease {
        private final PoolImpl.PoolEntry<String> notInUse = new PoolImpl.PoolEntry<>(
                "0.56", PoolImpl.PoolEntry.State.NOT_IN_USE);
        @Actor
        public void actor1(L_Result r) {
            notInUse.take();
            r.r1 = notInUse.getState();
        }


        @Actor
        public void actor2(L_Result r) {
            notInUse.release();
            r.r1 = notInUse.getState();
        }
    }

}
