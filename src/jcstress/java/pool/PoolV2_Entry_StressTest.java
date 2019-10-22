package pool;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZL_Result;

import static java.util.Objects.requireNonNull;

public class PoolV2_Entry_StressTest {

    @JCStressTest()
    @Outcome(id = "true, false, IN_USE", expect = Expect.ACCEPTABLE, desc = "acq, release")
    @Outcome(id = "true, true, IDLE", expect = Expect.ACCEPTABLE, desc = "acq, release")
    @Outcome(id = "true, true, IN_USE", expect = Expect.ACCEPTABLE, desc = "release, acq")
    @State
    public static class AllocateAndRelease {

        final PoolImpl.DefaultPooledEntry<String> e =  new PoolImpl.DefaultPooledEntry<>("");


        @Actor
        public void actor1(ZZL_Result r) {
            r.r1 = e.allocate();
            r.r3 = e.state();
        }


        @Actor
        public void actor2(ZZL_Result r) {
            r.r2 = e.release();
            r.r3 = e.state();
        }
    }


    @JCStressTest()
    @Outcome(id = "true, false, TOMBSTONE", expect = Expect.ACCEPTABLE, desc = "release, mark")
    @Outcome(id = "false, true, TOMBSTONE", expect = Expect.ACCEPTABLE, desc = "mark, release")
    @State
    public static class ReleaseMark {

        final PoolImpl.DefaultPooledEntry<String> e;

        public ReleaseMark() {
            e = new PoolImpl.DefaultPooledEntry<>("");
            e.allocate();
        }

        @Actor
        public void actor2(ZZL_Result r) {
            r.r1 = e.release();
            r.r3 = e.state();
        }

        @Actor
        public void actor3(ZZL_Result r) {
            r.r2 = e.markTombstone();
            r.r3 = e.state();
        }
    }

}
