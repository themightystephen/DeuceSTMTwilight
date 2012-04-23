package org.deuce.test.twilight.ex7;

import org.deuce.Atomic;
import org.deuce.transaction.Twilight;

public class ExampleSeven {
	public int counter = 0;

	@Atomic(twilight=true)
	public void atomicBlockUsingExplicitTwilightPrepareCommit() {
		counter++;

		boolean consistent = Twilight.prepareCommit();
		// twilight zone
		// In this test, just do nothing in twilight zone
		// remember, finalizeCommit operation is implicit at the end of the method block
	}
}
