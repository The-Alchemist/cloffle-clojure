/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* chouser Jun 23, 2010 */

package clojure.lang;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.math.BigInteger;
import java.math.BigDecimal;

@ExportLibrary(InteropLibrary.class)
public final class BigInt extends Number implements IHashEq, TruffleObject{

private static final long serialVersionUID = 5097771279236135022L;

final public long lpart;
final public BigInteger bipart;

final public static BigInt ZERO = new BigInt(0,null);
final public static BigInt ONE = new BigInt(1,null);


//must follow Long
public int hashCode(){
	if(bipart == null)
		return (int) (this.lpart ^ (this.lpart >>> 32));
	return bipart.hashCode();
}

public int hasheq(){
	if(bipart == null)
		return Murmur3.hashLong(lpart);
	return bipart.hashCode();

}

public boolean equals(Object obj){
	if(this == obj)
		return true;
	if(obj instanceof BigInt)
		{
		BigInt o = (BigInt) obj;
		if(bipart == null)
			return o.bipart == null && this.lpart == o.lpart;
		return o.bipart != null && this.bipart.equals(o.bipart);
		}
	return false;
}

private BigInt(long lpart, BigInteger bipart){
	this.lpart = lpart;
	this.bipart = bipart;
}

public static BigInt fromBigInteger(BigInteger val){
	if(val.bitLength() < 64)
		return new BigInt(val.longValue(), null);
	else
		return new BigInt(0, val);
}

public static BigInt fromLong(long val){
	return new BigInt(val, null);
}

public BigInteger toBigInteger(){
	if(bipart == null)
		return BigInteger.valueOf(lpart);
	else
		return bipart;
}

public BigDecimal toBigDecimal(){
	if(bipart == null)
		return BigDecimal.valueOf(lpart);
	else
		return new BigDecimal(bipart);
}

///// java.lang.Number:

public int intValue(){
	if(bipart == null)
		return (int) lpart;
	else
		return bipart.intValue();
}

public long longValue(){
	if(bipart == null)
		return lpart;
	else
		return bipart.longValue();
}

public float floatValue(){
	if(bipart == null)
			return lpart;
	else
		return bipart.floatValue();
}

public double doubleValue(){
	if(bipart == null)
		return lpart;
	else
		return bipart.doubleValue();
}

public byte byteValue(){
	if(bipart == null)
		return (byte) lpart;
	else
		return bipart.byteValue();
}

public short shortValue(){
	if(bipart == null)
		return (short) lpart;
	else
		return bipart.shortValue();
}

public static BigInt valueOf(long val){
	return new BigInt(val, null);
}

public String toString(){
	if(bipart == null)
		return String.valueOf(lpart);
	return bipart.toString();
}

public int bitLength(){
	return toBigInteger().bitLength();
}

@ExportMessage
boolean isNumber() {
	return true;
}

@ExportMessage
boolean fitsInByte() {
	return fitsIn(BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE));
}

@ExportMessage
boolean fitsInShort() {
	return fitsIn(BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE));
}

@ExportMessage
boolean fitsInInt() {
	return fitsIn(BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE));
}

@ExportMessage
boolean fitsInLong() {
	return fitsIn(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
}

@ExportMessage
boolean fitsInBigInteger() {
	return true;
}

@ExportMessage
boolean fitsInFloat() {
	float f = floatValue();
	return Float.isFinite(f) && fromBigInteger(BigDecimal.valueOf(f).toBigInteger()).equals(this);
}

@ExportMessage
boolean fitsInDouble() {
	double d = doubleValue();
	return Double.isFinite(d) && fromBigInteger(BigDecimal.valueOf(d).toBigInteger()).equals(this);
}

@ExportMessage
byte asByte() throws UnsupportedMessageException {
	if (!fitsInByte()) throw UnsupportedMessageException.create();
	return byteValue();
}

@ExportMessage
short asShort() throws UnsupportedMessageException {
	if (!fitsInShort()) throw UnsupportedMessageException.create();
	return shortValue();
}

@ExportMessage
int asInt() throws UnsupportedMessageException {
	if (!fitsInInt()) throw UnsupportedMessageException.create();
	return intValue();
}

@ExportMessage
long asLong() throws UnsupportedMessageException {
	if (!fitsInLong()) throw UnsupportedMessageException.create();
	return longValue();
}

@ExportMessage
BigInteger asBigInteger() {
	return toBigInteger();
}

@ExportMessage
float asFloat() throws UnsupportedMessageException {
	if (!fitsInFloat()) throw UnsupportedMessageException.create();
	return floatValue();
}

@ExportMessage
double asDouble() throws UnsupportedMessageException {
	if (!fitsInDouble()) throw UnsupportedMessageException.create();
	return doubleValue();
}

@ExportMessage
Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
	return toString();
}

private boolean fitsIn(BigInteger min, BigInteger max) {
	BigInteger val = toBigInteger();
	return val.compareTo(min) >= 0 && val.compareTo(max) <= 0;
}

public BigInt add(BigInt y) {
    if ((bipart == null) && (y.bipart == null)) {
        long ret = lpart + y.lpart;
        if ((ret ^ lpart) >= 0 || (ret ^ y.lpart) >= 0)
            return BigInt.valueOf(ret);
    }
    return BigInt.fromBigInteger(this.toBigInteger().add(y.toBigInteger()));
}

public BigInt multiply(BigInt y) {
    if ((bipart == null) && (y.bipart == null)) {
        long ret = lpart * y.lpart;
            if (y.lpart == 0 ||
                (ret / y.lpart == lpart && lpart != Long.MIN_VALUE))
                return BigInt.valueOf(ret);
        }
    return BigInt.fromBigInteger(this.toBigInteger().multiply(y.toBigInteger()));
}

public BigInt quotient(BigInt y) {
    if ((bipart == null) && (y.bipart == null)) {
        if (lpart == Long.MIN_VALUE && y.lpart == -1)
            return BigInt.fromBigInteger(this.toBigInteger().negate());
        return BigInt.valueOf(lpart / y.lpart);
    }
    return BigInt.fromBigInteger(this.toBigInteger().divide(y.toBigInteger()));
}

public BigInt remainder(BigInt y) {
    if ((bipart == null) && (y.bipart == null)) {
        return BigInt.valueOf(lpart % y.lpart);
    }
    return BigInt.fromBigInteger(this.toBigInteger().remainder(y.toBigInteger()));
}

public boolean lt(BigInt y) {
    if ((bipart == null) && (y.bipart == null)) {
        return lpart < y.lpart;
    }
    return this.toBigInteger().compareTo(y.toBigInteger()) < 0;
}

}
