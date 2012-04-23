package org.deuce.test.twilight.ex3;

import org.deuce.test.twilight.ex2.TwilightAtomic;
import org.deuce.test.twilight.ex2.TwilightConsistent;
import org.deuce.test.twilight.ex2.TwilightInconsistent;
import org.deuce.transaction.Twilight;

/**
 * NOTE: This project assumes the correctness of the Twilight-TL2 implementation in the referenced project.
 *
 * NOTE: the programmer should never explicitly invoke the TwilightConsistent and TwilightInconsistent methods
 * themselves. The calling of them will happen automatically -- they correspond to the two cases of the result
 * of prepareCommit: readset is consistent, readset is inconsistent.
 *
 * NOTE: disadvantage of this three separate methods approach. Can't have convenient local variables that are
 * declared and probably initialised in the transactional zone and then use them in the twilight zone methods.
 * You would have to get round this by making the variable a field of the class instead of a local variable.
 * This could quite easily make a class cluttered with fields that really ought to be local variables. Could
 * get around this problem by having nested classes. But if I do this then I might as well just require the
 * programmer implement an interface that requires three methods, atomic(), consistent() and inconsistent().
 * Well, I don't have to. If for the most part, users don't need local variables that much, then the current
 * solution of three separately annotated methods is fine. And in the occasional case where users do need
 * several local variables that need to be accessed in both the transactional and twilight zones, the user
 * could use a nested class to make things cleaner.
 *
 * @author Stephen Tuttlebee
 */

public class Example {
	private int counter = 0; // pretend counter is a shared variable that is accessed by many threads/transactions (actually, it is properly shared if we have multiple threads invoking example() on the same object)
	private int pos; // non-shared (ought to be a local variable)
	private int counterTag; // non-shared (ought to be a local variable)

	@TwilightAtomic(name = "ex")
	public void example() {
		// complex computation

		counterTag = Twilight.newTag();
		pos = counter + 1;
		counter = pos;
		// TODO: temporarily commented out
//		Twilight.markField(counterTag, "counter", Example.class); // NOTE: only way I can see of indicating the variable to be marked is to pass the String name and then use Java reflection
	}

	@TwilightConsistent(name = "ex")
	public void exampleTLConsistent() {
		System.out.println("Transaction "+counter+" finished at "+pos);
	}

	@TwilightInconsistent(name = "ex")
	public void exampleTLInconsistent() {
		if(Twilight.isOnlyInconsistent(counterTag)) {
			Twilight.reload();
			pos = counter + 1;
			counter = pos;
		}
		else {
			Twilight.restart();
		}

		System.out.println("Transaction "+counter+" finished at "+pos);
	}
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
