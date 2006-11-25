/*
 * This file is part of MMTk (http://jikesrvm.sourceforge.net).
 * MMTk is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2002
 */
package org.mmtk.utility.deque;

import org.mmtk.plan.Plan;
import org.mmtk.utility.Constants;

import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * Note this may perform poorly when being used as a FIFO structure with
 * insertHead and pop operations operating on the same buffer.  This
 * only uses the fields inherited from <code>LocalQueue</code>, but adds
 * the ability for entries to be added to the head of the deque and popped
 * from the rear.
 * 
 * @author Steve Blackburn
 * @author <a href="http://www-ali.cs.umass.edu/~hertz">Matthew Hertz</a>
 * @version $Revision$
 * @date $Date$
 */
@Uninterruptible public class LocalDeque extends LocalQueue 
  implements Constants {
  public final static String Id = "$Id$"; 

  /****************************************************************************
   * 
   * Public instance methods
   */

  /**
   * Constructor
   * 
   * @param queue The shared deque to which this local deque will append
   * its buffers (when full or flushed).
   */
  LocalDeque(SharedDeque queue) {
    super(queue);
  }

  /**
   * Flush the buffer to the shared deque (this will make any entries
   * in the buffer visible to any other consumer associated with the
   * shared deque).
   */
  public final void flushLocal() {
    super.flushLocal();
    if (head.NE(Deque.HEAD_INITIAL_VALUE)) {
      closeAndInsertHead(queue.getArity());
      head = Deque.HEAD_INITIAL_VALUE;
    }
  }

  /****************************************************************************
   * 
   * Protected instance methods
   */

  /**
   * Check whether there is space in the buffer for a pending insert.
   * If there is not sufficient space, allocate a new buffer
   * (dispatching the full buffer to the shared deque if not null).
   *
   * @param arity The arity of the values stored in this deque: the
   * buffer must contain enough space for this many words.
   */
  protected final void checkHeadInsert(int arity) throws InlinePragma {
    if (bufferOffset(head).EQ(bufferSentinel(arity)) || 
        head.EQ(HEAD_INITIAL_VALUE))
      headOverflow(arity);
    else if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bufferOffset(head).sLE(bufferLastOffset(arity)));
  }

  /**
   * Insert a value at the front of the deque (and buffer).  This is 
   * <i>unchecked</i>.  The caller must first call 
   * <code>checkHeadInsert()</code> to ensure the buffer can accommodate 
   * the insertion.
   * 
   * @param value the value to be inserted.
   */
  protected final void uncheckedHeadInsert(Address value) 
    throws InlinePragma {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bufferOffset(head).sLT(bufferSentinel(queue.getArity())));
    head.store(value);
    head = head.plus(BYTES_IN_ADDRESS);
    // if (VM_Interface.VerifyAssertions) enqueued++;
  }

  /****************************************************************************
   * 
   * Private instance methods and fields
   */

  /**
   * Buffer space has been exhausted, allocate a new buffer and enqueue
   * the existing buffer (if any).
   * 
   * @param arity The arity of this buffer (used for sanity test only).
   */
  private final void headOverflow(int arity) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(arity == queue.getArity());
    if (head.NE(Deque.HEAD_INITIAL_VALUE))
      closeAndInsertHead(arity);

    head = queue.alloc();
    Plan.checkForAsyncCollection(); // possible side-effect of alloc()
  }

  /**
   * Close the head buffer and enqueue it at the front of the 
   * shared buffer deque.
   * 
   *  @param arity The arity of this buffer.
   */
  private final void closeAndInsertHead(int arity) throws InlinePragma {
    queue.enqueue(head, arity, false);
  }

  /**
   * The tail is empty (or null), and the shared deque has no buffers
   * available.  If the head has sufficient entries, consume the head.
   * Otherwise try wait on the shared deque until either all other
   * clients of the reach exhaustion or a buffer becomes
   * available.
   * 
   * @param arity The arity of this buffer  
   * @return True if the consumer has eaten all of the entries
   */
  private final boolean tailStarved(int arity) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(arity == queue.getArity());
    // entries in tail, so consume tail
    if (!bufferOffset(head).isZero()) {
      tailBufferEnd = head;
      tail = bufferStart(tailBufferEnd);
      head = Deque.HEAD_INITIAL_VALUE;
      return false;
    }

    // Wait for another entry to materialize...
    tailBufferEnd = queue.dequeueAndWait(arity, true);
    tail = bufferStart(tail);

    // return true if a) there is not a tail buffer or b) it is empty
    return (tail.EQ(Deque.TAIL_INITIAL_VALUE) || tail.EQ(tailBufferEnd));
  }
}
