package org.deuce.test.twilight.ex2;
import java.io.IOException;

/**
 * <p>Tests correctness of TL2-Twilight [or should I call it Twilight-TL2?] implementation by explicitly invoking
 * the Context API in the same way that the instrumented code does. See org.deuce.transform.asm.method.AtomicMethod
 * for an example; it has an example inside a comment near the top of the class (with a method called foo()).</p>
 *
 * <p>We add also the synthetic methods which would be generated in the instrumentation by the Deuce Agent. We
 * do not use the Deuce Agent here to do that because we want to test the Context implementations independently
 * of the instrumentation.</p>
 *
 * @author stephen
 */
public class TestExplicitContextTL2Twilight {
	public static void main(String[] args) {
		System.setProperty("org.deuce.transaction.contextClass","org.deuce.transaction.tl2.Context");

		// construct common object with a transactional method, foo()
		final BarExplicit b = new BarExplicit();
//		final Bar b = new Bar();

		// construct two threads that call foo() concurrently (both threads call foo() 500 times)
		Runnable r = new Runnable() {
			public void run() {
				for(int i = 0; i < 500; i++) {
					try {
						int z = b.foo(new Object());
						System.out.println(z);
					} catch (IOException e) {
						// carry on as normal after an exception, but print out the exception before continuing
						e.printStackTrace();
					}
				}
			}
		};
		new Thread(r).start();
		new Thread(r).start();
	}
}
