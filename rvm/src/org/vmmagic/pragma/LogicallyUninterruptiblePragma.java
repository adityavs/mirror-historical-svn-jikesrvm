/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2002
 */
//$Id$
package org.vmmagic.pragma;

import com.ibm.jikesrvm.classloader.*;

/**
 * A pragma that can be used to declare that a 
 * particular method is logically uninterruptible
 * even though it contains bytecodes that are actually interruptible.
 * The effect of this pragma is to supress warning messages
 * about violations of uninterruptiblity when compiling a method 
 * that throws this exception.
 * There are two cases in which using the pragma is justified.
 * <ul>
 * <li> Uninterruptibility is ensured via some other mechansism. 
 *      For example, the method explicitly disables threadswitching
 *      around the interruptible regions (VM.sysWrite on String).  
 *      Or the interruptible regions are not reachable when the VM is 
 *      running (various VM.sysWrite that check VM.runningVM).
 * <li> The interruptible regions represent an 'error' condition that will
 *      never be executed unless the VM is already in the process of reporting
 *      an error, for example VM_Runtime.raiseClassCastException.
 * <ul>
 * Extreme care must be exercised when using this pragma since it supresses 
 * the checking of uninterruptibility.
 * <p>
 * Use of this pragma is being phased out since it lumps together two
 * possible special cases. Use either UnPreemptiblePragma or
 * UninterrpibleNoWarn instead. See also defect 1147447 for more context.
 * for the
 * @deprecated
 * @author Dave Grove
 */
public class LogicallyUninterruptiblePragma extends PragmaException {
  private static final VM_TypeReference me = getTypeRef("Lorg/vmmagic/pragma/LogicallyUninterruptiblePragma;");
  public static boolean declaredBy(VM_Method method) {
    return declaredBy(me, method);
  }
}