/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
/**
   VM_Lock provides RVM support for monitors and Java level 
   synchronization.

   <p>
   This class may be decomposed into four sections:
   <OL>
   <LI> support for synchronization methods of java.lang.Oblect,
   <LI> heavy weight locking mechanism,
   <LI> management of heavy weight locks, and
   <LI> debugging and performance tuning support.
   </OL>
   </p>

   <p><STRONG>Requirement 1:</STRONG> 
   It must be possible to lock an object when allocations are not
   allowed.
   </p>

   <p><STRONG>Requirement 2:</STRONG> 
   After a lock has been obtained, the code of this class must return
   without allowing a thread switch.  (The {@link
   VM_BaselineExceptionDeliverer#unwindStackFrame exception handler}
   of the baseline compiler assumes that until lock() returns the lock
   has not been obtained.)
   </p>

   <p> 
   Locking in RVM uses the locking bits of the {@link
   VM_ObjectLayoutConstants status word} of the RVM object
   header.  The first of these bits is called the <em>fat bit</em>.
   If it is set, the rest of the bit field is an index into a global
   array of heavy locks (VM_Lock objects).  If the fat bit is zero,
   the object is either not locked (in which case all the locking
   bits are zero), or some thread holds a light weight lock on the
   object.  In the latter case, two sub-fields encode the information
   about this lock.  The first is the id number of the thread that
   holds the lock and the second is a count of the number of time
   the lock is held.
   </p>

   <p><STRONG>Section 1:</STRONG> 
   support for {@link java.lang.Object#notify}, {@link
   java.lang.Object#notifyAll}, and {@link java.lang.Object#wait}.
   When these methods are called, the indicated object must be locked
   by the current thread.  <p>

   <p><STRONG>Section 2:</STRONG> 
   light-weight locking and unlocking.  Handles the most common
   locking cases: the object unlocked, or locked by the current
   thread (but not too many times).  If a light-weight lock cannot
   easily be obtained (usually because another thread has the
   object locked).  Releasing a light weight lock can also be
   accomplished here.
   </p>

   <p><STRONG>Section 3:</STRONG> 
   has two sections.  <EM>Section 3a:</EM> locks (and unlocking)
   objects with heavy-weight locks associated with them.  <EM>Section
   3b:</EM> associates (and disassociates) heavy-weight locks with
   objects.  
   </p>

   <p><STRONG>Section 4:</STRONG> 
   Allocates (and frees) heavy weight locks consistent with Requirement
   1.  Also, distributes them among the virtual processors.
   </p>

   <p><STRONG>Section 5:</STRONG> 
   debugging and performance tuning stuff.  (I have remove most of this.)
   </p>

   <p>
   The following performance tuning issues have not yet been addressed
   adaquately:
   <OL>
   <LI> <EM>What to do if the attempt to lock an object fails?</EM>  There
        are three choices: try again (busy-wait), yield and then try again,
        inflate the lock and yield to the heavy-weight lock's entering
	queue.  Currently, yield {@link #retryLimit} times, then inflate.  
        (This seemed to be best for the portBOB benchmark on a 12-way AIX
        SMP in the Fall of '99.)
   <LI> <EM>When should a heavy-weight lock be deflated?</EM>  Currently,
        deflation happens when the lock is unlocked with nothing on either
        of its queues.  Probably better, would be to periodically (what
        period?) examine heavy-weight locks and deflate any that havn't
        been held for a while (how long?).
   <LI> <EM>How many heavy-weight locks are needed? and how should they be
        managed?</EM>  Currently, each processor maintains a pool of free
	locks.  When a lock is inflated by a processor it is taken from
	this pool and when a lock is deflated by a processor it gets added
        to the processors pool.  Since inflation can happen on one processor
        and deflation on another, this can create an imbalance.  It might
	be worth investigating a scheme for balancing these local pools.
   <LI> <EM>Is there any advantage to using the {@link VM_ProcessorLock#tryLock}
        method?</EM>  
   </OL>
   Once these questions, and the issue of using MCS locking in {@link 
   VM_ProcessorLock}, have been investigate, then a larger performance issue
   comes into view.  A number of different light-weight locking schemes have
   been proposed over the years (see last several OOPSLA's).  It should be
   possible to implement each of them in RVM and compare their performance.
   </p>

   @see java.lang.Object
   @see VM_ObjectLayoutConstants
   @see VM_ProcessorLock
   @author Bowen Alpern */

