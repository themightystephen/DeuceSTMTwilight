package org.deuce.transaction.tl2twilight.pool;

// what is this interface meant to represent exactly...; it's obviously a factory that produces objects of type T.
public interface ResourceFactory<T>{
	T newInstance();
}
