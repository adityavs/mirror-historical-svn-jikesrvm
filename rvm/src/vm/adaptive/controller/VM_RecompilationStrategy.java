/*
 * (C) Copyright IBM Corp. 2001
 */
package com.ibm.JikesRVM.adaptive;

import com.ibm.JikesRVM.opt.*;
import com.ibm.JikesRVM.VM_CompiledMethod;
import com.ibm.JikesRVM.VM_Method;
import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_RuntimeOptCompilerInfrastructure;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

/**
 * VM_Recompilation Strategy
 *
 * An abstract class providing the interface to the decision making
 * component of the controller. 
 *
 * @author Matthew Arnold
 */
abstract class VM_RecompilationStrategy {

  //------  Interface -------

  /**
   * A hot method has been passed to the controller by an organizer
   */
  VM_ControllerPlan considerHotMethod(VM_CompiledMethod cmpMethod,
				      VM_HotMethodEvent hme) {
    // Default behavior, do nothing.
    return null;
  }


  /**
   * A hot call edge has been passed to the controller by an organizer
   */
  void considerHotCallEdge(VM_CompiledMethod cmpMethod, 
			   VM_AINewHotEdgeEvent event) {
    // Default behavior, do nothing.
  }


  // -- Functionality common to all recompilation strategies (at least
  //    for now) --


  /**
   *  Initialize the recompilation strategy.
   *
   *  Note: This uses the command line options to set up the
   *  optimization plans, so this must be run after the command line
   *  options are available.  
   */
  void init() {
    createOptimizationPlans();
  }


  /**
   * This helper method creates a ControllerPlan, which contains a 
   * CompilationPlan, for the passed method using the passed optimization 
   * level and instrumentation plan.
   * 
   * @param method the VM_Method for the plan
   * @param optLevel the optimization level to use in the plan
   * @param instPlan the instrumentation plan to use
   * @param prevCMID the previous compiled method ID
   * @param expectedSpeedup  expected speedup from this recompilation
   * @param priority a measure of the oveall benefit we expect to see
   *                 by executing this plan.
   * @return the compilation plan to be used 
   */

   VM_ControllerPlan createControllerPlan(VM_Method method, 
					  int optLevel,
					  OPT_InstrumentationPlan instPlan,
					  int prevCMID,
					  double expectedSpeedup,
					  double priority) {

     // Construct the compilation plan (varies depending on strategy)
     OPT_CompilationPlan compPlan = createCompilationPlan(method,
							optLevel,
							instPlan);

    if (VM_Controller.options.ADAPTIVE_INLINING) {
      OPT_InlineOracle inlineOracle = 
	VM_AdaptiveInlining.getInlineOracle(method);
      compPlan.setInlineOracle(inlineOracle);
    }

    // Create the controller plan
    return new VM_ControllerPlan(compPlan, VM_Controller.controllerClock, 
				 prevCMID, expectedSpeedup, priority);
   }


  /**
   * Construct a compilation plan that will compile the given method
   * with instrumentation.  
   *
   * @param method The method to be compiled with instrumentation
   * @param optLevel The opt-level to recompile at 
   * @param instPlan The instrumentation plan
   */
  OPT_CompilationPlan createCompilationPlan(VM_Method method, 
					    int optLevel,
					    OPT_InstrumentationPlan instPlan) {

     // Construct a plan from the basic pre-computed opt-levels
     return new OPT_CompilationPlan(method, _optPlans[optLevel],
				    null, _options[optLevel]);
   }

