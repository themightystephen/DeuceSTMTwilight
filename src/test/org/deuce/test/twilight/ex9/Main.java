package org.deuce.test.twilight.ex9;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.deuce.transaction.TransactionException;

/**
 * Test more typical use of Twilight operations. We only use restart operation in twilight zone
 * at the moment.
 *
 * @author Stephen Tuttlebee
 */
public class Main {
	private static int numMaxRetriesExceeded = 0;

	private static final int NUM_THREADS = 5;
	private static final int NUM_TASKS = 500;

	public static void main(String[] args) {
		// construct pool of threads
		ExecutorService es = Executors.newFixedThreadPool(NUM_THREADS);

		final ExampleNine ex = new ExampleNine();
		Runnable task = new Runnable() {
			@Override
			public void run() {
				try {
					ex.atomicBlockUsingTwilightZone();
				}
				catch(TransactionException e) {
					numMaxRetriesExceeded++;
				}
			}
		};

		for(int i = 0; i < NUM_TASKS; i++) {
			System.out.println("Execute task "+i);
			es.execute(task);
		}

		// shutdown executor and wait for all tasks to complete (or fail)
		es.shutdown();
		try {
			es.awaitTermination(1, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// show final value of counters
		System.out.println("CounterA: "+ex.counterA); // expected: 500
		System.out.println("CounterB: "+ex.counterB); // expected: 1000
		System.out.println("CounterC: "+ex.counterC); // expected: the sum of 1 to 500 = 125250
		// show number of times the maximum number of retries was exceeded
		System.out.println("Number of times retries exceeded: "+numMaxRetriesExceeded);
	}
}
