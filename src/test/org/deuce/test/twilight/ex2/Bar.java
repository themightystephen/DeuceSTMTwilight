package org.deuce.test.twilight.ex2;

import java.io.IOException;
import java.util.Random;


/**
 * NOTE: This project (TestDeuceTL2Twilight) assumes the correctness of the Twilight-TL2 implementation
 * in the referenced project.
 *
 * @author stephen
 */

public class Bar {
	private int x = 0;

	@TwilightAtomic(name = "foo")
	public int foo(Object s) throws IOException {
		// every now and again our original method would throw an IOException; the rest of the time it behaves fine.
		Random r = new Random();
		if(r.nextFloat() < 0.0) { // TODO: change back to 0.05 at some point
			throw new IOException();
		}

		x++;
		return x;
	}

	// Must beware of overloaded methods (methods are distinguished by name and parameter lists -- can have same name provided different number and/or type of parameters)
	// TODO: How do I associate the methods for the consistent and inconsistent cases with the main atomic method??
	// 1. I could have some clever annotation thing going on where the values in the @TwilightConsistent and @TwilightInconsistent annotations indicate which method they are associated with...but need to ensure it specifies more than just name!
	// 2. Another option is to require people to have a class that implements a special Twilight interface that has three methods: atomic(), twilightConsistent(), twilightInconsistent().
	// with 1, provided we require the user to obviously put their twilight methods in the same class as the atomic method is in, all I need to be able to do is to translate the proper bytecode type descriptor from the more Java-oriented one given by the user.
	// it's not an error if one or both of the twilight methods are missing, because we can just infer a default one.
	// but maybe could provide simple warnings in case the user forgot or slightly mistyped
	// however, it probably is an error if there is no atomic method corresponding to the signatures given in @TwilightConsistent or @TwilightInconsistent annotations

	// IDEA: let the user specify as a value in the @Atomic annotation, their own user-defined name that they can be used by the @TwilightConsistent and @TwilightInconsistent annotations to identify which @Atomic method they're associated with (instead of them having to use the method's signature, minus the return type).

	@TwilightConsistent(name = "foo")
	public void fooTwilightConsistent() {

	}

	@TwilightInconsistent(name = "foo")
	public void fooTwilightInconsistent() {

	}
}