public final class VM_Lock implements VM_Constants, VM_Uninterruptible {

  ///////////////////////////////////////////////////
  /// Section 0: Support for light-weight locking ///
  ///////////////////////////////////////////////////

  public static void lock (Object o) { VM_ObjectModel.genericLock(o); }

  public static void unlock (Object o) { VM_ObjectModel.genericUnlock(o); }


  ////////////////////////////////////////////////////////////////////////
  /// Section 1: Support for synchronizing methods of java.lang.Object ///
  ////////////////////////////////////////////////////////////////////////

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @see java.lang.Object#wait()
   */
  public static void wait (Object o) {
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logWaitBegin(); }
    if (STATS) waitOperations++;
    VM_Thread t = VM_Thread.getCurrentThread();
    t.proxy = new VM_Proxy(t); // cache the proxy before obtaining lock
    VM_Lock l = VM_ObjectModel.getHeavyLock(o, true);
    // this thread is supposed to own the lock on o
    if (l.ownerId != VM_Magic.getThreadId())
      throw new IllegalMonitorStateException("waiting on " + o);
    // allow an entering thread a chance to get the lock
    l.mutex.lock(); // until unlock(), thread-switching fatal
    VM_Thread n = l.entering.dequeue();
    if (n != null) n.scheduleHighPriority();
    // squirrel away lock state in current thread
    t.waitObject = l.lockedObject;
    t.waitCount  = l.recursionCount;
    // release l and simultaneously put t on l's waiting queue
    l.ownerId = 0;
    Throwable rethrow = null;
    try {
      t.yield(l.waiting, l.mutex); // thread-switching benign
    } catch (Throwable thr) {
      rethrow = thr; // An InterruptedException. We'll rethrow it after regaining the lock on o.
    }
    // regain lock
    VM_ObjectModel.genericLock(o);
    t.waitObject = null;          
    if (t.waitCount != 1) { // reset recursion count
      l = VM_ObjectModel.getHeavyLock(o, true);
      l.recursionCount = t.waitCount;
    }
    if (rethrow != null) {
      VM_Runtime.athrow(rethrow); // doesn't return
    }
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logWaitEnd(); }
  }

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @param millis the number of milliseconds to wait for notification
   * @see java.lang.Object#wait(long time)
   */
  public static void wait (Object o, long millis) {
    double time;
    VM_Thread t = VM_Thread.getCurrentThread();
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logWaitBegin(); }
    if (STATS) timedWaitOperations++;
    // Get proxy and set wakeup time
    t.wakeupTime = VM_Time.now() + millis * .001;
    t.proxy = new VM_Proxy(t, t.wakeupTime); // cache the proxy before obtaining locks
    // Get monitor lock
    VM_Lock l = VM_ObjectModel.getHeavyLock(o, true);
    // this thread is supposed to own the lock on o
    if (l.ownerId != VM_Magic.getThreadId())
      throw new IllegalMonitorStateException("waiting on " + o);
    // allow an entering thread a chance to get the lock
    l.mutex.lock(); // until unlock(), thread-switching fatal
    VM_Thread n = l.entering.dequeue();
    if (n != null) n.scheduleHighPriority();
    VM_Scheduler.wakeupMutex.lock();
    // squirrel away lock state in current thread
    t.waitObject = l.lockedObject;
    t.waitCount  = l.recursionCount;
    // release locks and simultaneously put t on their waiting queues
    l.ownerId = 0;
    try {
      t.yield(l.waiting, l.mutex, VM_Scheduler.wakeupQueue, VM_Scheduler.wakeupMutex); // thread-switching benign
    } catch (Throwable thr) {
      VM_ObjectModel.genericLock(o);
      VM_Runtime.athrow(thr);
    }
    // regain lock
    VM_ObjectModel.genericLock(o);
    t.waitObject = null;          
    if (t.waitCount != 1) { // reset recursion count
      l = VM_ObjectModel.getHeavyLock(o, true);
      l.recursionCount = t.waitCount;
    }
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logWaitEnd(); }
  }

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @see java.lang.Object#notify
   */
  public static void notify (Object o) {
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logNotifyBegin(); }
    if (STATS) notifyOperations++;
    VM_Lock l = VM_ObjectModel.getHeavyLock(o, false);
    if (l == null) return;
    if (l.ownerId != VM_Magic.getThreadId())
      throw new IllegalMonitorStateException("notifying " + o);
    l.mutex.lock(); // until unlock(), thread-switching fatal
    VM_Thread t = l.waiting.dequeue();
    if (false) { // this "optimization" seems tempting, but actually makes things worse (on Volano, at least) [--DL]
       if (t != null) { // global queue: check to see if thread's stack in use by some other dispatcher
          if (t.beingDispatched) l.entering.enqueue(t); // normal scheduling
          else VM_Processor.getCurrentProcessor().readyQueue.enqueueHighPriority(t); // optimized scheduling
       }
    } else {
      if (t != null) l.entering.enqueue(t);
    }
    l.mutex.unlock(); // thread-switching benign
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logNotifyEnd(); }
  }

  /**
   * Support for Java synchronization primitive.
   *
   * @param o the object synchronized on
   * @see java.lang.Object#notifyAll
   */
  public static void notifyAll (Object o) {
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logNotifyAllBegin(); }
    if (STATS) notifyAllOperations++;
    VM_Lock l = VM_ObjectModel.getHeavyLock(o, false);
    if (l == null) return;
    if (l.ownerId != VM_Magic.getThreadId())
      throw new IllegalMonitorStateException("notifying " + o);
    l.mutex.lock(); // until unlock(), thread-switching fatal
    VM_Thread t = l.waiting.dequeue();
    while (t != null) {
      l.entering.enqueue(t); 
      t = l.waiting.dequeue();
    }
    l.mutex.unlock(); // thread-switching benign
    if (VM.BuildForEventLogging && VM.EventLoggingEnabled) { VM_EventLogger.logNotifyAllEnd(); }
  }

  ///////////////////////////////////////////////////
  /// Section 2: Support for heavy-weight locking ///
  ///////////////////////////////////////////////////

  /** The object being locked (if any). */
  Object               lockedObject;
  /** The id of the thread that owns this lock (if any). */
  int                  ownerId;
  /** The number of times the owning thread (if any) has acquired this lock. */
  int                  recursionCount;
  /** A queue of threads contending for this lock (guarded by <code>mutex</code>). */
  VM_ThreadQueue       entering;   
  /** A queue of (proxies for) threads awaiting notification on this object (guarded by <code>mutex</code>). */
  VM_ProxyWaitingQueue waiting; 
  /** A spin lock to handle contention for the data structures of this lock. */
  VM_ProcessorLock     mutex;

  /**
   * A heavy weight lock to handle extreme contention and wait/notify
   * synchronization.
   */
  VM_Lock () {
    entering = new VM_ThreadQueue(VM_EventLogger.ENTERING_QUEUE);
    waiting  = new VM_ProxyWaitingQueue(VM_EventLogger.WAITING_QUEUE);
    mutex    = new VM_ProcessorLock();
  }

  //////////////////////////////////////////////////////////////////////////
  /// Section 2A: Support for locking (and unlocking) heavy-weight locks ///
  //////////////////////////////////////////////////////////////////////////

  /**
   * Acquires this heavy-weight lock on the indicated object.
   *
   * @param o the object to be locked 
   * @return true, if the lock succeeds; false, otherwise
   */
  boolean lockHeavy (Object o) {
    if (tentativeMicrolocking) {
      if (!mutex.tryLock()) 
	return false;
    } else mutex.lock();  // Note: thread switching is not allowed while mutex is held.
    if (lockedObject != o) { // lock disappeared before we got here
      mutex.unlock(); // thread switching benign
      return false;
    }
    if (STATS) lockOperations++;
    if (ownerId == VM_Magic.getThreadId()) {
      recursionCount ++;
    } else if (ownerId == 0) {
      ownerId = VM_Magic.getThreadId();
      recursionCount = 1;
    } else if (VM_Processor.getCurrentProcessor().threadSwitchingEnabled()) {
      VM_Thread.yield(entering, mutex); // thread-switching benign
      // when this thread next gets scheduled, it will be entitled to the lock,
      // but another thread might grab it first.
      return false; // caller will try again
    } else { // can't yield - must spin and let caller retry
      // potential deadlock if user thread is contending for a lock with thread switching disabled
      if (VM.VerifyAssertions) VM.assert(VM_Thread.getCurrentThread().isGCThread);
      mutex.unlock(); // thread-switching benign
      return false; // caller will try again
    }
    mutex.unlock(); // thread-switching benign
    return true;
  }
  
  /**
   * Releases this heavy-weight lock on the indicated object.
   *
   * @param o the object to be unlocked 
   */
  void unlockHeavy (Object o) {
    boolean deflated = false;
    mutex.lock(); // Note: thread switching is not allowed while mutex is held.
    if (ownerId != VM_Magic.getThreadId()) {
      mutex.unlock(); // thread-switching benign
      throw new IllegalMonitorStateException("heavy unlocking " + o);
    }
    if (0 < --recursionCount) {
      mutex.unlock(); // thread-switching benign
      return;
    }
    if (STATS) unlockOperations++;
    ownerId = 0;
    VM_Thread t = entering.dequeue();
    if (t != null) t.scheduleHighPriority();
    else if (entering.isEmpty() && waiting.isEmpty()) { // heavy lock can be deflated
      // Possible project: decide on a heuristic to control when lock should be deflated
      int lockOffset = VM_Magic.getObjectType(o).thinLockOffset;
      if (lockOffset != -1) { // deflate heavy lock
	deflate(o, lockOffset);
	deflated = true;
      }
    }
    mutex.unlock(); // does a VM_Magic.sync();  (thread-switching benign)
    if (deflated && ((LOCK_ALLOCATION_UNIT_SIZE<<1) <= VM_Processor.getCurrentProcessor().freeLocks) && balanceFreeLocks)
      globalizeFreeLocks();
  }
  

  /**
   * Disassociates this heavy-weight lock from the indicated object.
   * This lock is not heald, nor are any threads on its queues.  Note:
   * the mutex for this lock is held when deflate is called.
   *
   * @param o the object from which this lock is to be disassociated
   */
  private void deflate (Object o, int lockOffset) {
    if (VM.VerifyAssertions) {
      VM.assert(lockedObject == o);
      VM.assert(recursionCount == 0);
      VM.assert(entering.isEmpty());
      VM.assert(waiting.isEmpty());
    }
    if (STATS) deflations++;
    VM_ThinLock.deflate(o, lockOffset, this);
    lockedObject = null;
    free(this);
  }


  ////////////////////////////////////////////////////////////////////////////
  /// Section 3: Support for allocating (and recycling) heavy-weight locks ///
  ////////////////////////////////////////////////////////////////////////////

  // lock table implementation
  // 
                 boolean       active;
  private        VM_Lock       nextFreeLock;
                 int           index;
  private static int           nextLockIndex;

  // Maximum number of VM_Lock's that we can support
  //
  private static final int LOCK_ALLOCATION_UNIT_SIZE  =  100;
  private static final int LOCK_ALLOCATION_UNIT_COUNT =  2500;  // TEMP SUSAN
          static final int MAX_LOCKS = LOCK_ALLOCATION_UNIT_SIZE * LOCK_ALLOCATION_UNIT_COUNT ;

  private static VM_ProcessorLock lockAllocationMutex;
  private static int              lockUnitsAllocated;
  private static VM_Lock          globalFreeLock;
  private static int              globalFreeLocks;

  /**
   * Sets up the data structures for holding heavy-weight locks.
   */
  static void init() {
    lockAllocationMutex = new VM_ProcessorLock();
    VM_Scheduler.locks  = new VM_Lock[MAX_LOCKS+1]; // don't use slot 0
    if (VM.VerifyAssertions) // check that each potential lock is addressable
      VM.assert((VM_Scheduler.locks.length-1<=(VM_ThinLockConstants.TL_LOCK_ID_MASK>>>VM_ThinLockConstants.TL_LOCK_ID_SHIFT))
                || (VM_ThinLockConstants.TL_LOCK_ID_MASK==-1));
  }
  
  /**
   * Delivers up an unassigned heavy-weight lock.  Locks are allocated
   * from processor specific regions or lists, so normally no synchronization
   * is required to obtain a lock.
   *
   * Collector threads cannot use heavy-weight locks.
   *
   * @return a free VM_Lock; or <code>null</code>, if garbage collection is not enabled
   */
  static VM_Lock allocate () {
    VM_Processor mine = VM_Processor.getCurrentProcessor();
    if (!mine.threadSwitchingEnabled()) return null; // Collector threads can't use heavy locks because they don't fix up their stacks after moving objects
    if ((mine.freeLocks == 0) && (0 < globalFreeLocks) && balanceFreeLocks) {
      localizeFreeLocks();
    }
    VM_Lock l = mine.freeLock;
    if (l != null) {
      mine.freeLock = l.nextFreeLock;
      l.nextFreeLock = null;
      mine.freeLocks--;
      l.active = true;
    } else {
      l = new VM_Lock(); // may cause thread switch (and processor loss)
      mine = VM_Processor.getCurrentProcessor();
      if (mine.lastLockIndex < mine.nextLockIndex) {
	lockAllocationMutex.lock();
	mine.nextLockIndex = 1 + (LOCK_ALLOCATION_UNIT_SIZE * lockUnitsAllocated++);
	lockAllocationMutex.unlock();
	mine.lastLockIndex = mine.nextLockIndex + LOCK_ALLOCATION_UNIT_SIZE - 1;
	if (MAX_LOCKS <= mine.lastLockIndex) {
	  VM.sysFail("Too many thick locks on processor " + mine.id); // make MAX_LOCKS bigger?
	  return null;                                                // we can keep going
	}
      }
      l.index = mine.nextLockIndex++;
      VM_Scheduler.locks[l.index] = l;
      l.active = true;
      VM_Magic.sync(); // make sure other processors see lock initialization.  Note: Derek and I BELIEVE that an isync is not required in the other processor because the lock is newly allocated - Bowen
    }
    mine.locksAllocated++;
    return l;
  }

  /**
   * Recycles a unused heavy-weight lock.  Locks are deallocated
   * to processor specific lists, so normally no synchronization
   * is required to obtain or release a lock.
   *
   * @return a free VM_Lock; or <code>null</code>, if garbage collection is not enabled
   */
  static void free (VM_Lock l) {
    l.active = false;
    VM_Processor mine = VM_Processor.getCurrentProcessor();
    l.nextFreeLock = mine.freeLock;
    mine.freeLock  = l;
    mine.freeLocks ++;
    mine.locksFreed++;
  }

  /**
   * Transfers free heavy-weight locks from a processor local
   * structure to a global one.
   *
   * Only used if RVM_WITH_FREE_LOCK_BALANCING preprocessor
   * directive is set for the current build.
   */
  private static void globalizeFreeLocks () {
    VM_Processor mine = VM_Processor.getCurrentProcessor();
    if (mine.freeLocks <= LOCK_ALLOCATION_UNIT_SIZE) {
      if (VM.VerifyAssertions) VM.assert(mine.freeLock != null);
      VM_Lock q = mine.freeLock;
      while (q.nextFreeLock != null) {
	q = q.nextFreeLock;
      }
      lockAllocationMutex.lock();
      q.nextFreeLock  = globalFreeLock;
      globalFreeLock  = mine.freeLock;
      globalFreeLocks += mine.freeLocks;
      lockAllocationMutex.unlock();
      mine.freeLock   = null;
      mine.freeLocks  = 0;
    } else {
      VM_Lock p = null;
      VM_Lock q = mine.freeLock;
      for (int i=0; i<LOCK_ALLOCATION_UNIT_SIZE; i++) {
	p = q;
	q = q.nextFreeLock;
      }
      lockAllocationMutex.lock();
      p.nextFreeLock   = globalFreeLock;
      globalFreeLock   = mine.freeLock;
      globalFreeLocks += LOCK_ALLOCATION_UNIT_SIZE;
      lockAllocationMutex.unlock();
      mine.freeLock    = q;
      mine.freeLocks  -= LOCK_ALLOCATION_UNIT_SIZE;
    }
  }

  /**
   * Transfers free heavy-weight locks from a global structure to a
   * processor local one.
   *
   * Only used if RVM_WITH_FREE_LOCK_BALANCING preprocessor
   * directive is set for the current build.  
   */
  private static void localizeFreeLocks () {
    if (true) return; // TEMP
    VM_Processor mine = VM_Processor.getCurrentProcessor();
    if (VM.VerifyAssertions) VM.assert(mine.freeLock == null);
    lockAllocationMutex.lock();
    if (globalFreeLocks <= LOCK_ALLOCATION_UNIT_SIZE){
      mine.freeLock   = globalFreeLock;
      mine.freeLocks  = globalFreeLocks;
      globalFreeLock  = null;
      globalFreeLocks = 0;
    } else {
      VM_Lock p = null;
      VM_Lock q = globalFreeLock;
      for (int i=0; i<LOCK_ALLOCATION_UNIT_SIZE; i++) {
	p = q;
	q = q.nextFreeLock;
      }
      p.nextFreeLock   = null;
      mine.freeLock    = globalFreeLock;
      mine.freeLocks   = LOCK_ALLOCATION_UNIT_SIZE;
      globalFreeLock   = q;
      globalFreeLocks -= LOCK_ALLOCATION_UNIT_SIZE;
    }
    lockAllocationMutex.unlock();
  }

  ///////////////////////////////////////////////////////////////
  /// Section 4: Support for debugging and performance tuning ///
  ///////////////////////////////////////////////////////////////

