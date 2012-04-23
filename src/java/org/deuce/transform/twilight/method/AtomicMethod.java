package org.deuce.transform.twilight.method;

import java.util.concurrent.atomic.AtomicInteger;

import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.Label;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.transaction.AbortTransactionException;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transaction.TransactionException;
import org.deuce.transaction.TwilightContext;
import org.deuce.transaction.TwilightContextDelegator;
import org.deuce.transform.Agent;
import org.deuce.transform.commons.type.TypeCodeResolver;
import org.deuce.transform.commons.type.TypeCodeResolverFactory;

import static org.deuce.objectweb.asm.Opcodes.*;

/**
 * Used to replaced the original @Atomic method with a method that run the transaction loop.
 * On each round the transaction contest reinitialized and the duplicated method is called with the
 * transaction context.
 *
 * I have / am going modify this class to appropriately instrument @Atomic methods which have the
 * twilight attribute set to true.
 *
 * @author Guy Korland
 * @author Stephen Tuttlebee
 */
public class AtomicMethod extends MethodVisitor {

	final static private AtomicInteger ATOMIC_BLOCK_COUNTER = new AtomicInteger(0);
	final static private String ATOMIC_ANNOTATION_NAME_RETRIES = "retries";
	final static private String ATOMIC_ANNOTATION_NAME_METAINFORMATION = "metainf";
	final static private String ATOMIC_ANNOTATION_NAME_TWILIGHT = "twilight";

	// attributes of current @Atomic method's annotation
	private Integer retries = Integer.getInteger("org.deuce.transaction.retries", Integer.MAX_VALUE);
	private String metainf = "";//Integer.getInteger("org.deuce.transaction.retries", Integer.MAX_VALUE);
	private boolean useTwilightOnlyOperations = false; // indicates non-standard STM operations such as prepareCommit(), finalizeCommit() and other Twilight-only API calls are being by the programmer

	final private String className;
	final private String methodName;
	final private TypeCodeResolver returnResolver;
	final private TypeCodeResolver[] argumentResolvers;
	final private boolean isStatic;
	final private int localVariablesSize;
	final private Method newMethod;

	public AtomicMethod(MethodVisitor mv, String className, String methodName,
			String descriptor, Method newMethod, boolean isStatic) {
		super(Opcodes.ASM4,mv);
		this.className = className;
		this.methodName = methodName;
		this.newMethod = newMethod;
		this.isStatic = isStatic;

		Type returnType = Type.getReturnType(descriptor);
		Type[] argumentTypes = Type.getArgumentTypes(descriptor);

		returnResolver = TypeCodeResolverFactory.getResolver(returnType);
		argumentResolvers = new TypeCodeResolver[argumentTypes.length];
		for(int i = 0; i < argumentTypes.length; ++i) {
			argumentResolvers[i] = TypeCodeResolverFactory.getResolver(argumentTypes[i]);
		}
		localVariablesSize = localVariablesSize(argumentResolvers, isStatic);
	}


	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		final AnnotationVisitor av = super.visitAnnotation(desc, visible);

