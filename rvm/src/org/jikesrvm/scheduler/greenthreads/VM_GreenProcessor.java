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
package org.jikesrvm.scheduler.greenthreads;

import org.jikesrvm.ArchitectureSpecific.VM_ProcessorLocalState;
import org.jikesrvm.VM;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.VM_Entrypoints;
import org.jikesrvm.runtime.VM_Magic;
import static org.jikesrvm.runtime.VM_SysCall.sysCall;
import org.jikesrvm.runtime.VM_Time;
import org.jikesrvm.scheduler.VM_Processor;
import org.jikesrvm.scheduler.VM_ProcessorLock;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.scheduler.VM_Thread;
import org.jikesrvm.scheduler.greenthreads.VM_GlobalGreenThreadQueue;
import org.jikesrvm.scheduler.greenthreads.VM_ThreadIOQueue;
import org.jikesrvm.scheduler.greenthreads.VM_ThreadProcessWaitQueue;
import org.jikesrvm.scheduler.greenthreads.VM_GreenThreadQueue;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.LogicallyUninterruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

/**
 * Multiplex execution of large number of VM_Threads on small
 * number of o/s kernel threads.
 */
@Uninterruptible
public class VM_GreenProcessor extends VM_Processor {
  /**
   * thread previously running on this processor
   */
  public VM_GreenThread previousThread;

  /**
   * Should this processor dispatch a new VM_Thread when
   * "threadSwitch" is called?
   * Also used to decide if it's safe to call yield() when
   * contending for a lock.
   * A value of:
   *    1 means "yes" (switching enabled)
   * <= 0 means "no"  (switching disabled)
   */
  int threadSwitchingEnabledCount;

  /**
   * Was "threadSwitch" called while this processor had
   * thread switching disabled?
   */
  int threadSwitchPending;

  /**
   * threads to be added to ready queue
   */
  public VM_GlobalGreenThreadQueue transferQueue;
  /** guard for transferQueue */
  public final VM_ProcessorLock transferMutex;

  /**
   * threads waiting for a timeslice in which to run
   */
  VM_GreenThreadQueue readyQueue;

  /**
   * Threads waiting for a subprocess to exit.
   */
  VM_ThreadProcessWaitQueue processWaitQueue;

  /**
   * Lock protecting a process wait queue.
   * This is needed because a thread may need to switch
   * to a different <code>VM_Processor</code> in order to perform
   * a waitpid.  (This is because of Linux's weird pthread model,
   * in which pthreads are essentially processes.)
   */
  VM_ProcessorLock processWaitQueueLock;

  /**
   * threads waiting for i/o
   */
  VM_ThreadIOQueue ioQueue;

  /**
   * thread to run when nothing else to do
   */
  VM_GreenThreadQueue idleQueue;

  /**
   * Is the processor doing a select with a wait option
   * A value of:
   *   false means "processor is not executing a select"
   *   true  means "processor is  executing a select with a wait option"
   */
  boolean isInSelect;

  /**
   * Number of timer ticks that have actually been forwarded to the VM from
   * the C time slicing code
   */
  public static int reportedTimerTicks = 0;

  /**
   * How many times has the C time slicing code been entered due to a timer tick.
   * Invariant: timerTicks >= reportedTimerTicks
   * reportedTimerTicks can be lower because we supress the reporting of timer ticks during GC.
   */
  public static int timerTicks = 0;

  /**
   * Number of timer ticks between checks of the process wait
   * queue.  Assuming a tick frequency of 10 milliseconds, we will
   * check about twice per second.  Waiting for processes
   * to die is almost certainly not going to be on a performance-critical
   * code path, and we don't want to add unnecessary overhead to
   * the thread scheduler.
   */
  public static final int NUM_TICKS_BETWEEN_WAIT_POLL = 50;

  /**
   * non-null --> a processor that has no work to do
   */
  static VM_GreenProcessor idleProcessor;

