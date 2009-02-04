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
package org.jikesrvm.compilers.common;

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.ArchitectureSpecific.JNICompiler;
import org.jikesrvm.VM;
import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.adaptive.controller.Controller;
import org.jikesrvm.adaptive.controller.ControllerMemory;
import org.jikesrvm.adaptive.controller.ControllerPlan;
import org.jikesrvm.adaptive.recompilation.InvocationCounts;
import org.jikesrvm.adaptive.recompilation.PreCompile;
import org.jikesrvm.adaptive.recompilation.instrumentation.AOSInstrumentationPlan;
import org.jikesrvm.adaptive.util.AOSGenerator;
import org.jikesrvm.adaptive.util.AOSLogging;
import org.jikesrvm.adaptive.util.CompilerAdviceAttribute;
import org.jikesrvm.classloader.NativeMethod;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.baseline.BaselineCompiler;
import org.jikesrvm.compilers.opt.MagicNotImplementedException;
import org.jikesrvm.compilers.opt.OptimizingCompilerException;
import org.jikesrvm.compilers.opt.OptOptions;
import org.jikesrvm.compilers.opt.driver.CompilationPlan;
import org.jikesrvm.compilers.opt.driver.OptimizationPlanElement;
import org.jikesrvm.compilers.opt.driver.OptimizationPlanner;
import org.jikesrvm.compilers.opt.driver.OptimizingCompiler;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.RVMThread;

/**
 * Harness to select which compiler to dynamically
 * compile a method in first invocation.
 *
 * A place to put code common to all runtime compilers.
 * This includes instrumentation code to get equivalent data for
 * each of the runtime compilers.
 * <p>
 * We collect the following data for each compiler
 * <ol>
 * <li>
 *   total number of methods complied by the compiler
 * <li>
 *   total compilation time in milliseconds.
 * <li>
 *   total number of bytes of bytecodes compiled by the compiler
 *   (under the assumption that there is no padding in the bytecode
 *   array and thus RVMMethod.getBytecodes().length is the number bytes
 *   of bytecode for a method)
 * <li>
 *   total number of machine code insructions generated by the compiler
 *   (under the assumption that there is no (excessive) padding in the
 *   machine code array and thus CompiledMethod.numberOfInsturctions()
 *   is a close enough approximation of the number of machinecodes generated)
 * </ol>
 *   Note that even if 3. & 4. are inflated due to padding, the numbers will
 *   still be an accurate measure of the space costs of the compile-only
 *   approach.
 */
public class RuntimeCompiler implements Constants, Callbacks.ExitMonitor {

  // Use these to encode the compiler for record()
  public static final byte JNI_COMPILER = 0;
  public static final byte BASELINE_COMPILER = 1;
  public static final byte OPT_COMPILER = 2;

  // Data accumulators
  private static final String[] name = {"JNI\t", "Base\t", "Opt\t"};   // Output names
  private static int[] totalMethods = {0, 0, 0};
  private static double[] totalCompTime = {0, 0, 0};
  private static int[] totalBCLength = {0, 0, 0};
  private static int[] totalMCLength = {0, 0, 0};

  // running sum of the natural logs of the rates,
  //  used for geometric mean, the product of rates is too big for doubles
  //  so we use the principle of logs to help us
  // We compute  e ** ((log a + log b + ... + log n) / n )
  private static double[] totalLogOfRates = {0, 0, 0};

  // We can't record values until Math.log is loaded, so we miss the first few
  private static int[] totalLogValueMethods = {0, 0, 0};

  private static String[] earlyOptArgs = new String[0];

  // is the opt compiler usable?
  protected static boolean compilerEnabled;

  // is opt compiler currently in use?
  // This flag is used to detect/avoid recursive opt compilation.
  // (ie when opt compilation causes a method to be compiled).
  // We also make all public entrypoints static synchronized methods
  // because the opt compiler is not reentrant.
  // When we actually fix defect 2912, we'll have to implement a different
  // scheme that can distinguish between recursive opt compilation by the same
  // thread (always bad) and parallel opt compilation (currently bad, future ok).
  // NOTE: This code can be quite subtle, so please be absolutely sure
  // you know what you're doing before modifying it!!!
  protected static boolean compilationInProgress;

