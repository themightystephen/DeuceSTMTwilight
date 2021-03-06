package org.deuce.transaction.tl2twilight.field;

import org.deuce.reflection.UnsafeHolder;
import org.deuce.transform.commons.Exclude;

@Exclude
public class FloatWriteFieldAccess extends WriteFieldAccess {

	private float value;

	public void set(float value, Object reference, long field) {
		super.init(reference, field);
		this.value = value;
	}

	@Override
	public void put() {
		UnsafeHolder.getUnsafe().putFloat(reference, field, value);
		clear();
	}

	public float getValue() {
		return value;
	}

}