  /**
   * Create data object to be associated with an o/s kernel thread
   * (aka "virtual cpu" or "pthread").
   * @param id id that will be returned by getCurrentProcessorId() for
   * this processor.
   */
  public VM_GreenProcessor(int id) {
    super(id);
    this.transferMutex = new VM_ProcessorLock();
    this.transferQueue = new VM_GlobalGreenThreadQueue(this.transferMutex);
    this.readyQueue = new VM_GreenThreadQueue();
    this.ioQueue = new VM_ThreadIOQueue();
    this.processWaitQueue = new VM_ThreadProcessWaitQueue();
    this.processWaitQueueLock = new VM_ProcessorLock();
    this.idleQueue = new VM_GreenThreadQueue();
    this.isInSelect = false;
  }

  /**
   * Code executed to initialize a virtual processor and
   * prepare it to execute Java threads.
   */
  public void initializeProcessor() {
    // bind our execution to a physical cpu
    //
    if (VM_Scheduler.cpuAffinity != VM_Scheduler.NO_CPU_AFFINITY) {
      sysCall.sysVirtualProcessorBind(VM_Scheduler.cpuAffinity + id - 1);
    }

    sysCall.sysPthreadSetupSignalHandling();

    /* get pthread_id from the operating system and store into vm_processor
       field  */
    pthread_id = sysCall.sysPthreadSelf();

    //
    // tell VM_Scheduler.boot() that we've left the C startup
    // code/stack and are now running vm code/stack
    //
    isInitialized = true;

    sysCall.sysWaitForVirtualProcessorInitialization();

    // enable multiprocessing
    //
    enableThreadSwitching();

    // wait for all other processors to do likewise
    //
    sysCall.sysWaitForMultithreadingStart();

    // Store VM_Processor in pthread
    sysCall.sysStashVmProcessorInPthread(this);
  }

  /**
   * Is it ok to switch to a new VM_Thread in this processor?
   */
  @Inline
  @Override
  public boolean threadSwitchingEnabled() {
    return threadSwitchingEnabledCount == 1;
  }

  /**
   * Enable thread switching in this processor.
   */
  @Override
  public void enableThreadSwitching() {
    ++threadSwitchingEnabledCount;
    if (VM.VerifyAssertions) {
      VM._assert(threadSwitchingEnabledCount <= 1);
      if (MM_Interface.gcInProgress()) {
        VM._assert(threadSwitchingEnabledCount < 1 || getCurrentProcessorId() == 0);
      }
    }
    if (threadSwitchingEnabled() && threadSwitchPending != 0) {
      takeYieldpoint = threadSwitchPending;
      threadSwitchPending = 0;
    }
  }

  /**
   * Disable thread switching in this processor.
   */
  @Inline
  @Override
  public void disableThreadSwitching() {
    --threadSwitchingEnabledCount;
  }

  /**
   * Request the thread executing on the processor to take the next executed yieldpoint
   * and initiate a GC
   */
  @Override
  public void requestYieldToGC() {
    takeYieldpoint = 1;
    yieldToGCRequested = true;
  }

  /**
   * Request the thread executing on the processor to take the next executed yieldpoint
   * and issue memory synchronization instructions
   */
  @Override
  public void requestPostCodePatchSync() {
    if (VM.BuildForPowerPC) {
      takeYieldpoint = 1;
      codePatchSyncRequested = true;
    } else {
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }
  }

  /**
   * Get processor that's being used to run the current java thread.
   */
  @Inline
  public static VM_GreenProcessor getCurrentProcessor() {
    return (VM_GreenProcessor)VM_ProcessorLocalState.getCurrentProcessor();
  }

  /**
   * Get id of processor that's being used to run the current java thread.
   */
  @Inline
  public static int getCurrentProcessorId() {
    return getCurrentProcessor().id;
  }