  // One time check to optionally preload and compile a specified class
  protected static boolean preloadChecked = false;

  // Cache objects needed to cons up compilation plans
  // TODO: cutting link to opt compiler by declaring type as object.
  public static final Object /* Options */ options = VM.BuildForAdaptiveSystem ? new OptOptions() : null;
  public static Object /* OptimizationPlanElement[] */ optimizationPlan;

  /**
   * To be called when the VM is about to exit.
   * @param value the exit value
   */
  public void notifyExit(int value) {
    report(false);
  }

  /**
   * This method records the time and sizes (bytecode and machine code) for
   * a compilation.
   * @param compiler the compiler used
   * @param method the resulting RVMMethod
   * @param compiledMethod the resulting compiled method
   */
  public static void record(byte compiler, NormalMethod method, CompiledMethod compiledMethod) {

    recordCompilation(compiler,
                      method.getBytecodeLength(),
                      compiledMethod.numberOfInstructions(),
                      compiledMethod.getCompilationTime());

    if (VM.BuildForAdaptiveSystem) {
      if (AOSLogging.logger.booted()) {
        AOSLogging.logger.recordUpdatedCompilationRates(compiler,
                                                    method,
                                                    method.getBytecodeLength(),
                                                    totalBCLength[compiler],
                                                    compiledMethod.numberOfInstructions(),
                                                    totalMCLength[compiler],
                                                    compiledMethod.getCompilationTime(),
                                                    totalCompTime[compiler],
                                                    totalLogOfRates[compiler],
                                                    totalLogValueMethods[compiler],
                                                    totalMethods[compiler]);
      }
    }
  }

  /**
   * This method records the time and sizes (bytecode and machine code) for
   * a compilation
   * @param compiler the compiler used
   * @param method the resulting RVMMethod
   * @param compiledMethod the resulting compiled method
   */
  public static void record(byte compiler, NativeMethod method, CompiledMethod compiledMethod) {

    recordCompilation(compiler, 0, // don't have any bytecode info, its native
                      compiledMethod.numberOfInstructions(), compiledMethod.getCompilationTime());
  }

  /**
   * This method does the actual recording
   * @param compiler the compiler used
   * @param BCLength the number of bytecodes in method source
   * @param MCLength the length of the generated machine code
   * @param compTime the compilation time in ms
   */
  private static void recordCompilation(byte compiler, int BCLength, int MCLength, double compTime) {

    totalMethods[compiler]++;
    totalMCLength[compiler] += MCLength;
    totalCompTime[compiler] += compTime;

    // Comp rate not useful for JNI compiler because there is no bytecode!
    if (compiler != JNI_COMPILER) {
      totalBCLength[compiler] += BCLength;
      double rate = BCLength / compTime;

      // need to be fully booted before calling log
      if (VM.fullyBooted) {
        // we want the geometric mean, but the product of rates is too big
        //  for doubles, so we use the principle of logs to help us
        // We compute  e ** ((log a + log b + ... + log n) / n )
        totalLogOfRates[compiler] += Math.log(rate);
        totalLogValueMethods[compiler]++;
      }
    }
  }