//-#if RVM_WITH_FREE_LOCK_BALANCING
  /**
   * Attempt to keep the roughly equal sized pools for free
   * heavy-weight locks on each processor.
   *
   * Preprocessor directive RVM_WITH_FREE_LOCK_BALANCING=1.  
   */
  private static final boolean balanceFreeLocks = true;
//-#else
  /**
   * Don't attempt to keep the roughly equal sized pools for free
   * heavy-weight locks on each processor.  Each virual processor
   * keeps its own free pool of the locks that it has freed 
   * (deflated).
   *
   * Preprocessor directive RVM_WITH_FREE_LOCK_BALANCING=0.
   */
  private static final boolean balanceFreeLocks = false;
//-#endif

//-#if RVM_WITH_TENTATIVE_MICROLOCKING
  /**
   * Give up the attempt to get a heavy-weight lock, if its
   * <code>mutex</code> microlock is held by another procesor.
   *
   * Preprocessor directive RVM_WITH_TENTATIVE_MICROLOCKING=1.
   */
  private static final boolean tentativeMicrolocking = true;
//-#else
  /**
   * Persist in attempting to get a heavy-weight lock, if its
   * <code>mutex</code> microlock is held by another procesor.
   *
   * Preprocessor directive RVM_WITH_TENTATIVE_MICROLOCKING=0.
   */
  private static final boolean tentativeMicrolocking = false;
