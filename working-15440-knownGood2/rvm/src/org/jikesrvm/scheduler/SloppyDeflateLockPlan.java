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

import org.jikesrvm.VM;
import org.jikesrvm.Services;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.objectmodel.ThinLockConstants;
import org.jikesrvm.runtime.Magic;
import static org.jikesrvm.runtime.SysCall.sysCall;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class SloppyDeflateLockPlan extends CommonLockPlan {
  public static SloppyDeflateLockPlan instance;
  
  protected HeavyCondLock deflateLock;
  
  public SloppyDeflateLockPlan() {
    instance=this;
  }
  
  public void init() {
    super.init();
    // nothing to do for now...
  }
  
  public void boot() {
    super.boot();
    deflateLock=new HeavyCondLock();
  }
  
  public void lateBoot() {
    super.lateBoot();
    PollDeflateThread pdt=new PollDeflateThread();
    pdt.makeDaemon(true);
    pdt.start();
  }
  
  @Inline
  public final boolean inlineLockInflated(Word old, Object o, Word myId) {
    SloppyDeflateLock l=(SloppyDeflateLock)
      Magic.eatCast(getLock(LockConfig.selectedThinPlan.getLockIndex(old)));
    return l!=null && l.inlineLock(o, myId);
  }

  @Inline
  public final boolean inlineUnlockInflated(Word old) {
    SloppyDeflateLock l=(SloppyDeflateLock)
      Magic.eatCast(getLock(LockConfig.selectedThinPlan.getLockIndex(old)));
    return l!=null && l.inlineUnlock();
  }

  public SloppyDeflateLock inflate(Object o, Offset lockOffset) {
    for (int cnt=0;;cnt++) {
      Word bits = Magic.getWordAtOffset(o, lockOffset);
      
      if (LockConfig.selectedThinPlan.isFat(bits)) {
        return (SloppyDeflateLock)Magic.eatCast(
          getLock(LockConfig.selectedThinPlan.getLockIndex(bits)));
      }
      
      SloppyDeflateLock l=(SloppyDeflateLock)allocate();
      if (l==null) {
        // allocation failed, give up
        return null;
      }
      
      // we need to do a careful dance here.  set up the lock.  put it into a locked
      // state.  but if the CAS fails, have a way of rescuing the lock.
      
      // note that at this point attempts to acquire the lock will succeed but back out
      // immediately, since they'll notice that the locked object is not the one they
      // wanted.
      
      if (LockConfig.selectedThinPlan.getLockOwner(bits)==0) {
        l.setUnlockedState();
      } else {
        l.setLockedState(
          LockConfig.selectedThinPlan.getLockOwner(bits),
          LockConfig.selectedThinPlan.getRecCount(bits));
      }
      
      Magic.sync();
      
      l.init();
      l.setLockedObject(o); // this activates the lock
      
      // the lock is now "active" - so the deflation detector thingy will see it, but it
      // will also see that the lock is held.
      
      if (LockConfig.selectedThinPlan.attemptToMarkInflated(
            o, lockOffset, bits, l.id, cnt)) {
        if (VM.monitorSloppyInflations) inflations++;
        addLock(l);
        return l;
      } else {
        l.setLockedObject(null); // mark the lock dead
        free(l);
      }
    }
  }
  
  protected boolean deflateAsMuchAsPossible(int useThreshold) {
    int cnt=0,cntno=0;
    long before=0;
    if (trace) {
      before=sysCall.sysNanoTime();
    }
    deflateLock.lockNicely();
    for (int i=0;i<numLocks();++i) {
      SloppyDeflateLock l=(SloppyDeflateLock)getLock(i);
      if (l!=null) {
        if (l.pollDeflate(useThreshold)) {
          cnt++;
        } else {
          cntno++;
        }
      }
    }
    deflateLock.unlock();
    if ((trace || VM.traceSloppyDeflations) && cnt>0) {
      long after=0;
      if (trace) {
        after=sysCall.sysNanoTime();
      }
      VM.tsysWriteln("deflated ",cnt," but skipped ",cntno," locks with useThreshold = ",useThreshold);
      VM.tsysWriteln("lock list is ",numLocks()," long");
      if (trace) {
        VM.tsysWriteln("and it took ",after-before," nanos");
      }
    }
    return cnt>0;
  }
  
  protected boolean tryToDeflateSomeLocks() {
    return deflateAsMuchAsPossible(-1);
  }
  
  protected long interruptQuantumMultiplier() {
    return VM.sloppyQuantumMult;
  }
  
  static int lastInflations;
  
  @NonMoving
  static class PollDeflateThread extends RVMThread {
    PollDeflateThread() {
      super("PollDeflateThread");
    }
    
    @Override
    public void run() {
      try {
        for (;;) {
          RVMThread.sleep(
            1000L*1000L*instance.interruptQuantumMultiplier()*VM.interruptQuantum);
          
          instance.deflateAsMuchAsPossible(VM.sloppyUseThreshold);
          if (VM.monitorSloppyInflations) {
            VM.tsysWriteln("saw ",inflations-lastInflations," inflations.");
            lastInflations=inflations;
          }
        }
      } catch (Throwable e) {
        VM.printExceptionAndDie("poll deflate thread",e);
      }
      VM.sysFail("should never get here");
    }
  }
}