  /**
   * This method produces a summary report of compilation activities
   * @param explain Explains the metrics used in the report
   */
  public static void report(boolean explain) {
    VM.sysWrite("\n\t\tCompilation Subsystem Report\n");
    VM.sysWrite("Comp\t#Meths\tTime\tbcb/ms\tmcb/bcb\tMCKB\tBCKB\n");
    for (int i = 0; i <= name.length - 1; i++) {
      if (totalMethods[i] > 0) {
        VM.sysWrite(name[i]);
        // Number of methods
        VM.sysWrite(totalMethods[i]);
        VM.sysWrite("\t");
        // Compilation time
        VM.sysWrite(totalCompTime[i]);
        VM.sysWrite("\t");

        if (i == JNI_COMPILER) {
          VM.sysWrite("NA");
        } else {
          // Bytecode bytes per millisecond,
          //  use unweighted geomean
          VM.sysWrite(Math.exp(totalLogOfRates[i] / totalLogValueMethods[i]), 2);
        }
        VM.sysWrite("\t");
        // Ratio of machine code bytes to bytecode bytes
        if (i != JNI_COMPILER) {
          VM.sysWrite((double) (totalMCLength[i] << ArchitectureSpecific.RegisterConstants.LG_INSTRUCTION_WIDTH) /
                      (double) totalBCLength[i], 2);
        } else {
          VM.sysWrite("NA");
        }
        VM.sysWrite("\t");
        // Generated machine code Kbytes
        VM.sysWrite((double) (totalMCLength[i] << ArchitectureSpecific.RegisterConstants.LG_INSTRUCTION_WIDTH) /
                    1024, 1);
        VM.sysWrite("\t");
        // Compiled bytecode Kbytes
        if (i != JNI_COMPILER) {
          VM.sysWrite((double) totalBCLength[i] / 1024, 1);
        } else {
          VM.sysWrite("NA");
        }
        VM.sysWrite("\n");
      }
    }
    if (explain) {
      // Generate an explanation of the metrics reported
      VM.sysWrite("\t\t\tExplanation of Metrics\n");
      VM.sysWrite("#Meths:\t\tTotal number of methods compiled by the compiler\n");
      VM.sysWrite("Time:\t\tTotal compilation time in milliseconds\n");
      VM.sysWrite("bcb/ms:\t\tNumber of bytecode bytes complied per millisecond\n");
      VM.sysWrite("mcb/bcb:\tRatio of machine code bytes to bytecode bytes\n");
      VM.sysWrite("MCKB:\t\tTotal number of machine code bytes generated in kilobytes\n");
      VM.sysWrite("BCKB:\t\tTotal number of bytecode bytes compiled in kilobytes\n");
    }

    BaselineCompiler.generateBaselineCompilerSubsystemReport(explain);

    if (VM.BuildForAdaptiveSystem) {
      // Get the opt's report
      RVMType theType = TypeReference.OptimizationPlanner.peekType();
      if (theType != null && theType.asClass().isInitialized()) {
        OptimizationPlanner.generateOptimizingCompilerSubsystemReport(explain);
      } else {
        VM.sysWrite("\n\tNot generating Optimizing Compiler SubSystem Report because \n");
        VM.sysWrite("\tthe opt compiler was never invoked.\n\n");
      }
    }
  }

  /**
   * Return the current estimate of basline-compiler rate, in bcb/msec
   */
  public static double getBaselineRate() {
    return Math.exp(totalLogOfRates[BASELINE_COMPILER] / totalLogValueMethods[BASELINE_COMPILER]);
  }

