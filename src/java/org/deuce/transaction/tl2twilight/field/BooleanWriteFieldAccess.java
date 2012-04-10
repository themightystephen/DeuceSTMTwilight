package org.deuce.transaction.tl2twilight.field;

import org.deuce.reflection.UnsafeHolder;
import org.deuce.transform.commons.Exclude;

@Exclude
public class BooleanWriteFieldAccess extends WriteFieldAccess {

	private boolean value;

	public void set(boolean value, Object reference, long field) {
		super.init(reference, field);
		this.value = value;
	}

	@Override
	public void put() {
		UnsafeHolder.getUnsafe().putBoolean(reference, field, getValue());
		clear();
	}

	public boolean getValue() {
		return value;
	}
}
