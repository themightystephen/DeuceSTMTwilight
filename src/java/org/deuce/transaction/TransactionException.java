package org.deuce.transaction;

import org.deuce.objectweb.asm.Type;
import org.deuce.transform.Exclude;

/**
 * If thrown under the context of an active transaction the current transaction
 * context will be rolled back and a new retry will be initialized.
 *
 * NOTE: extends RuntimeException. Did they do this for simplicity of implementation so that there was no need to add throws clauses to the signatures of
 * methods.
 *
 * @author Guy Korland
 * @since 1.0
 */
@Exclude
public class TransactionException extends RuntimeException {

	final static public String TRANSACTION_EXCEPTION_INTERNAL = Type.getInternalName(TransactionException.class);
	final static public TransactionException STATIC_TRANSACTION = new TransactionException();

	public TransactionException(){}

	public TransactionException( String msg){
		super(msg);
	}

	public TransactionException( Throwable cause){
		super(cause);
	}

	@Override
	public Throwable fillInStackTrace(){ return null;} // light exception with no stack trace

	@Override
	public Throwable initCause(Throwable cause) {
		throw new IllegalStateException("Can't set cause.");
	}
}