//-#endif

  /**
   * Reports the state of a heavy-weight lock.
   */
  void dump() {
    if (!active) return;
    VM_Scheduler.writeString("Lock "); VM_Scheduler.writeDecimal(index); VM.sysWrite(":\n");
    VM_Scheduler.writeString(" lockedObject: "); VM_Scheduler.writeHex(VM_Magic.objectAsAddress(lockedObject)); VM_Scheduler.writeString("\n");

    VM_Scheduler.writeString(" ownerId: "); VM_Scheduler.writeDecimal(ownerId); VM_Scheduler.writeString(" recursionCount: "); VM_Scheduler.writeDecimal(recursionCount); VM_Scheduler.writeString("\n");
    VM_Scheduler.writeString(" entering: "); entering.dump();
    VM_Scheduler.writeString(" waiting: ");  waiting.dump();

    VM_Scheduler.writeString(" mutexLatestContender: ");
    if (mutex.latestContender == null) VM_Scheduler.writeString("<null>");
    else                     VM_Scheduler.writeHex(mutex.latestContender.id);
    VM_Scheduler.writeString("\n");
  }

    //////////////////////////////////////////////
    //             Statistics                   //
    //////////////////////////////////////////////

    public static void boot () {
	VM_Callbacks.addExitMonitor(new VM_Lock.ExitMonitor());
    }

    static final class ExitMonitor implements VM_Callbacks.ExitMonitor {

	public void notifyExit (int value) {
	    if (! STATS) return;

	    int totalLocks = lockOperations + VM_ThinLock.fastLocks + VM_ThinLock.slowLocks;

	    VM.sysWrite("FatLocks: "); VM.sysWrite(waitOperations, false);      VM.sysWrite(" wait operations\n");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(timedWaitOperations, false); VM.sysWrite(" timed wait operations\n");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(notifyOperations, false);    VM.sysWrite(" notify operations\n");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(notifyAllOperations, false); VM.sysWrite(" notifyAll operations\n");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(lockOperations, false);      VM.sysWrite(" locks");
	    VM_Stats.percentage(lockOperations, totalLocks, "all lock operations");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(unlockOperations, false);    VM.sysWrite(" unlock operations\n");
	    VM.sysWrite("FatLocks: "); VM.sysWrite(deflations, false);          VM.sysWrite(" deflations\n");

	    VM_ThinLock.notifyExit(totalLocks);
	    VM_LockNursery.notifyExit(totalLocks);
	}
    }

    static final boolean STATS = false;

    static int waitOperations;
    static int timedWaitOperations;
    static int notifyOperations;
    static int notifyAllOperations;
    static int lockOperations;
    static int unlockOperations;
    static int deflations;

}