		if(MethodTransformer.ATOMIC_DESCRIPTOR.equals(desc)) {
			return new AnnotationVisitor(Opcodes.ASM4,av) {
				public void visit(String name, Object value) {
					// retries
					if(name.equals(ATOMIC_ANNOTATION_NAME_RETRIES))
						AtomicMethod.this.retries = (Integer)value;
					// meta information
					if(name.equals(ATOMIC_ANNOTATION_NAME_METAINFORMATION))
						AtomicMethod.this.metainf = (String)value;
					// twilight attribute set (with value true)
					if(name.equals(ATOMIC_ANNOTATION_NAME_TWILIGHT) && (Boolean)value) {
						// if context supports twilight zones, ok
						if(Agent.CONTEXT_SUPPORTS_TWILIGHT)
							AtomicMethod.this.useTwilightOnlyOperations = (Boolean)value;
						// if context *doesn't* support twilight zones, error
						else
							throw new RuntimeException("@Atomic method "+className+"."+methodName+" twilight attribute set to 'true' but selected context "+Agent.CONTEXT_CHOSEN.getName()+" does not support Twilight operations.");
					}

					av.visit(name, value);
				}

				public AnnotationVisitor visitAnnotation(String name, String desc) {
					return av.visitAnnotation(name, desc);
				}

				public AnnotationVisitor visitArray(String name) {
					return av.visitArray(name);
				}

				public void visitEnd() {
					av.visitEnd();
				}

				public void visitEnum(String name, String desc, String value) {
					av.visitEnum(name, desc, value);
				}
			};
		}
		return av;
	}

	/**
	*Standard Version*
	public [static] [returnType] foo([parameters]) [throws XXXException,YYYException] {
		Throwable throwable = null;
		Context context = ContextDelegator.getInstance();
		boolean commit = true;
		[returnType] result = true;
		for( int i=10 ; i>0 ; --i)
		{
			context.init(atomicBlockId, metainf);
			try
			{
				result = foo([parameters],context);
			}
			catch(AbortTransactionException ex)
			{
				context.rollback();
				throw ex;
			}
			catch(TransactionException ex)
			{
				commit = false;
			}
			catch(Throwable ex)
			{
				throwable = ex;
			}

			if(commit)
			{
				if(context.commit()){
					if(throwable != null)
						throw throwable;
					return result;
				}
			}
			else
			{
				context.rollback();
				commit = true;
			}
		}
		throw new TransactionException();
	}


	*Twilight Version* (used if twilight attribute set to true) --- TODO
	public [static] [returnType] foo([parameters]) [throws XXXException,YYYException] {
		Throwable throwable = null;
		Context context = ContextDelegator.getInstance();
		boolean commit = true;
		[returnType] result = true;
		for(int i = 10; i > 0; --i)
		{
			context.init(atomicBlockId, metainf);
			try
			{
				result = foo([parameters],context); // inside this method are calls to Twilight API (including prepareCommit())
			}
			catch(AbortTransactionException ex)
			{
				context.rollback();
				throw ex;
			}
			catch(TransactionException ex)
			{
				commit = false;
			}
			catch(Throwable ex)
			{
				throwable = ex;
			}

			if(commit)
			{
				if(context.finalizeCommit()) {
					if(throwable != null)
						throw throwable;
					return result;
				}
			}
			else
			{
				context.rollback();
				commit = true;
			}
		}
		throw new TransactionException();
	}
	 */
	/**
	 * Calls to init and commit (or finalizeCommit()) are called directly on the Context whereas the
	 * read/write field accesses are done through the ContextDelegator.
	 */
	@Override
	public void visitCode() {
		// additional local variables required
		final int indexIndex = localVariablesSize; // i
		final int contextIndex = indexIndex + 1; // context
		final int throwableIndex = contextIndex + 1;
		final int commitIndex = throwableIndex + 1;
		final int exceptionIndex = commitIndex + 1;
		final int resultIndex = exceptionIndex + 1;

		Label l0 = new Label();
		Label l1 = new Label();
		Label l25 = new Label();
		mv.visitTryCatchBlock(l0, l1, l25, AbortTransactionException.ABORT_TRANSACTION_EXCEPTION_INTERNAL);  // try{
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, TransactionException.TRANSACTION_EXCEPTION_INTERNAL);  // try{
		Label l3 = new Label();
		mv.visitTryCatchBlock(l0, l1, l3, Type.getInternalName(Throwable.class));  // try{

		Label l4 = new Label(); // Throwable throwable = null;
		mv.visitLabel(l4);
		mv.visitInsn(ACONST_NULL);
		mv.visitVarInsn(ASTORE, throwableIndex);

		Label l5 = getContext(contextIndex); // Context context = ContextDelegator.getInstance();

		Label l6 = new Label(); // boolean commit = true;
		mv.visitLabel(l6);
		mv.visitInsn(ICONST_1);
		mv.visitVarInsn(ISTORE, commitIndex);

		Label l7 = new Label(); // ... result = null;
		mv.visitLabel(l7);
		if( returnResolver != null)
		{
			mv.visitInsn( returnResolver.nullValueCode());
			mv.visitVarInsn( returnResolver.storeCode(), resultIndex);
		}

		Label l8 = new Label(); // for( int i=10 ; ... ; ...)
		mv.visitLabel(l8);
		mv.visitLdcInsn( retries);
		mv.visitVarInsn(ISTORE, indexIndex);

		Label l9 = new Label();
		mv.visitLabel(l9);
		Label l10 = new Label();
		mv.visitJumpInsn(GOTO, l10);

		Label l11 = new Label(); // context.init(atomicBlockId, metainf);
		mv.visitLabel(l11);
		mv.visitVarInsn(ALOAD, contextIndex);
		mv.visitLdcInsn(ATOMIC_BLOCK_COUNTER.getAndIncrement());
		mv.visitLdcInsn(metainf);
		mv.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "init", "(ILjava/lang/String;)V");

		/* result = foo( context, ...)  */
		mv.visitLabel(l0);
		if( !isStatic) // load this id if not static
			mv.visitVarInsn(ALOAD, 0);

		// load the rest of the arguments
		int local = isStatic ? 0 : 1;
		for( int i=0 ; i < argumentResolvers.length ; ++i) {
			mv.visitVarInsn(argumentResolvers[i].loadCode(), local);
			local += argumentResolvers[i].localSize(); // move to the next argument
		}

		mv.visitVarInsn(ALOAD, contextIndex); // load the context

		if( isStatic)
			mv.visitMethodInsn(INVOKESTATIC, className, methodName, newMethod.getDescriptor()); // ... = foo( ...
		else
			mv.visitMethodInsn(INVOKEVIRTUAL, className, methodName, newMethod.getDescriptor()); // ... = foo( ...

		if( returnResolver != null)
			mv.visitVarInsn(returnResolver.storeCode(), resultIndex); // result = ...

		mv.visitLabel(l1);
		Label l12 = new Label();
		mv.visitJumpInsn(GOTO, l12);

		/*catch( AbortTransactionException ex)
		{
			throw ex;
		}*/
		mv.visitLabel(l25);
		mv.visitVarInsn(ASTORE, exceptionIndex);
		Label l27 = new Label();
		mv.visitVarInsn(ALOAD, contextIndex);
		mv.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "rollback", "()V");
		mv.visitLabel(l27);
		mv.visitVarInsn(ALOAD, exceptionIndex);
		mv.visitInsn(ATHROW);
		Label l28 = new Label();
		mv.visitLabel(l28);
		mv.visitJumpInsn(GOTO, l12);

		/*catch( TransactionException ex)
		{
			commit = false;
		}*/
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, exceptionIndex);
		Label l13 = new Label();
		mv.visitLabel(l13);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ISTORE, commitIndex);
		Label l14 = new Label();
		mv.visitLabel(l14);
		mv.visitJumpInsn(GOTO, l12);

		/*catch( Throwable ex)
		{
			throwable = ex;
		}*/
		mv.visitLabel(l3);
		mv.visitVarInsn(ASTORE, exceptionIndex);
		Label l15 = new Label();
		mv.visitLabel(l15);
		mv.visitVarInsn(ALOAD, exceptionIndex);
		mv.visitVarInsn(ASTORE, throwableIndex);

		/*
		 * if( commit )
			{
				if( context.commit()){
					if( throwable != null)
						throw (IOException)throwable;
					return result;
				}
			}
			else
			{
				context.rollback();
				commit = true;
			}
		 */
		mv.visitLabel(l12); // if( commit )
		mv.visitVarInsn(ILOAD, commitIndex);
		Label l16 = new Label();
		mv.visitJumpInsn(IFEQ, l16);

		Label l17 = new Label(); // if( context.commit())
		mv.visitLabel(l17);
		mv.visitVarInsn(ALOAD, contextIndex);
		// if programmer has specified to use twilight for this method, call finalizeCommit() otherwise user is either using twilight STM without twilight zone, or their using a non-twilight STM
		if(useTwilightOnlyOperations) {
			System.out.println("useTwilightOnlyOperations is true");
			mv.visitMethodInsn(INVOKEINTERFACE, TwilightContext.TWILIGHTCONTEXT_INTERNAL, "finalizeCommit", "()Z");
		}
		else {
			mv.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "commit", "()Z");
		}
		Label l18 = new Label();
		mv.visitJumpInsn(IFEQ, l18);

		//		if( throwable != null)
		//			throw throwable;
		Label l19 = new Label();
		mv.visitLabel(l19);
		mv.visitVarInsn(ALOAD, throwableIndex);
		Label l20 = new Label();
		mv.visitJumpInsn(IFNULL, l20);
		Label l21 = new Label();
		mv.visitLabel(l21);
		mv.visitVarInsn(ALOAD, throwableIndex);
		mv.visitInsn(ATHROW);

		// return
		mv.visitLabel(l20);
		if( returnResolver == null) {
			mv.visitInsn( RETURN); // return;
		}
		else {
			mv.visitVarInsn(returnResolver.loadCode(), resultIndex); // return result;
			mv.visitInsn(returnResolver.returnCode());
		}

		mv.visitJumpInsn(GOTO, l18);

		// else
		mv.visitLabel(l16); // context.rollback();
		mv.visitVarInsn(ALOAD, contextIndex);
		mv.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "rollback", "()V");

		mv.visitInsn(ICONST_1); // commit = true;
		mv.visitVarInsn(ISTORE, commitIndex);

		mv.visitLabel(l18);  // for( ... ; i>0 ; --i)
		mv.visitIincInsn(indexIndex, -1);
		mv.visitLabel(l10);
		mv.visitVarInsn(ILOAD, indexIndex);
		mv.visitJumpInsn(IFGT, l11);

		// throw new TransactionException("Failed to commit ...");
		Label l23 = throwTransactionException();

		/* locals */
		Label l24 = new Label();
		mv.visitLabel(l24);
		mv.visitLocalVariable("throwable", "Ljava/lang/Throwable;", null, l5, l24, throwableIndex);
		if(Agent.CONTEXT_SUPPORTS_TWILIGHT)
			mv.visitLocalVariable("context", Context.CONTEXT_DESC, null, l6, l24, contextIndex);
		else
			mv.visitLocalVariable("context", TwilightContext.TWILIGHTCONTEXT_DESC, null, l6, l24, contextIndex);
		mv.visitLocalVariable("commit", "Z", null, l7, l24, commitIndex);
		if( returnResolver != null)
			mv.visitLocalVariable("result", returnResolver.toString(), null, l8, l24, resultIndex);
		mv.visitLocalVariable("i", "I", null, l9, l23, indexIndex);
		mv.visitLocalVariable("ex", "Lorg/deuce/transaction/AbortTransactionException;", null, l27, l28, exceptionIndex);
		mv.visitLocalVariable("ex", "Lorg/deuce/transaction/TransactionException;", null, l13, l14, exceptionIndex);
		mv.visitLocalVariable("ex", "Ljava/lang/Throwable;", null, l15, l12, exceptionIndex);

		mv.visitMaxs(6 + localVariablesSize, resultIndex + 2);
		mv.visitEnd();
	}

	/**
	 * Visits the instructions required to store the Context as a local variable.
	 *
	 *
	 * @param contextIndex
	 * @return
	 */
	private Label getContext(final int contextIndex) {
		Label label = new Label();
		mv.visitLabel(label); // Context context = ContextDelegator.getInstance();
		if(Agent.CONTEXT_SUPPORTS_TWILIGHT)
			mv.visitMethodInsn(INVOKESTATIC, TwilightContextDelegator.TWILIGHTCONTEXT_DELEGATOR_INTERNAL, "getInstance", "()Lorg/deuce/transaction/TwilightContext;");
		else
			mv.visitMethodInsn(INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL, "getInstance", "()Lorg/deuce/transaction/Context;");
		mv.visitVarInsn(ASTORE, contextIndex);
		return label;
	}

	private Label throwTransactionException() {
		Label label = new Label();
		mv.visitLabel(label);
		mv.visitTypeInsn(NEW, "org/deuce/transaction/TransactionException");
		mv.visitInsn(DUP);
		mv.visitLdcInsn("Failed to commit the transaction in the defined retries.");
		mv.visitMethodInsn(INVOKESPECIAL, "org/deuce/transaction/TransactionException", "<init>", "(Ljava/lang/String;)V");
		mv.visitInsn(ATHROW);
		return label;
	}

	@Override
	public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
	}

	@Override
	public void visitIincInsn(int var, int increment) {
	}

	@Override
	public void visitInsn(int opcode) {
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
	}

	@Override
	public void visitLabel(Label label) {
	}

	@Override
	public void visitEnd() {
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
	}


	@Override
	public void visitLdcInsn(Object cst) {
	}

	@Override
	public void visitLineNumber(int line, Label start) {
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start,
			Label end, int index) {
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc) {
	}

	@Override
	public void visitMultiANewArrayInsn(String desc, int dims) {
	}

	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
	}

	@Override
	public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
	}

	public void setRetries(int retries) {
		this.retries = retries;
	}

	private int localVariablesSize( TypeCodeResolver[] types, boolean isStatic) {
		int i = isStatic ? 0 : 1;
		for( TypeCodeResolver type : types) {
			i += type.localSize();
		}
		return i;
	}
}
