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

public class SloppyDeflateThinLockPlan extends CommonThinLockPlan {
  public static SloppyDeflateThinLockPlan instance;
  
  protected HeavyCondLock deflateLock;
  
  public SloppyDeflateThinLockPlan() {
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

  @NoInline
  public void lock(Object o, Offset lockOffset) {
    for (;;) {
      // the idea:
      // - if the lock is uninflated and unclaimed attempt to grab it the thin way
      // - if the lock is uninflated and claimed by me, attempt to increase rec count
      // - if the lock is uninflated and claimed by someone else, inflate it and
      //   do the slow path of acquisition
      // - if the lock is inflated, grab it.
      
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      if (id.isZero()) {
        // lock not held, acquire quickly with rec count == 1
        if (Magic.attemptWord(o, lockOffset, old, old.or(threadId))) {
          Magic.isync();
          return;
        }
      } else if (id.EQ(threadId)) {
        // lock held, attempt to increment rec count
        Word changed = old.toAddress().plus(TL_LOCK_COUNT_UNIT).toWord();
        if (!changed.and(TL_LOCK_COUNT_MASK).isZero() &&
            Magic.attemptWord(o, lockOffset, old, changed)) {
          Magic.isync();
          return;
        }
      } else if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        // we have a heavy lock.
        if (getLock(getLockIndex(old)).lockHeavy(o)) {
          return;
        } // else we grabbed someone else's lock
      } else {
        // the lock is not fat, is owned by someone else, or else the count wrapped.
        // attempt to inflate it (this may fail, in which case we'll just harmlessly
        // loop around).  if it succeeds, we loop around anyway, so that we can
        // grab the lock the fat way.
        inflate(o, lockOffset);
      }
    }
  }
  
  @NoInline
  public void unlock(Object o, Offset lockOffset) {
    Magic.sync();
    for (;;) {
      Word old = Magic.prepareWord(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      Word threadId = Word.fromIntZeroExtend(RVMThread.getCurrentThread().getLockingId());
      if (id.EQ(threadId)) {
        if (old.and(TL_LOCK_COUNT_MASK).isZero()) {
          // release lock
          Word changed = old.and(TL_UNLOCK_MASK);
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return;
          }
        } else {
          // decrement count
          Word changed = old.toAddress().minus(TL_LOCK_COUNT_UNIT).toWord();
          if (Magic.attemptWord(o, lockOffset, old, changed)) {
            return; // unlock succeeds
          }
        }
      } else {
        if (old.and(TL_FAT_LOCK_MASK).isZero()) {
          // someone else holds the lock in thin mode and it's not us.  that indicates
          // bad use of monitorenter/monitorexit
          RVMThread.raiseIllegalMonitorStateException("thin unlocking", o);
        }
        // fat unlock
        getLock(getLockIndex(old)).unlockHeavy();
        return;
      }
    }
  }
  
