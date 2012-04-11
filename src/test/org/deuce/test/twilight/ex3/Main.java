package org.deuce.test.twilight.ex3;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
	private static final int NUM_THREADS = 5;
	private static final int NUM_TASKS = 5000;

	public static void main(String[] args) {
		// common object being worked on by threads
		final Example ex = new Example();

		// construct pool of threads
		ExecutorService es = Executors.newFixedThreadPool(NUM_THREADS);

		// runnable
		Runnable task = new Runnable() {
			@Override
			public void run() {
				ex.example();
			}
		};

		// go (start executing tasks in thread pool!)
		for(int i = 0; i < NUM_TASKS; i++) {
			es.execute(task);
		}
	}
}