  /**
   * Become next "ready" thread.
   * Note: This method is ONLY intended for use by VM_Thread.
   * @param timerTick   timer interrupted if true
   */
  @Override
  public void dispatch(boolean timerTick) {
    if (lockCount != 0) {
      VM.sysWriteln("Lock count==", lockCount);
      VM_Scheduler.dumpVirtualMachine();
    }
    // no processor locks should be held across a thread switch
    if (VM.VerifyAssertions) VM._assert(lockCount == 0);

    VM_GreenThread newThread = getRunnableThread();
    while (newThread.suspendIfPending()) {
      newThread = getRunnableThread();
    }

    previousThread = (VM_GreenThread)activeThread;
    activeThread = (VM_GreenThread)newThread;

    if (!previousThread.isDaemonThread() && idleProcessor != null && !readyQueue.isEmpty()) {
      // if we've got too much work, transfer some of it to another
      // processor that has nothing to do
      // don't schedule when switching away from a daemon thread...
      // kludge to avoid thrashing when VM is underloaded with real threads.
      VM_GreenThread t = readyQueue.dequeue();
      if (VM.TraceThreadScheduling > 0) VM_Scheduler.trace("VM_Processor", "dispatch: offload ", t.getIndex());
      scheduleThread(t);
    }

    // Accumulate CPU time on a per thread basis.
    // Used by the adaptive system and compilation measurement.
    long now = VM_Time.cycles();
    previousThread.endQuantum(now);
    newThread.startQuantum(now);

    threadId = newThread.getLockingId();
    activeThreadStackLimit = newThread.stackLimit; // Delay this to last possible moment so we can sysWrite
    VM_Magic.threadSwitch(previousThread, newThread.contextRegisters);
  }

