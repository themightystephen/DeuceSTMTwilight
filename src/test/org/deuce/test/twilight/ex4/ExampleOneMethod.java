package org.deuce.test.twilight.ex4;

import org.deuce.Atomic;
import org.deuce.transaction.Twilight;

/**
 * <p>NOTE: This project assumes the correctness of the Twilight-TL2 implementation in the referenced
 * project.</p>
 *
 * <p>In this example, everything is in one method - both the transactional zone and the twilight zone
 * are in the same method. The drawback is that the programmer has to write slightly more boilerplate
 * code (on the plus side, they only have to write one annotation instead of three). This includes
 * making an explicit call to Twilight.prepareCommit() in the Twilight API and also having to do an
 * if..else on the return value of prepareCommit(), which indicates whether the readset is consistent
 * or not; the if..else allows the programmer to handle each case differently. Fortunately, one of the
 * key benefits of Deuce remain, which is not having to wrap fields in special STM objects or making
 * calls out to the STM library on every field access. This is done automatically in the background in
 * the instrumentation process (replacing field accesses with calls to the STM library).</p>
 *
 * <p>The only problem with having the additional prepareCommit method in the API is how do we stop the
 * programmer from misusing the API...we have already checked that the use of the other methods in the wrong
 * places is not catastrophic...is misusing prepareCommit() possible in a way that is catastrophic? I'm
 * thinking its actually quite difficult to...for now I will not worry. I will just implement it and then
 * deal with such problems later.</p>
 *
 * <p>Anyway, the below sketches out an example of what the programmer might write with this choice of
 * approach.</p>
 *
 * @author Stephen Tuttlebee
 */

public class ExampleOneMethod {
	private int counter = 0; // pretend counter is a shared variable that is accessed by many threads/transactions (actually, it is properly shared if we have multiple threads invoking example() on the same object)

	// programmer can specify for each trasnasctional method whether twilight 'mode' should be available (as an attribute of the annotation)
	// If it is, then that's cool, the user goes ahead and is allowed to use the Twilight API operations
	// If not, the user is saying "I have no need of a twilight zone" and therefore does not need the Twilight API operations. ALL transactional workflow operations are inserted implicitly for the user in the class transformation. However, we STILL USE THE SAME STM choice. For example, we keep using the tl2twilight implementation regardless of whether the user enabled twilight in the annotation or not (we make it optional -- if they don't specify anything, then default value is false...we assume they want ordinary transaction without twilight zone). Fortunately, tl2twilight still implements the ordinary Context interface and is compatible with being used in an ordinary way (or should be, if I've implemented it right). Thus we should be able to call the init() and commit() operations of the tl2twilight.Context class and everything should be fine. Basically, we switch interfaces between Context and TwilightContext depending on what the user has asked for in the twilight attribute of the @Atomic annotation.
	// Hopefully, multiple twilight algorithms (i.e. twilight on top of something other than TL2) will be able to still conform to the Context interface in addition to the TwilightContext interface. If this is the case, then a twilight STM can be used anywhere a normal STM is used; in some sense, it is backward compatible with a normal STM.

	@Atomic(retries = 50, twilight = true)
	public void example() {
		// TRANSACTIONAL ZONE
		// complex computation

		int counterTag = Twilight.newTag();
		int pos = counter + 1;
		counter = pos;
		// FIXME: will uncomment once markField is implemented
//		Twilight.markField(counterTag, "counter", ExampleOneMethod.class); // NOTE: only way I can see of indicating the variable to be marked is to pass the String name and then use Java reflection

		boolean consistent = Twilight.prepareCommit(); //TODO
		// TWILIGHT ZONE
		if(!consistent) {
			if(Twilight.isOnlyInconsistent(counterTag)) {
				Twilight.reload();
				pos = counter + 1;
				counter = pos;
			}
			else {
				Twilight.restart();
			}
		}
		System.out.println("Transaction "+counter+" finished at "+pos);

		// TODO: decide whether finalizeCommit() should be implicit or explicit (or even optional! -- we could achieve this by making it an idempotent operation if performed on a transaction that's already completed...)
	}
}
