package org.deuce.transaction.tl2twilight.pool;

import org.deuce.transform.commons.Exclude;

// what is this interface meant to represent exactly...; it's obviously a factory that produces objects of type T.
@Exclude
public interface ResourceFactory<T>{
	T newInstance();
}