  /**
   * Find a thread that can be run by this processor and remove it
   * from its queue.
   */
  @Inline
  private VM_GreenThread getRunnableThread() {

    for (int i = transferQueue.length(); 0 < i; i--) {
      transferMutex.lock();
      VM_GreenThread t = transferQueue.dequeue();
      transferMutex.unlock();
      if (t.isGCThread()) {
        if (VM.TraceThreadScheduling > 1) {
          VM_Scheduler.trace("VM_Processor", "getRunnableThread: collector thread", t.getIndex());
        }
        return t;
      } else if (t.beingDispatched && t != VM_Scheduler.getCurrentThread()) {
        // thread's stack in use by some OTHER dispatcher
        if (VM.TraceThreadScheduling > 1) {
          VM_Scheduler.trace("VM_Processor", "getRunnableThread: stack in use", t.getIndex());
        }
        transferMutex.lock();
        transferQueue.enqueue(t);
        transferMutex.unlock();
      } else {
        if (VM.TraceThreadScheduling > 1) {
          VM_Scheduler.trace("VM_Processor", "getRunnableThread: transfer to readyQueue", t.getIndex());
        }
        readyQueue.enqueue(t);
      }
    }

    if ((reportedTimerTicks % VM_GreenScheduler.numProcessors) + 1 == id) {
      // it's my turn to check the io queue early to avoid starvation
      // of threads in io wait.
      // We round robin this among the virtual processors to avoid serializing
      // thread switching in the call to select.
      if (ioQueue.isReady()) {
        VM_GreenThread t = ioQueue.dequeue();
        if (VM.TraceThreadScheduling > 1) {
          VM_Scheduler.trace("VM_Processor", "getRunnableThread: ioQueue (early)", t.getIndex());
        }
        if (VM.VerifyAssertions) {
          // local queue: no other dispatcher should be running on thread's stack
          VM._assert(!t.beingDispatched || t == VM_Scheduler.getCurrentThread());
        }
        return t;
      }
    }

    // FIXME - Need to think about whether we want a more
    // intelligent way to do this; for example, handling SIGCHLD,
    // and using that to implement the wakeup.  Polling is considerably
    // simpler, however.
    if ((reportedTimerTicks % NUM_TICKS_BETWEEN_WAIT_POLL) == id) {
      VM_GreenThread result = null;

      processWaitQueueLock.lock();
      if (processWaitQueue.isReady()) {
        VM_GreenThread t = processWaitQueue.dequeue();
        if (VM.VerifyAssertions) {
          // local queue: no other dispatcher should be running on thread's stack
          VM._assert(!t.beingDispatched ||
                     t == VM_Scheduler.getCurrentThread());
        }
        result = t;
      }
      processWaitQueueLock.unlock();
      if (result != null) {
        return result;
      }
    }

    if (!readyQueue.isEmpty()) {
      VM_GreenThread t = readyQueue.dequeue();
      if (VM.TraceThreadScheduling > 1) {
        VM_Scheduler.trace("VM_Processor", "getRunnableThread: readyQueue", t.getIndex());
      }
      if (VM.VerifyAssertions) {
        // local queue: no other dispatcher should be running on thread's stack
        VM._assert(!t.beingDispatched ||
            t == VM_Scheduler.getCurrentThread());
      }
      return t;
    }

    if (ioQueue.isReady()) {
      VM_GreenThread t = ioQueue.dequeue();
      if (VM.TraceThreadScheduling > 1) VM_Scheduler.trace("VM_Processor", "getRunnableThread: ioQueue", t.getIndex());
      if (VM.VerifyAssertions) {
        // local queue: no other dispatcher should be running on thread's stack
        VM._assert(!t.beingDispatched ||
                   t == VM_Scheduler.getCurrentThread());
      }
      return t;
    }

    if (!idleQueue.isEmpty()) {
      VM_GreenThread t = idleQueue.dequeue();
      if (VM.TraceThreadScheduling > 1) {
        VM_Scheduler.trace("VM_Processor", "getRunnableThread: idleQueue", t.getIndex());
      }
      if (VM.VerifyAssertions) {
        // local queue: no other dispatcher should be running on thread's stack
        VM._assert(!t.beingDispatched ||
                   t == VM_Scheduler.getCurrentThread());
      }
      return t;
    }

    VM._assert(VM.NOT_REACHED); // should never get here (the idle thread should always be: running, on the idleQueue, or (maybe) on the transferQueue)
    return null;
  }

  //-----------------//
  //  Load Balancing //
  //-----------------//

  /**
   * Add a thread to this processor's transfer queue.
   */
  private void transferThread(VM_GreenThread t) {
    if (this != getCurrentProcessor() || t.isGCThread() ||
        (t.beingDispatched && t != VM_Scheduler.getCurrentThread())) {
      transferMutex.lock();
      transferQueue.enqueue(t);
      transferMutex.unlock();
    } else if (t.isIdleThread()) {
      idleQueue.enqueue(t);
    } else {
      readyQueue.enqueue(t);
    }
  }

  /**
   * Put thread onto most lightly loaded virtual processor.
   */
  public void scheduleThread(VM_GreenThread t) {
    // if thread wants to stay on specified processor, put it there
    if (t.processorAffinity != null) {
      if (VM.TraceThreadScheduling > 0) {
        VM_Scheduler.trace("VM_Processor.scheduleThread", "outgoing to specific processor:", t.getIndex());
      }
      t.processorAffinity.transferThread(t);
      return;
    }

    // if t is the last runnable thread on this processor, don't move it
    if (t == VM_Scheduler.getCurrentThread() && readyQueue.isEmpty() && transferQueue.isEmpty()) {
      if (VM.TraceThreadScheduling > 0) {
        VM_Scheduler.trace("VM_Processor.scheduleThread", "staying on same processor:", t.getIndex());
      }
      getCurrentProcessor().transferThread(t);
      return;
    }

    // if a processor is idle, transfer t to it
    VM_GreenProcessor idle = idleProcessor;
    if (idle != null) {
      idleProcessor = null;
      if (VM.TraceThreadScheduling > 0) {
        VM_Scheduler.trace("VM_Processor.scheduleThread", "outgoing to idle processor:", t.getIndex());
      }
      idle.transferThread(t);
      return;
    }

    // otherwise distribute threads round robin
    if (VM.TraceThreadScheduling > 0) {
      VM_Scheduler.trace("VM_Processor.scheduleThread", "outgoing to round-robin processor:", t.getIndex());
    }
    chooseNextProcessor(t).transferThread(t);

  }

