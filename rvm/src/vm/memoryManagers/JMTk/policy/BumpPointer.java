/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_Word;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

/**
 * This class implements a simple bump pointer allocator.  The
 * allocator operates in <code>BLOCK</code> sized units.  Intra-block
 * allocation is fast, requiring only a load, addition comparison and
 * store.  If a block boundary is encountered the allocator will
 * request more memory (virtual and actual).
 *
 * FIXME This code takes no account of the fact that Jikes RVM can
 * have an object pointer *beyond* the memory allocated for that
 * object---the significance of this is that if the object pointer
 * (rather than the allocated space) is used to test whether an object
 * is within a particular region, it could lie.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */

final class BumpPointer implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  /**
   * Constructor
   *
   * @param vmr The virtual memory resource from which this bump
   * pointer will acquire virtual memory.
   * @param mr The memory resource from which this bump pointer will
   * acquire memory.
   */
  BumpPointer(MonotoneVMResource vmr, MemoryResource mr) {
    bp = INITIAL_BP_VALUE;
    vmResource = vmr;
    memoryResource = mr;
  }

  /**
   * Re-associate this bump pointer with a different virtual memory
   * resource.  Reset the bump pointer so that it will use this virtual
   * memory resource on the next call to <code>alloc</code>.
   *
   * @param vmr The virtual memory resouce with which this bump
   * pointer is to be associated.
   */
  public void rebind(MonotoneVMResource vmr) {
    bp = INITIAL_BP_VALUE;
    vmResource = vmr;
  }

  /**
   * Re-associate this bump pointer with a different memory
   * resource.  Reset the bump pointer so that it will use this 
   * memory resource on the next call to <code>alloc</code>.
   *
   * @param mr The memory resource with which this bump pointer is to
   * be associated.
   */
  public void rebind(MemoryResource mr) {
    bp = INITIAL_BP_VALUE;
    memoryResource = mr;
  }

  /**
   * Allocate space for a new object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @return The address of the first byte of the allocated region
   */
  public VM_Address alloc(boolean isScalar, EXTENT bytes) throws VM_PragmaInline {
    VM_Address oldbp = bp;
    bp = bp.add(bytes);
    VM_Word tmp = oldbp.toWord().xor(bp.toWord());
    if (tmp.GT(VM_Word.fromInt(TRIGGER)))
      return allocSlowPath(bytes);
    return oldbp;
  }


  
  private VM_Address allocSlowPath(EXTENT bytes) throws VM_PragmaNoInline { 
    int blocks = Conversions.bytesToBlocks(bytes);
    memoryResource.acquire(Conversions.blocksToPages(blocks));
    VM_Address start = vmResource.acquire(blocks);
    bp = start.add(bytes);
    return start;
  }


  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private VM_Address bp;
  private MonotoneVMResource vmResource;
  private MemoryResource memoryResource;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Final class variables (aka constants)
  //
  private static final EXTENT TRIGGER = VMResource.BLOCK_SIZE;
  // this ensures the bump pointer will go through slow path on first alloc
  private static final VM_Address INITIAL_BP_VALUE = VM_Address.fromInt(TRIGGER - 1);
}
