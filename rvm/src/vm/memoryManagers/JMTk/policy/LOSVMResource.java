/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_AllocatorHeader;

import com.ibm.JikesRVM.VM_Constants;
import com.ibm.JikesRVM.VM_ProcessorLock;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Array;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 *  A mark-sweep area to hold "large" objects (typically at least 2K).
 *  The large space code is obtained by factoring out the code in various
 *  collectors.
 *
 *  @author Perry Cheng
 */
public class LOSVMResource extends MonotoneVMResource implements Constants {

  public final static String Id = "$Id$"; 

  public void prepare (VMResource _vm, MemoryResource _mr) throws VM_PragmaUninterruptible {
      VM_Memory.zero(VM_Magic.objectAsAddress(mark), 
		     VM_Magic.objectAsAddress(mark).add(2*mark.length));
  }

  public void release (VMResource _vm, MemoryResource _mr) throws VM_PragmaUninterruptible {
      short[] temp    = allocated;
      allocated = mark;
      mark  = temp;
      lastAllocated = 0;
  }

  // Internal management
  private VM_ProcessorLock spaceLock;        // serializes access to large space
  private final int pageSize = 4096;         // large space allocated in 4K chunks
  private final int GC_LARGE_SIZES = 20;           // for statistics  
  private final int GC_INITIAL_LARGE_SPACE_PAGES = 200; // for early allocation of large objs
  private int           totalPages;
  private int		lastAllocated;   // where to start search for free space
  private short[]	allocated;	// used to allocate in large space
  private short[]	mark;		// used to mark large objects


  /**
   * Initialize for boot image - called from init of various collectors
   */
  public LOSVMResource(String name, VM_Address start, EXTENT size, byte status) throws VM_PragmaUninterruptible {
    super(name, start, size, status);
    spaceLock       = new VM_ProcessorLock();      // serializes access to large space
    lastAllocated = 0;
    totalPages = 0;
  }


  /**
   * Initialize for execution.  Meta-data created at boot time.
   */
  public void setup () throws VM_PragmaUninterruptible {

    // Get the (full sized) arrays that control large object space
    totalPages = end.diff(start).toInt() / VM_Memory.getPagesize();
    allocated = VM_Interface.newImmortalShortArray(totalPages + 1);
    mark  = VM_Interface.newImmortalShortArray(totalPages + 1);
  }


  /**
   * Allocate size bytes of zeroed memory.
   * Size is a multiple of wordsize, and the returned memory must be word aligned
   * 
   * @param size Number of bytes to allocate
   * @return Address of allocated storage
   */
  protected VM_Address alloc (boolean isScalar, int size) throws VM_PragmaUninterruptible {

    if (allocated == null) setup();  // not a good way to do it XXXXXX

    for (int count=0; ; count++) {

      spaceLock.lock();

      int num_pages = (size + (pageSize - 1)) / pageSize;    // Number of pages needed
      int last_possible = totalPages - num_pages;

      while (allocated[lastAllocated] != 0) 
	lastAllocated += allocated[lastAllocated];
      int first_free = lastAllocated;
      while (first_free <= last_possible) {
	// Now find contiguous pages for this object
	// first find the first available page
	// i points to an available page: remember it
	int i;
	for (i = first_free + 1; i < first_free + num_pages ; i++) {
	  if (allocated[i] != 0) break;
	}
	if (i == (first_free + num_pages )) {  

	  // successful: found num_pages contiguous pages
	  // mark the newly allocated pages
	  // mark the beginning of the range with num_pages
	  // mark the end of the range with -num_pages
	  // so that when marking (ref is input) will know which extreme 
	  // of the range the ref identifies, and then can find the other
	  
	  allocated[first_free + num_pages - 1] = (short)(-num_pages);
	  allocated[first_free] = (short)(num_pages);
	       
	  spaceLock.unlock();  //release lock *and synch changes*
	  VM_Address result = start.add(VM_Memory.getPagesize() * first_free);
	  VM_Address resultEnd = result.add(size);
	  if (resultEnd.GT(cursor)) {
	    int bytes = resultEnd.diff(cursor).toInt();
	    int blocks = Conversions.bytesToBlocks(bytes);
	    VM_Address newArea = acquire(blocks);
	    if (VM.VerifyAssertions) VM._assert(resultEnd.LE(cursor));
	  }
	  Memory.zero(result, resultEnd);
	  return result;
	} else {  
	  // free area did not contain enough contig. pages
	  first_free = i + allocated[i]; 
	  while (allocated[first_free] != 0) 
	    first_free += allocated[first_free];
	}
      }

      spaceLock.release();  //release lock: won't keep change to large_last_alloc'd
      VM_Interface.getPlan().poll(true);
    }

  }


  boolean isLive (VM_Address ref) throws VM_PragmaUninterruptible {
      VM_Address addr = VM_ObjectModel.getPointerInMemoryRegion(ref);
      if (VM.VerifyAssertions) VM._assert(start.LE(addr) && addr.LE(end));
      int page_num = addr.diff(start).toInt() >> 12;
      return (mark[page_num] != 0);
  }

  VM_Address traceObject (VM_Address ref) throws VM_PragmaUninterruptible {

    VM_Address tref = VM_ObjectModel.getPointerInMemoryRegion(ref);
    // if (VM.VerifyAssertions) VM._assert(addrInHeap(tref));

    int ij;
    int page_num = tref.diff(start).toInt() >>> 12;
    boolean result = (mark[page_num] != 0);
    if (result) return ref;	// fast, no synch case
    
    spaceLock.lock();		// get sysLock for large objects
    result = (mark[page_num] != 0);
    if (result) {	// need to recheck
      spaceLock.release();
      return ref;
    }
    int temp = allocated[page_num];
    if (temp == 1) 
      mark[page_num] = 1;
    else {
      // mark entries for both ends of the range of allocated pages
      if (temp > 0) {
	ij = page_num + temp -1;
	mark[ij] = (short)-temp;
      }
      else {
	ij = page_num + temp + 1;
	mark[ij] = (short)-temp;
      }
      mark[page_num] = (short)temp;
    }

    spaceLock.unlock();	// INCLUDES sync()
    return ref;
  }




}