  /**
   * Should we consider the hme for recompilation?
   * 
   * @param hme the VM_HotMethodEvent
   * @param plan the VM_ControllerPlan for the compiled method (may be null)
   * @param prevCompiler the previous compiler 
   * 
   */
  boolean considerForRecompilation(VM_HotMethodEvent hme, 
				   VM_ControllerPlan plan) {
    VM_Method method = hme.getMethod();
    if (plan == null) {
      // Our caller did not find a matching plan for this compiled method.
      // Therefore the code was not generated by the AOS recompilation subsystem. 
      if (VM_ControllerMemory.shouldConsiderForInitialRecompilation(method)) {
	// AOS has not already taken action to address the situation 
	// (or it attempted to take action, and the attempt failed in a way
	//  that doesn't preclude trying again,
	//  for example the compilation queue could have been full).  
	return true;
      } else {
	// AOS has already taken action to address the situation, and thus
	// we need to handle this as an old compiled version of a 
        // method still being live on some thread's stack.
	if (VM.LogAOSEvents) VM_AOSLogging.oldVersionStillHot(hme); 
	VM_Controller.methodSamples.reset(hme.getCMID());
	return false;
      }
    } else {
      // A matching plan was found.
      if (plan.getStatus() == VM_ControllerPlan.OUTDATED || 
	  VM_ControllerMemory.planWithStatus(method, 
					     VM_ControllerPlan.IN_PROGRESS)) {
	// (a) The HotMethodEvent actually corresponds to an 
        // old compiled version of the method
	// that is still live on some thread's stack or 
        // (b) AOS has already initiated a plan that hasn't
	// completed yet to address the situation. 
        // Therefore don't initiate a new recompilation action.
	if (VM.LogAOSEvents) VM_AOSLogging.oldVersionStillHot(hme); 
	VM_Controller.methodSamples.reset(hme.getCMID());
	return false;
      }
      if (VM_ControllerMemory.planWithStatus(method, 
					     VM_ControllerPlan.ABORTED_COMPILATION_ERROR)) {
	// AOS failed to successfully recompile this method before.  
        // Don't try it again.
	return false;
      }
      return true;
    }
  }


  /**
   *  This method returns true if we've already tried to recompile the
   *  passed method.  It does not guarantee that the compilation was
   *  successful.
   * 
   *  @param method the method of interest
   *  @return whether we've tried to recompile this method
   */
   boolean previousRecompilationAttempted(VM_Method method) {
    return  VM_ControllerMemory.findLatestPlan(method) != null;
  }

  /**
   *  This method retrieves the previous compiler constant.
   */
   int getPreviousCompiler(VM_CompiledMethod cmpMethod) {
    switch(cmpMethod.getCompilerType()) {
    case VM_CompiledMethod.TRAP: 
    case VM_CompiledMethod.JNI:
      return -1; // don't try to optimize these guys!
    case VM_CompiledMethod.BASELINE:
      { 
	// Prevent the adaptive system from recompiling certain classes
	// of baseline compiled methods.
	if (cmpMethod.getMethod().getDeclaringClass().isDynamicBridge()) {
	  // The opt compiler does not implement this calling convention.
	  return -1;
	} 
	if (cmpMethod.getMethod().getDeclaringClass().isBridgeFromNative()) {
	  // The opt compiler does not implement this calling convention.
	  return -1;
	}
	if (VM_Interface.MOVES_OBJECTS && !cmpMethod.getMethod().isInterruptible()) {
	  // A crude filter to identify the subset of core VM methods that 
	  // can't be recompiled because we require their code to be non-moving.
	  // We really need to do a better job of this to avoid missing too many opportunities.
	  return -1;
	}
	return 0;
      }
    case VM_CompiledMethod.OPT:
      VM_OptCompiledMethod optMeth = (VM_OptCompiledMethod)cmpMethod;
      return VM_CompilerDNA.getCompilerConstant(optMeth.getOptLevel());
    default:
      if (VM.VerifyAssertions) VM._assert(false, "Unknown Compiler");
      return -1;
    }
  }

  /**
   * What is the maximum opt level that is vallid according to this strategy?
   */
  int getMaxOptLevel() {
    return VM_Controller.options.MAX_OPT_LEVEL;
  }


  /**
   * Create the default set of <optimization plan, options> pairs
   * Process optimizing compiler command line options.
   */
  private  OPT_OptimizationPlanElement[][] _optPlans;
  private  OPT_Options[] _options;

