package org.deuce.transaction.tl2twilight.pool;

import org.deuce.transaction.tl2.field.ReadFieldAccess;
import org.deuce.transform.commons.Exclude;

/**
 * Represents the transaction read set. [this appears to be the most recent version cf. the ReadSet class -- says it's from v1.0, plus it uses generics]
 * And acts as a recycle pool of the {@link ReadFieldAccess}.
 *
 * In the tl2.Context class, there is a Pool per autoboxed primitive and Object. hmmm, it appears this class is used for the write set rather than read set. Maybe it was a typo.
 *
 * @author Guy Korland
 * @since 1.0
 */
@Exclude
final public class Pool<T>{

	private static final int DEFAULT_CAPACITY = 1024;
	private T[] pool = (T[]) new Object[DEFAULT_CAPACITY];
	private int nextAvaliable = 0;
	final private ResourceFactory<T> factory;

	public Pool(ResourceFactory<T> factory){
		this.factory = factory;
		fillArray( 0);
	}

	public void clear(){
		nextAvaliable = 0;
	}

	private void fillArray( int offset){
		for( int i=offset ; i < DEFAULT_CAPACITY + offset ; ++i){
			pool[i] = factory.newInstance();
		}
	}

	public T getNext(){
		if( nextAvaliable >= pool.length){
			int orignLength = pool.length;
			T[] newPool = (T[]) new Object[ orignLength + DEFAULT_CAPACITY];
			System.arraycopy(pool, 0, newPool, 0, pool.length);
			pool = newPool;
			fillArray( orignLength);
		}
		return pool[ nextAvaliable++];
	}
}
