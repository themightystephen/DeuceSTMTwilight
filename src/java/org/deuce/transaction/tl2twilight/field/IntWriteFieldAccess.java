package org.deuce.transaction.tl2twilight.field;

import org.deuce.reflection.UnsafeHolder;
import org.deuce.transform.commons.Exclude;

@Exclude
public class IntWriteFieldAccess extends WriteFieldAccess {

	private int value;

	public void set(int value, Object reference, long field) {
		super.init(reference, field);
		this.value = value;
	}

	@Override
	public void put() {
		UnsafeHolder.getUnsafe().putInt(reference, field, value);
		clear();
	}

	public int getValue() {
		return value;
	}

}
