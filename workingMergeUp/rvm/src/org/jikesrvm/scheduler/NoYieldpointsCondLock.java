/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.scheduler;

import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NonMoving;
import org.jikesrvm.VM;

/**
 * A heavy condition variable and lock that also disables interrupts while
 * the lock is held.  Extremely useful for any locks that may be acquired,
 * released, or waited upon in the process of performing a GC.
 * <p>
 * Note that calling any of the Nicely methods on an instance of this
 * class is extremely dangerous.  These methods may cause you to block on
 * GC, which seemingly goes against the very intent of this being a "no
 * interrupts" condition variable and lock.  However, it makes a subtle
 * kind of sense to use these methods, <i>if you're calling them on the
 * instance of NoInterruptsCondLock that your thread will wait on when
 * blocking on GC</i>.  This idiom is used quite a bit.
 * <p>
 * To ensure that the Nicely methods are used correctly - that is, that
 * they are only used by the thread that owns the lock - there are assertions
 * in place to ensure that the caller is the owner.
 */
@Uninterruptible
@NonMoving
public class NoYieldpointsCondLock extends HeavyCondLock {
  public void lock() {
    VM.disableYieldpoints();
    super.lock();
  }
  
  // This method is strange
  @Unpreemptible
  public void lockNicely() {
    VM.disableYieldpoints();
    super.lockNicely();
  }
  
  public void unlock() {
    super.unlock();
    VM.enableYieldpoints();
  }
}

/* For the emacs weenies in the crowd.
Local Variables:
   c-basic-offset: 2
End:
*/
