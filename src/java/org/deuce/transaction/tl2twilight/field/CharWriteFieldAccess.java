package org.deuce.transaction.tl2twilight.field;

import org.deuce.reflection.UnsafeHolder;
import org.deuce.transform.commons.Exclude;

@Exclude
public class CharWriteFieldAccess extends WriteFieldAccess {

	private char value;

	public void set(char value, Object reference, long field) {
		super.init(reference, field);
		this.value = value;
	}

	@Override
	public void put() {
		UnsafeHolder.getUnsafe().putChar(reference, field, value);
		clear();
	}

	public char getValue() {
		return value;
	}

}