  /**
   * Cycle (round robin) through the available processors.
   */
  private VM_GreenProcessor chooseNextProcessor(VM_GreenThread t) {
    t.chosenProcessorId = (t.chosenProcessorId % VM_GreenScheduler.numProcessors) + 1;
    return VM_GreenScheduler.processors[t.chosenProcessorId];
  }

  //---------------------//
  // Garbage Collection  //
  //---------------------//

  @Override
  public boolean unblockIfBlockedInC() {
    int newState, oldState;
    boolean result = true;
    Offset offset = VM_Entrypoints.vpStatusField.getOffset();
    do {
      oldState = VM_Magic.prepareInt(this, offset);
      if (oldState != BLOCKED_IN_NATIVE) {
        result = false;
        break;
      }
      newState = IN_NATIVE;
    } while (!(VM_Magic.attemptInt(this, offset, oldState, newState)));
    return result;
  }

  /**
   * sets the VP status to BLOCKED_IN_NATIVE if it is currently IN_NATIVE (ie C)
   * returns true if BLOCKED_IN_NATIVE
   */
  @Override
  public boolean lockInCIfInC() {
    int oldState;
    Offset offset = VM_Entrypoints.vpStatusField.getOffset();
    do {
      oldState = VM_Magic.prepareInt(this, offset);
      if (VM.VerifyAssertions) VM._assert(oldState != BLOCKED_IN_NATIVE);
      if (oldState != IN_NATIVE) {
        if (VM.VerifyAssertions) VM._assert(oldState == IN_JAVA);
        return false;
      }
    } while (!(VM_Magic.attemptInt(this, offset, oldState, BLOCKED_IN_NATIVE)));
    return true;
  }

  @LogicallyUninterruptible
  /* GACK --dave */
  public void dumpProcessorState() {
    VM.sysWrite("Processor ");
    VM.sysWriteInt(id);
    if (this == VM_GreenProcessor.getCurrentProcessor()) VM.sysWrite(" (me)");
    VM.sysWrite(" running thread");
    if (activeThread != null) {
      activeThread.dump();
    } else {
      VM.sysWrite(" NULL Active Thread");
    }
    VM.sysWrite("\n");
    VM.sysWrite(" system thread id ");
    VM.sysWriteInt(pthread_id);
    VM.sysWrite("\n");
    VM.sysWrite(" transferQueue:");
    if (transferQueue != null) transferQueue.dump();
    VM.sysWrite(" readyQueue:");
    if (readyQueue != null) readyQueue.dump();
    VM.sysWrite(" ioQueue:");
    if (ioQueue != null) ioQueue.dump();
    VM.sysWrite(" processWaitQueue:");
    if (processWaitQueue != null) processWaitQueue.dump();
    VM.sysWrite(" idleQueue:");
    if (idleQueue != null) idleQueue.dump();
    VM.sysWrite(" status: ");
    int status = vpStatus;
    if (status == IN_NATIVE) VM.sysWrite("IN_NATIVE\n");
    if (status == IN_JAVA) VM.sysWrite("IN_JAVA\n");
    if (status == BLOCKED_IN_NATIVE) VM.sysWrite("BLOCKED_IN_NATIVE\n");
    VM.sysWrite(" timeSliceExpired: ");
    VM.sysWriteInt(timeSliceExpired);
    VM.sysWrite("\n");
  }
}