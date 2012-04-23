package org.deuce.test.twilight.ex5;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
	private static final int NUM_THREADS = 25;
	private static final int NUM_TASKS = 500; // TODO: later change back to 2000

	public static void main(String[] args) {
		// common object being worked on by threads
		final ExampleFiveMethod ex = new ExampleFiveMethod();

		// construct pool of threads
		ExecutorService es = Executors.newFixedThreadPool(NUM_THREADS);

		// runnable
		Runnable task = new Runnable() {
			@Override
			public void run() {
				ex.example();
//				System.out.println("Transaction in thread "+ Thread.currentThread() + ". Counter has value "+ex.counter);
			}
		};

		// commented out for debug purposes
		// go (start executing tasks in thread pool!)
		for(int i = 0; i < NUM_TASKS; i++) {
			System.out.println("Execute task "+i);
			es.execute(task);
//			new Thread(task).start();
		}

		// shutdown executor and wait for all tasks to complete (or fail)
		es.shutdown();
		try {
			es.awaitTermination(1, TimeUnit.HOURS); // wait so long that in practice it waits long enough for all tasks to complete
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// show final result of counter
		System.out.println(ex.counter);
	}
}