  /**
   * This method will compile the passed method using the baseline compiler.
   * @param method the method to compile
   */
  public static CompiledMethod baselineCompile(NormalMethod method) {
    Callbacks.notifyMethodCompile(method, CompiledMethod.BASELINE);
    long start = 0;
    CompiledMethod cm = null;
    try {
      if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
        start = Time.nanoTime();
      }

      cm = BaselineCompiler.compile(method);
    } finally {
      if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
        long end = Time.nanoTime();
        if (cm != null) {
          double compileTime = Time.nanosToMillis(end - start);
          cm.setCompilationTime(compileTime);
          record(BASELINE_COMPILER, method, cm);
        }
      }
    }


    return cm;
  }

  /**
   * Process command line argument destined for the opt compiler
   */
  public static void processOptCommandLineArg(String prefix, String arg) {
    if (VM.BuildForAdaptiveSystem) {
      if (compilerEnabled) {
        if (((OptOptions) options).processAsOption(prefix, arg)) {
          // update the optimization plan to reflect the new command line argument
          optimizationPlan = OptimizationPlanner.createOptimizationPlan((OptOptions) options);
        } else {
          VM.sysWrite("Unrecognized opt compiler argument \"" + arg + "\"");
          VM.sysExit(VM.EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
        }
      } else {
        String[] tmp = new String[earlyOptArgs.length + 2];
        for (int i = 0; i < earlyOptArgs.length; i++) {
          tmp[i] = earlyOptArgs[i];
        }
        earlyOptArgs = tmp;
        earlyOptArgs[earlyOptArgs.length - 2] = prefix;
        earlyOptArgs[earlyOptArgs.length - 1] = arg;
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(NOT_REACHED);
    }
  }

  /**
   * attempt to compile the passed method with the Compiler.
   * Don't handle OptimizingCompilerExceptions
   *   (leave it up to caller to decide what to do)
   * Precondition: compilationInProgress "lock" has been acquired
   * @param method the method to compile
   * @param plan the plan to use for compiling the method
   */
  private static CompiledMethod optCompile(NormalMethod method, CompilationPlan plan)
      throws OptimizingCompilerException {
    if (VM.BuildForOptCompiler) {
      if (VM.VerifyAssertions) {
        VM._assert(compilationInProgress, "Failed to acquire compilationInProgress \"lock\"");
      }

      Callbacks.notifyMethodCompile(method, CompiledMethod.JNI);
      long start = 0;
      CompiledMethod cm = null;
      try {
        if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
          start = Time.nanoTime();
        }
        cm = OptimizingCompiler.compile(plan);
      } finally {
        if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
          long end = Time.nanoTime();
          if (cm != null) {
            double compileTime = Time.nanosToMillis(end - start);
            cm.setCompilationTime(compileTime);
            record(OPT_COMPILER, method, cm);
          }
        }
      }

      return cm;
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return null;
    }
  }

  // These methods are safe to invoke from RuntimeCompiler.compile

  /**
   * This method tries to compile the passed method with the Compiler,
   * using the default compilation plan.  If
   * this fails we will use the quicker compiler (baseline for now)
   * The following is carefully crafted to avoid (infinte) recursive opt
   * compilation for all combinations of bootimages & lazy/eager compilation.
   * Be absolutely sure you know what you're doing before changing it !!!
   * @param method the method to compile
   */
  public static synchronized CompiledMethod optCompileWithFallBack(NormalMethod method) {
    if (VM.BuildForOptCompiler) {
      if (compilationInProgress) {
        return fallback(method);
      } else {
        try {
          compilationInProgress = true;
          CompilationPlan plan =
              new CompilationPlan(method,
                                      (OptimizationPlanElement[]) optimizationPlan,
                                      null,
                                      (OptOptions) options);
          return optCompileWithFallBackInternal(method, plan);
        } finally {
          compilationInProgress = false;
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return null;
    }
  }

  /**
   * This method tries to compile the passed method with the Compiler
   * with the passed compilation plan.  If
   * this fails we will use the quicker compiler (baseline for now)
   * The following is carefully crafted to avoid (infinte) recursive opt
   * compilation for all combinations of bootimages & lazy/eager compilation.
   * Be absolutely sure you know what you're doing before changing it !!!
   * @param method the method to compile
   * @param plan the compilation plan to use for the compile
   */
  public static synchronized CompiledMethod optCompileWithFallBack(NormalMethod method,
                                                                      CompilationPlan plan) {
    if (VM.BuildForOptCompiler) {
      if (compilationInProgress) {
        return fallback(method);
      } else {
        try {
          compilationInProgress = true;
          return optCompileWithFallBackInternal(method, plan);
        } finally {
          compilationInProgress = false;
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return null;
    }
  }

  /**
   * This real method that performs the opt compilation.
   * @param method the method to compile
   * @param plan the compilation plan to use
   */
  private static CompiledMethod optCompileWithFallBackInternal(NormalMethod method, CompilationPlan plan) {
    if (VM.BuildForOptCompiler) {
      if (method.hasNoOptCompileAnnotation()) return fallback(method);
      try {
        return optCompile(method, plan);
      } catch (OptimizingCompilerException e) {
        String msg =
            "RuntimeCompiler: can't optimize \"" +
            method +
            "\" (error was: " +
            e +
            "): reverting to baseline compiler\n";
        if (e.isFatal && VM.ErrorsFatal) {
          e.printStackTrace();
          VM.sysFail(msg);
        } else {
          boolean printMsg = true;
          if (e instanceof MagicNotImplementedException) {
            printMsg = !((MagicNotImplementedException) e).isExpected;
          }
          if (printMsg) VM.sysWrite(msg);
        }
        return fallback(method);
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return null;
    }
  }

  /* recompile the specialized method with Compiler. */
  public static CompiledMethod recompileWithOptOnStackSpecialization(CompilationPlan plan) {
    if (VM.BuildForOptCompiler) {
      if (VM.VerifyAssertions) { VM._assert(plan.method.isForOsrSpecialization());}
      if (compilationInProgress) {
        return null;
      }

      try {
        compilationInProgress = true;

        // the compiler will check if isForOsrSpecialization of the method
        CompiledMethod cm = optCompile(plan.method, plan);

        // we donot replace the compiledMethod of original method,
        // because it is temporary method
        return cm;
      } catch (OptimizingCompilerException e) {
        e.printStackTrace();
        String msg =
            "Optimizing compiler " +
            "(via recompileWithOptOnStackSpecialization): " +
            "can't optimize \"" +
            plan
                .method +
                        "\" (error was: " +
                        e +
                        ")\n";

        if (e.isFatal && VM.ErrorsFatal) {
          VM.sysFail(msg);
        } else {
          VM.sysWrite(msg);
        }
        return null;
      } finally {
        compilationInProgress = false;
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return null;
    }
  }

  /**
   * This method tries to compile the passed method with the Compiler.
   * It will install the new compiled method in the VM, if sucessful.
   * NOTE: the recompile method should never be invoked via
   *      RuntimeCompiler.compile;
   *   it does not have sufficient guards against recursive recompilation.
   * @param plan the compilation plan to use
   * @return the CMID of the new method if successful, -1 if the
   *    recompilation failed.
   *
   **/
  public static synchronized int recompileWithOpt(CompilationPlan plan) {
    if (VM.BuildForOptCompiler) {
      if (compilationInProgress) {
        return -1;
      } else {
        try {
          compilationInProgress = true;
          CompiledMethod cm = optCompile(plan.method, plan);
          try {
            plan.method.replaceCompiledMethod(cm);
          } catch (Throwable e) {
            String msg = "Failure in RVMMethod.replaceCompiledMethod (via recompileWithOpt): while replacing \"" + plan
                .method + "\" (error was: " + e + ")\n";
            if (VM.ErrorsFatal) {
              e.printStackTrace();
              VM.sysFail(msg);
            } else {
              VM.sysWrite(msg);
            }
            return -1;
          }
          return cm.getId();
        } catch (OptimizingCompilerException e) {
          String msg = "Optimizing compiler (via recompileWithOpt): can't optimize \"" + plan
              .method + "\" (error was: " + e + ")\n";
          if (e.isFatal && VM.ErrorsFatal) {
            e.printStackTrace();
            VM.sysFail(msg);
          } else {
            // VM.sysWrite(msg);
          }
          return -1;
        } finally {
          compilationInProgress = false;
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return -1;
    }
  }

  /**
   * A wrapper method for those callers who don't want to make
   * optimization plans
   * @param method the method to recompile
   */
  public static int recompileWithOpt(NormalMethod method) {
    if (VM.BuildForOptCompiler) {
      CompilationPlan plan =
          new CompilationPlan(method,
                                  (OptimizationPlanElement[]) optimizationPlan,
                                  null,
                                  (OptOptions) options);
      return recompileWithOpt(plan);
    } else {
      if (VM.VerifyAssertions) VM._assert(false);
      return -1;
    }
  }

  /**
   * This method uses the default compiler (baseline) to compile a method
   * It is typically called when a more aggressive compilation fails.
   * This method is safe to invoke from RuntimeCompiler.compile
   */
  protected static CompiledMethod fallback(NormalMethod method) {
    // call the inherited method "baselineCompile"
    return baselineCompile(method);
  }

  public static void boot() {
    if (VM.MeasureCompilation) {
      Callbacks.addExitMonitor(new RuntimeCompiler());
    }
    if (VM.BuildForAdaptiveSystem) {
      optimizationPlan = OptimizationPlanner.createOptimizationPlan((OptOptions) options);
      if (VM.MeasureCompilationPhases) {
        OptimizationPlanner.initializeMeasureCompilation();
      }

      OptimizingCompiler.init((OptOptions) options);

      PreCompile.init();
      // when we reach here the OPT compiler is enabled.
      compilerEnabled = true;

      for (int i = 0; i < earlyOptArgs.length; i += 2) {
        processOptCommandLineArg(earlyOptArgs[i], earlyOptArgs[i + 1]);
      }
    }
  }

  public static void processCommandLineArg(String prefix, String arg) {
    if (VM.BuildForAdaptiveSystem) {
      if (Controller.options != null && Controller.options.optIRC()) {
        processOptCommandLineArg(prefix, arg);
      } else {
        BaselineCompiler.processCommandLineArg(prefix, arg);
      }
    } else {
      BaselineCompiler.processCommandLineArg(prefix, arg);
    }
  }

  /**
   * Compile a Java method when it is first invoked.
   * @param method the method to compile
   * @return its compiled method.
   */
  public static CompiledMethod compile(NormalMethod method) {
    if (VM.BuildForAdaptiveSystem) {
      CompiledMethod cm;
      if (!Controller.enabled) {
        // System still early in boot process; compile with baseline compiler
        cm = baselineCompile(method);
        ControllerMemory.incrementNumBase();
      } else {
        if (!preloadChecked) {
          preloadChecked = true;                  // prevent subsequent calls
          // N.B. This will use irc options
          if (BaselineCompiler.options.PRELOAD_CLASS != null) {
            compilationInProgress = true;         // use baseline during preload
            // Other than when boot options are requested (processed during preloadSpecialClass
            // It is hard to communicate options for these special compilations. Use the
            // default options and at least pick up the verbose if requested for base/irc
            OptOptions tmpoptions = ((OptOptions) options).dup();
            tmpoptions.PRELOAD_CLASS = BaselineCompiler.options.PRELOAD_CLASS;
            tmpoptions.PRELOAD_AS_BOOT = BaselineCompiler.options.PRELOAD_AS_BOOT;
            if (BaselineCompiler.options.PRINT_METHOD) {
              tmpoptions.PRINT_METHOD = true;
            } else {
              tmpoptions = (OptOptions) options;
            }
            OptimizingCompiler.preloadSpecialClass(tmpoptions);
            compilationInProgress = false;
          }
        }
        if (Controller.options.optIRC()) {
          if (// will only run once: don't bother optimizing
              method.isClassInitializer() ||
              // exception in progress. can't use opt compiler:
              // it uses exceptions and runtime doesn't support
              // multiple pending (undelivered) exceptions [--DL]
              RVMThread.getCurrentThread().getExceptionRegisters().inuse) {
            // compile with baseline compiler
            cm = baselineCompile(method);
            ControllerMemory.incrementNumBase();
          } else { // compile with opt compiler
            AOSInstrumentationPlan instrumentationPlan =
                new AOSInstrumentationPlan(Controller.options, method);
            CompilationPlan compPlan =
                new CompilationPlan(method,
                                        (OptimizationPlanElement[]) optimizationPlan,
                                        instrumentationPlan,
                                        (OptOptions) options);
            cm = optCompileWithFallBack(method, compPlan);
          }
        } else {
          if ((Controller.options
              .BACKGROUND_RECOMPILATION &&
                                        (!Controller.options.ENABLE_REPLAY_COMPILE) &&
                                        (!Controller.options.ENABLE_PRECOMPILE))) {
            // must be an inital compilation: compile with baseline compiler
            // or if recompilation with OSR.
            cm = baselineCompile(method);
            ControllerMemory.incrementNumBase();
          } else {
            if (CompilerAdviceAttribute.hasAdvice()) {
              CompilerAdviceAttribute attr = CompilerAdviceAttribute.getCompilerAdviceInfo(method);
              if (attr.getCompiler() != CompiledMethod.OPT) {
                cm = fallback(method);
                AOSLogging.logger.recordCompileTime(cm, 0.0);
                return cm;
              }
              int newCMID = -2;
              CompilationPlan compPlan;
              if (Controller.options.counters()) {
                // for invocation counter, we only use one optimization level
                compPlan = InvocationCounts.createCompilationPlan(method);
              } else {
                // for now there is not two options for sampling, so
                // we don't have to use: if (Controller.options.sampling())
                compPlan = Controller.recompilationStrategy.createCompilationPlan(method, attr.getOptLevel(), null);
              }
              AOSLogging.logger.recompilationStarted(compPlan);
              newCMID = recompileWithOpt(compPlan);
              cm = newCMID == -1 ? null : CompiledMethods.getCompiledMethod(newCMID);
              if (newCMID == -1) {
                AOSLogging.logger.recompilationAborted(compPlan);
              } else if (newCMID > 0) {
                AOSLogging.logger.recompilationCompleted(compPlan);
              }
              if (cm == null) { // if recompilation is aborted
                cm = baselineCompile(method);
                ControllerMemory.incrementNumBase();
              }
            } else {
              // check to see if there is a compilation plan for this method.
              ControllerPlan plan = ControllerMemory.findLatestPlan(method);
              if (plan == null || plan.getStatus() != ControllerPlan.IN_PROGRESS) {
                // initial compilation or some other funny state: compile with baseline compiler
                cm = baselineCompile(method);
                ControllerMemory.incrementNumBase();
              } else {
                cm = plan.doRecompile();
                if (cm == null) {
                  // opt compilation aborted for some reason.
                  cm = baselineCompile(method);
                }
              }
            }
          }
        }
      }
      if ((Controller.options.ENABLE_ADVICE_GENERATION) &&
          (cm.getCompilerType() == CompiledMethod.BASELINE) &&
          Controller
              .enabled) {
        AOSGenerator.baseCompilationCompleted(cm);
      }
      AOSLogging.logger.recordCompileTime(cm, 0.0);
      return cm;
    } else {
      return baselineCompile(method);
    }
  }

  /**
   * Compile the stub for a native method when it is first invoked.
   * @param method the method to compile
   * @return its compiled method.
   */
  public static CompiledMethod compile(NativeMethod method) {
    Callbacks.notifyMethodCompile(method, CompiledMethod.JNI);
    long start = 0;
    CompiledMethod cm = null;
    try {
      if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
        start = Time.nanoTime();
      }

      cm = JNICompiler.compile(method);
      if (VM.verboseJNI) {
        VM.sysWriteln("[Dynamic-linking native method " +
                      method.getDeclaringClass() +
                      "." +
                      method.getName() +
                      " " +
                      method.getDescriptor());
      }
    } finally {
      if (VM.MeasureCompilation || VM.BuildForAdaptiveSystem) {
        long end = Time.nanoTime();
        if (cm != null) {
          double compileTime = Time.nanosToMillis(end - start);
          cm.setCompilationTime(compileTime);
          record(JNI_COMPILER, method, cm);
        }
      }
    }

    return cm;
  }

  /**
   * returns the string version of compiler number, using the naming scheme
   * in this file
   * @param compiler the compiler of interest
   * @return the string version of compiler number
   */
  public static String getCompilerName(byte compiler) {
    return name[compiler];
  }

}
