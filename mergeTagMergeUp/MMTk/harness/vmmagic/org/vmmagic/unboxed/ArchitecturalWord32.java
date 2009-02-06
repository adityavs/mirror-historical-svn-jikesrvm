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
package org.vmmagic.unboxed;

public class ArchitecturalWord32 extends ArchitecturalWord {

  private final int value;

  ArchitecturalWord32(int value) {
    assert getModel() == Architecture.BITS32;
    this.value = value;
  }

  @Override
  public boolean isZero() {
    return value == 0;
  }

  @Override
  public boolean isMax() {
    return value == 0xFFFFFFFF;
  }

  @Override
  public int toInt() {
    return value;
  }

  @Override
  public long toLongSignExtend() {
    return (long)value;
  }

  @Override
  public long toLongZeroExtend() {
    return (long)value & 0xFFFFFFFFL;
  }

  @Override
  boolean EQ(ArchitecturalWord word) {
    return value == word.toInt();
  }

  @Override
  boolean LT(ArchitecturalWord word) {
    if (value >= 0 && word.toInt() >= 0) return value < word.toInt();
    if (value < 0 && word.toInt() < 0) return value < word.toInt();
    if (value < 0) return false;
    return true;
  }

  @Override
  ArchitecturalWord minus(long offset) {
    return fromLong(value-(int)offset);
  }

  @Override
  ArchitecturalWord plus(long offset) {
    return fromLong(value + (int)offset);
  }

  @Override
  ArchitecturalWord and(ArchitecturalWord w) {
    return fromLong(value & w.toInt());
  }

  @Override
  ArchitecturalWord lsh(int amt) {
    return fromLong(value << amt);
  }

  @Override
  ArchitecturalWord not() {
    return fromLong(~value);
  }

  @Override
  ArchitecturalWord or(ArchitecturalWord w) {
    return fromLong(value | w.toInt());
  }

  @Override
  ArchitecturalWord rsha(int amt) {
    return fromLong(value >> amt);
  }

  @Override
  ArchitecturalWord rshl(int amt) {
    return fromLong(value >>> amt);
  }

  @Override
  ArchitecturalWord xor(ArchitecturalWord w) {
    return fromLong(value ^ w.toInt());
  }

  @Override
  ArchitecturalWord diff(ArchitecturalWord w) {
    return fromLong(value - w.toInt());
  }

  @Override
  boolean sLT(ArchitecturalWord word) {
    return value < word.toInt();
  }

  /**
   * Create a string representation of the given int value as an address.
   */
  public String toString() {
    char[] chars = new char[10];
    int v = value;
    chars[0] = '0';
    chars[1] = 'x';
    for(int x = 9; x > 1; x--) {
      int thisValue = v & 0x0F;
      if (thisValue > 9) {
        chars[x] = (char)('A' + thisValue - 10);
      } else {
        chars[x] = (char)('0' + thisValue);
      }
      v >>>= 4;
    }
    return new String(chars);
  }

}