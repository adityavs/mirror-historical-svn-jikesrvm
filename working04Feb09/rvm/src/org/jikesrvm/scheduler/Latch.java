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

import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.Uninterruptible;

/**
 * An implementation of a latch using the HeavyCondLock in "nice" mode.
 * This essentially gives you park/unpark functionality.  It can also
 * be used like the Win32-style AutoResetEvent or ManualResetEvent.
 * <p>
 * Park/unpark example: use open() to unpark and waitAndClose() to park.
 * <p>
 * AutoResetEvent example: use open() to set, close() to reset, and
 * waitAndClose() to wait.
 * <p>
 * ManualResetEvent example: use open() to set, close() to reset, and
 * wait() to wait.
 */
@Unpreemptible
public class Latch {
  private final HeavyCondLock schedLock = new HeavyCondLock();
  private boolean open;
  /** Create a new latch, with the given open/closed state. */
  public Latch(boolean open) {
    this.open = open;
  }
  /**
   * Open the latch and let all of the thread(s) waiting on it through.
   * But - if any of the threads is using waitAndClose(), then as soon
   * as that thread awakes further threads will be blocked.
   */
  public void open() {
    schedLock.lockNicely();
    open=true;
    schedLock.broadcast();
    schedLock.unlock();
  }
  /**
   * Like open(), but does it without letting the system know that we
   * could potentially block.  This is faster, and better for use in
   * interrupt handlers.
   */
  @Uninterruptible
  public void openDangerously() {
    schedLock.lock();
    open=true;
    schedLock.broadcast();
    schedLock.unlock();
  }
  /**
   * Close the latch, causing future calls to wait() or waitAndClose()
   * to block.
   */
  public void close() {
    schedLock.lockNicely();
    open=false;
    schedLock.unlock();
  }
  /**
   * Wait for the latch to become open.  If it is already open, don't
   * wait at all.
   */
  public void await() {
    schedLock.lockNicely();
    while (!open) {
      schedLock.waitNicely();
    }
    schedLock.unlock();
  }
  /**
   * Wait for the latch to become open, and then close it and return.
   * If the latch is already open, don't wait at all, just close it
   * immediately and return.
   */
  public void waitAndClose() {
    schedLock.lockNicely();
    while (!open) {
      schedLock.waitNicely();
    }
    open=false;
    schedLock.unlock();
  }
}

/* For the emacs weenies in the crowd.
Local Variables:
   c-basic-offset: 2
End:
*/