  protected SloppyDeflateThinLock inflate(Object o, Offset lockOffset) {
    // the idea:
    // attempt to allocate fat lock, extract the
    // state of the thin lock and put it into the fat lock, mark the lock as active
    // (allowing it to be deflated) and attempt CAS to replace
    // the thin lock with a pointer to the fat lock.
    
    // nb:
    // what about when someone asks for the lock to be inflated, holds onto the fat
    // lock, and then does stuff to it?  won't the autodeflater deflate it at that
    // point?
    // no.  you're only allowed to ask for the fat lock when the object is locked.  in
    // that case, it cannot be deflated.
    
    for (;;) {
      Word old = Magic.getWordAtOffset(o, lockOffset);
      Word id = old.and(TL_THREAD_ID_MASK.or(TL_FAT_LOCK_MASK));
      
      if (!old.and(TL_FAT_LOCK_MASK).isZero()) {
        return (SloppyDeflateThinLock)getLock(getLockIndex(old));
      }
      
      SloppyDeflateThinLock l=(SloppyDeflateThinLock)allocate();
      if (l==null) {
        // allocation failed, give up
        return null;
      }
      
      // we need to do a careful dance here.  set up the lock.  put it into a locked
      // state.  but if the CAS fails, have a way of rescuing the lock.
      
      // note that at this point attempts to acquire the lock will succeed but back out
      // immediately, since they'll notice that the locked object is not the one they
      // wanted.
      
      if (id.isZero()) {
        l.setUnlockedState();
      } else {
        l.setLockedState(
          old.and(TL_THREAD_ID_MASK).toInt(),
          old.and(TL_LOCK_COUNT_MASK).rshl(TL_LOCK_COUNT_SHIFT).toInt() + 1);
        // the lock is now acquired - on behalf of the thread that owned the thin
        // lock.  crazy.  what if that thread then tries to acquire this lock thinking
        // it belongs to a different object?
        
        // even crazier: what if the thread that owns the thin lock is trying to
        // acquire the fat lock thinking it belongs to a different object?  if it
        // decides to do that right now, it'll deadlock.
      }
      
      Magic.sync();
      l.setLockedObject(o);
      
      l.activate();
      
      // the lock is now "active" - so the deflation detector thingy will see it, but it
      // will also see that the lock is held.
      
      Word changed=
        TL_FAT_LOCK_MASK.or(Word.fromIntZeroExtend(l.id).lsh(TL_LOCK_ID_SHIFT))
        .or(old.and(TL_UNLOCK_MASK));
      
      if (Synchronization.tryCompareAndSwap(o, lockOffset, old, changed)) {
        if (trace) VM.tsysWriteln("inflated a lock.");
        return l;
      } else {
        // need to "deactivate" the lock here.
        free(l);
      }
    }
  }
  
  public AbstractLock getHeavyLock(Object o, Offset lockOffset, boolean create) {
    Word old = Magic.getWordAtOffset(o, lockOffset);
    if (!(old.and(TL_FAT_LOCK_MASK).isZero())) { // already a fat lock in place
      return getLock(getLockIndex(old));
    } else if (create) {
      AbstractLock result=inflate(o, lockOffset);
      if (VM.VerifyAssertions) VM._assert(result!=null);
      return result;
    } else {
      return null;
    }
  }
  
  protected boolean deflateAsMuchAsPossible(int useThreshold) {
    int cnt=0,cntno=0;
    long before=0;
    if (true || trace) {
      before=sysCall.sysNanoTime();
    }
    deflateLock.lockNicely();
    for (int i=0;i<numLocks();++i) {
      SloppyDeflateThinLock l=(SloppyDeflateThinLock)getLock(i);
      if (l!=null) {
        if (l.pollDeflate(useThreshold)) {
          cnt++;
        } else {
          cntno++;
        }
      }
    }
    deflateLock.unlock();
    if ((true || trace) && cnt>0) {
      long after=sysCall.sysNanoTime();
      VM.tsysWriteln("deflated ",cnt," but skipped ",cntno," locks with useThreshold = ",useThreshold);
      VM.tsysWriteln("lock list is ",numLocks()," long");
      VM.tsysWriteln("and it took ",after-before," nanos");
    }
    return cnt>0;
  }
  
  protected boolean tryToDeflateSomeLocks() {
    return deflateAsMuchAsPossible(-1);
  }
  
  protected long interruptQuantumMultiplier() {
    return 2;
  }
  
  @NonMoving
  static class PollDeflateThread extends RVMThread {
    public PollDeflateThread() {
      super("PollDeflateThread");
    }
    
    @Override
    public void run() {
      try {
        for (;;) {
          RVMThread.sleep(
            1000L*1000L*instance.interruptQuantumMultiplier()*VM.interruptQuantum);
          
          instance.deflateAsMuchAsPossible((int)(1000*instance.interruptQuantumMultiplier()*VM.interruptQuantum));
        }
      } catch (Throwable e) {
        VM.printExceptionAndDie("poll deflate thread",e);
      }
      VM.sysFail("should never get here");
    }
  }
}


