/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Mar 31, 2008 */

package clojure.lang;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;

@ExportLibrary(InteropLibrary.class)
public class Ratio extends Number implements Comparable, TruffleObject{

private static final long serialVersionUID = -576272795628662988L;

final public BigInteger numerator;
final public BigInteger denominator;

public Ratio(BigInteger numerator, BigInteger denominator){
	this.numerator = numerator;
	this.denominator = denominator;
}

public boolean equals(Object arg0){
	return arg0 != null
	       && arg0 instanceof Ratio
	       && ((Ratio) arg0).numerator.equals(numerator)
	       && ((Ratio) arg0).denominator.equals(denominator);
}

public int hashCode(){
	return numerator.hashCode() ^ denominator.hashCode();
}

public String toString(){
	return numerator.toString() + "/" + denominator.toString();
}

public int intValue(){
	return (int) doubleValue();
}

public long longValue(){
	return bigIntegerValue().longValue();
}

public float floatValue(){
	return (float)doubleValue();
}

public double doubleValue(){
	return decimalValue(MathContext.DECIMAL64).doubleValue();
}

public BigDecimal decimalValue(){
	return decimalValue(MathContext.UNLIMITED);
}

public BigDecimal decimalValue(MathContext mc){
	BigDecimal numerator = new BigDecimal(this.numerator);
	BigDecimal denominator = new BigDecimal(this.denominator);

	return numerator.divide(denominator, mc);
}

public BigInteger bigIntegerValue(){
	return numerator.divide(denominator);
}

@ExportMessage
boolean isNumber() {
	return true;
}

@ExportMessage
boolean fitsInByte() {
	return fitsIntegralIn(BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE));
}

@ExportMessage
boolean fitsInShort() {
	return fitsIntegralIn(BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE));
}

@ExportMessage
boolean fitsInInt() {
	return fitsIntegralIn(BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE));
}

@ExportMessage
boolean fitsInLong() {
	return fitsIntegralIn(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
}

@ExportMessage
boolean fitsInBigInteger() {
	return numerator.mod(denominator).equals(BigInteger.ZERO);
}

@ExportMessage
boolean fitsInFloat() {
	return false;
}

@ExportMessage
boolean fitsInDouble() {
	return false;
}

@ExportMessage
byte asByte() throws UnsupportedMessageException {
	if (!fitsInByte()) throw UnsupportedMessageException.create();
	return bigIntegerValue().byteValue();
}

@ExportMessage
short asShort() throws UnsupportedMessageException {
	if (!fitsInShort()) throw UnsupportedMessageException.create();
	return bigIntegerValue().shortValue();
}

@ExportMessage
int asInt() throws UnsupportedMessageException {
	if (!fitsInInt()) throw UnsupportedMessageException.create();
	return bigIntegerValue().intValue();
}

@ExportMessage
long asLong() throws UnsupportedMessageException {
	if (!fitsInLong()) throw UnsupportedMessageException.create();
	return bigIntegerValue().longValue();
}

@ExportMessage
BigInteger asBigInteger() throws UnsupportedMessageException {
	if (!fitsInBigInteger()) throw UnsupportedMessageException.create();
	return bigIntegerValue();
}

@ExportMessage
float asFloat() throws UnsupportedMessageException {
	throw UnsupportedMessageException.create();
}

@ExportMessage
double asDouble() throws UnsupportedMessageException {
	throw UnsupportedMessageException.create();
}

@ExportMessage
Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
	return toString();
}

private boolean fitsIntegralIn(BigInteger min, BigInteger max) {
	if (!fitsInBigInteger()) {
		return false;
	}
	BigInteger value = bigIntegerValue();
	return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
}

public int compareTo(Object o){
	Number other = (Number)o;
	return Numbers.compare(this, other);
}
}
