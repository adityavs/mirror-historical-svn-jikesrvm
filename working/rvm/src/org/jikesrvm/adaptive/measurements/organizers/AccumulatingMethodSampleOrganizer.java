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
package org.jikesrvm.adaptive.measurements.organizers;

import org.jikesrvm.VM;
import org.jikesrvm.adaptive.controller.Controller;
import org.jikesrvm.adaptive.database.methodsamples.MethodCountData;
import org.jikesrvm.adaptive.measurements.RuntimeMeasurements;
import org.jikesrvm.adaptive.measurements.listeners.MethodListener;
import org.jikesrvm.adaptive.util.AOSLogging;
import org.jikesrvm.scheduler.greenthreads.GreenScheduler;

/**
 * An organizer for method listener information that
 * simply accumulates the samples into a private
 * MethodCountData instance.
 *
 * This organizer is used to simply gather aggregate sample data and
 * report it.
 */
public final class AccumulatingMethodSampleOrganizer extends Organizer {

  private MethodCountData data;

  public AccumulatingMethodSampleOrganizer() {
    makeDaemon(true);
  }

  /**
   * Initialization: set up data structures and sampling objects.
   */
  @Override
  public void initialize() {
    data = new MethodCountData();
    int numSamples = Controller.options.METHOD_SAMPLE_SIZE * GreenScheduler.numProcessors;
    if (Controller.options.mlCBS()) {
      numSamples *= VM.CBSMethodSamplesPerTick;
    }
    MethodListener methodListener = new MethodListener(numSamples);
    listener = methodListener;
    listener.setOrganizer(this);
    if (Controller.options.mlTimer()) {
      RuntimeMeasurements.installTimerMethodListener(methodListener);
    } else if (Controller.options.mlCBS()) {
      RuntimeMeasurements.installCBSMethodListener(methodListener);
    } else {
      if (VM.VerifyAssertions) VM._assert(false, "Unexpected value of method_listener_trigger");
    }
  }

  /**
   * Method that is called when the sampling threshold is reached
   */
  void thresholdReached() {
    AOSLogging.organizerThresholdReached();
    int numSamples = ((MethodListener) listener).getNumSamples();
    int[] samples = ((MethodListener) listener).getSamples();
    data.update(samples, numSamples);
  }

  public void report() {
    VM.sysWrite("\nMethod sampler report");
    if (data != null) data.report();
  }
}