   void createOptimizationPlans() {
    OPT_Options options = new OPT_Options();

    int maxOptLevel = getMaxOptLevel();
    _options = new OPT_Options[maxOptLevel+1];
    _optPlans = new OPT_OptimizationPlanElement[maxOptLevel+1][];
    String[] optCompilerOptions = VM_Controller.getOptCompilerOptions();
    for (int i=0; i<= maxOptLevel; i++) {
      _options[i] = (OPT_Options)options.clone();
      _options[i].setOptLevel(i);		// set optimization level specific optimiations
      processCommandLineOptions(_options[i],i,maxOptLevel,optCompilerOptions);
      _optPlans[i]=OPT_OptimizationPlanner.createOptimizationPlan(_options[i]);
      if (_options[i].PRELOAD_CLASS != null) {
	VM.sysWrite("In an adaptive system, PRELOAD_CLASS should be specified with -X:aos:irc not -X:aos:opt\n");
	VM.sysExit(1);
      }
    }
  }

  /**
   * Process the command line arguments and pass the appropriate ones to the 
   * OPT_Options
   * 
   * @param options The options being constructed
   * @param optLevel The level of the options being constructed
   * @param maxOptLevel The maximum valid opt level
   * @param optCompilerOptions The list of command line options
   */
  void processCommandLineOptions(OPT_Options options, int optLevel, int maxOptLevel,
			 String optCompilerOptions[]) {

    String prefix = "opt"+optLevel+":";
    for (int j=0; j<optCompilerOptions.length; j++) {
      if (optCompilerOptions[j].startsWith("opt:")) {
	String option = optCompilerOptions[j].substring(4);
	if (!options.processAsOption("-X:aos:opt:", option)) {
	  VM.sysWrite("vm: Unrecognized optimizing compiler command line argument: \""
		      +option+"\" passed in as "
		      +optCompilerOptions[j]+"\n");
	}
      } else if (optCompilerOptions[j].startsWith(prefix)) {
	String option = optCompilerOptions[j].substring(5);
	if (!options.processAsOption("-X:aos:"+prefix, option)) {
	  VM.sysWrite("vm: Unrecognized optimizing compiler command line argument: \""
		      +option+"\" passed in as "
		      +optCompilerOptions[j]+"\n");
	}
      }
    }
    // TODO: check for optimization levels that are invalid; that is, 
    // greater than optLevelMax.
    //
    for (int j=0; j<optCompilerOptions.length; j++) {
      if (!optCompilerOptions[j].startsWith("opt")) {
	// This should never be the case!
	continue;
      }
      if (! optCompilerOptions[j].startsWith("opt:")) {
	// must specify optimization level!
	int endPoint = optCompilerOptions[j].indexOf(":");
	if (endPoint == -1) {
	  VM.sysWrite("vm: Unrecognized optimization level in optimizing compiler command line argument: \""
		      +optCompilerOptions[j]+"\"\n");
	}
	String optLevelS;
	try {
	  optLevelS = optCompilerOptions[j].substring(3,endPoint);
	} catch (IndexOutOfBoundsException e) {
	  VM.sysWrite("vm internal error: trying to find opt level has thrown indexOutOfBoundsException\n");
	  e.printStackTrace();
	  continue;
	}
	try {
	  Integer optLevelI = new Integer(optLevelS);
	  int cmdOptLevel = optLevelI.intValue();
	  if (cmdOptLevel > maxOptLevel) {
	    VM.sysWrite("vm: Invalid optimization level in optimizing compiler command line argument: \""
			+optCompilerOptions[j]+"\"\n"+
			"  Specified optimization level "+cmdOptLevel+
			" must be less than "+maxOptLevel+"\n");
	  }
	} catch (NumberFormatException e) {
	  VM.sysWrite("vm: Unrecognized optimization level in optimizing compiler command line argument: \""
		      +optCompilerOptions[j]+"\"\n");
	}
      }
    }
    VM_RuntimeOptCompilerInfrastructure.setNoCacheFlush(options);
  }
}




